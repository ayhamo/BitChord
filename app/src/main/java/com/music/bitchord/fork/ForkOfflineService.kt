package com.music.bitchord.fork

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.music.bitchord.R
import com.music.bitchord.data.navidrome.NavidromeOffline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Fork: keeps "Available offline" downloads going with the app in the
 * background, under a progress notification with a Cancel button.
 *
 * The downloads themselves run in [NavidromeOffline]; this only holds the
 * process in the foreground while there are any, says how far they are (or
 * that they wait for a network the "Download over" switches allow), and goes
 * once they are done. Started by [NavidromeOffline.keep].
 */
class ForkOfflineService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var watcher: Job? = null

    /** Whether this is a foreground service right now; see [offlineServiceStep]. */
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.fork_offline_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Within a few seconds of the start request, whatever the intent is.
        promote()
        if (intent?.action == ACTION_CANCEL) {
            NavidromeOffline.cancelAll()
            finish()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = scope.launch {
                // The first page may not have registered yet when this starts.
                delay(500)
                combine(NavidromeOffline.status, ForkNetwork.canDownload) { status, allowed -> status to allowed }
                    .collect { (status, allowed) ->
                        when (offlineServiceStep(status.active, allowed, inForeground)) {
                            OfflineServiceStep.FINISH -> finish()
                            OfflineServiceStep.PROMOTE -> promote()
                            OfflineServiceStep.LEAVE_FOREGROUND -> {
                                // The notification stays up, saying what it waits for.
                                stopForeground(STOP_FOREGROUND_DETACH)
                                inForeground = false
                                notifyProgress(status, allowed)
                            }
                            OfflineServiceStep.NOTIFY -> notifyProgress(status, allowed)
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        watcher?.cancel()
        watcher = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        cancelNotification() // a notification left up while waiting is no longer the service's
        stopSelf()
    }

    private fun cancelNotification() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    /**
     * Android 15+ gives a data-sync service 6 hours a day (all of them waiting
     * for Wi-Fi included); past that it must stop or the app is killed. The
     * downloads stop with it; each page is kept with what it has (nothing
     * downloaded is thrown away), and keeping it again fetches the rest.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        NavidromeOffline.cancelAll(keepDone = true)
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        // Detached while waiting ([offlineServiceStep]), the notification outlives a service Android
        // stops in the background; nothing would update or take down its "waiting" line after that.
        if (!inForeground) cancelNotification()
        super.onDestroy()
    }

    private fun promote() {
        val notification = build(NavidromeOffline.status.value, ForkNetwork.canDownload.value)
        // Refused when the day's data-sync budget is used up (Android 15+) or the app is in the
        // background: the downloads go on without the notification instead of crashing the app.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
        } catch (e: IllegalStateException) {
            // Also when the network came back with the app in the background (Android 12+ refuses a
            // foreground start then): the downloads go on, without a notification left saying they wait.
            inForeground = false
            cancelNotification()
            stopSelf()
        }
    }

    private fun notifyProgress(status: NavidromeOffline.Status, allowed: Boolean) {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(status, allowed)) }
    }

    private fun build(status: NavidromeOffline.Status, allowed: Boolean): Notification {
        val title = when (status.titles.size) {
            0, 1 -> getString(R.string.fork_offline_notification_one, status.titles.firstOrNull().orEmpty())
            else -> getString(R.string.fork_offline_notification_many, status.titles.size)
        }
        val text = if (allowed) {
            getString(R.string.fork_offline_notification_songs, status.done, status.total)
        } else {
            getString(R.string.fork_offline_notification_waiting)
        }
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, ForkOfflineService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(status.total.coerceAtLeast(1), status.done, status.total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, getString(R.string.fork_offline_notification_cancel), cancel)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "fork_offline"
        const val NOTIFICATION_ID = 7341
        const val ACTION_CANCEL = "com.music.bitchord.fork.OFFLINE_CANCEL"
    }
}

internal enum class OfflineServiceStep { FINISH, PROMOTE, LEAVE_FOREGROUND, NOTIFY }

/**
 * What [ForkOfflineService] does as the downloads and the network change.
 *
 * Android 15+ gives a data-sync service 6 hours a day, and it counts the time spent in the
 * foreground waiting for a network the "Download over" switches allow just the same: an evening
 * on mobile data with Wi-Fi-only on used the day's budget up before a byte came down. So the
 * service leaves the foreground while it waits (the notification stays, saying so) and comes back
 * when the network is allowed again.
 */
internal fun offlineServiceStep(active: Boolean, allowed: Boolean, inForeground: Boolean): OfflineServiceStep = when {
    !active -> OfflineServiceStep.FINISH
    allowed && !inForeground -> OfflineServiceStep.PROMOTE
    !allowed && inForeground -> OfflineServiceStep.LEAVE_FOREGROUND
    else -> OfflineServiceStep.NOTIFY
}
