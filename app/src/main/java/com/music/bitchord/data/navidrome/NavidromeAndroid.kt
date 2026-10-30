package com.music.bitchord.data.navidrome

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.music.bitchord.R
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.DebugLog
import com.music.bitchord.download.LyricsTag
import com.music.bitchord.fork.ForkNetwork
import com.music.bitchord.fork.ForkOfflineService
import java.io.File

/** Fork: the phone's side of [NavidromePlatform]: its prefs, cache folder, offline files and switches. */
object NavidromeAndroid {

    fun init(context: Context) {
        val app = context.applicationContext
        NavidromePlatform.kept = NavidromeOffline.Files
        NavidromePlatform.offline = ForkNetwork.offline
        NavidromePlatform.awaitDownload = ForkNetwork::awaitDownload
        NavidromePlatform.onKeepStarted = {
            // Android 12+ refuses a foreground service started from the background (a playlist sync
            // finishing after the app was left); the download still runs, just without the notification.
            try {
                ContextCompat.startForegroundService(app, android.content.Intent(app, ForkOfflineService::class.java))
            } catch (e: IllegalStateException) {
                DebugLog.d("BitChordNavidrome", "offline: no foreground service (${e.message})")
            }
        }
        NavidromePlatform.onlineLyrics = { song -> LyricsTag.forTrack(song)?.let { it.enhanced ?: it.plain } }
        NavidromePlatform.onKeptRow = { com.music.bitchord.ui.screens.ForkLibraryPlaylists.offline.value = it }
        NavidromePlatform.onSong = com.music.bitchord.fork.ForkYouTubeHistory::remember
        NavidromePlatform.scrobble = { com.music.bitchord.fork.ForkSettings.navidromeScrobble.value }
        NavidromeSettings.init(PrefsStore(EncryptedPrefs.open(context, "bitchord_navidrome", "bitchord_navidrome_plain")))
        // Plain prefs: only "no clip for this song" notes, nothing secret.
        NavidromeClips.init(PrefsStore(context.getSharedPreferences("bitchord_navidrome_clips", Context.MODE_PRIVATE)))
        NavidromeSongInfo.init()
        NavidromeOffline.init(
            File(context.filesDir, "navidrome_offline"),
            context.getString(R.string.fork_offline_row),
            context.getString(R.string.fork_offline_gone),
        )
        NavidromeLyrics.init(File(context.cacheDir, "navidrome_lyrics"))
        NavidromeRadio.init(context)
    }

    private class PrefsStore(private val prefs: SharedPreferences) : NavidromePlatform.Store {
        override fun string(key: String): String? = prefs.getString(key, null)
        override fun long(key: String): Long = prefs.getLong(key, 0L)
        override fun putStrings(values: Map<String, String>) {
            prefs.edit().apply { values.forEach { (key, value) -> putString(key, value) } }.apply()
        }
        override fun putLong(key: String, value: Long) {
            prefs.edit().putLong(key, value).apply()
        }
        override fun keys(): Set<String> = prefs.all.keys
        override fun remove(keys: Collection<String>) {
            prefs.edit().apply { keys.forEach(::remove) }.apply()
        }
        override fun clear() {
            prefs.edit().clear().apply()
        }
    }
}
