package com.music.bitchord.fork

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch

/**
 * Fork: "Pre-load next songs". The next N Navidrome songs in the queue are
 * read whole into the song cache while the current one plays, so they start at
 * once and keep playing if the network drops.
 *
 * Upstream reads ahead for YouTube only (one track, see
 * [AudioCache.prefetchQueue]) and leaves Navidrome out, its server being close
 * by. This does it for Navidrome alone, through the same read-ahead cache
 * writer, so the bytes land under the key playback asks for. It waits a few
 * seconds into a track (the player's own buffer comes first), on networks the
 * "Download over" switches allow, and a new queue position replaces the run.
 * Kept songs (`file://`) and songs already whole in the cache are skipped.
 */
object ForkPreload {

    private const val TAG = "BitChordNavidrome"
    private const val DELAY_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var pending: List<Uri> = emptyList()

    /** The song being written now, and what counts down when its writer has let go of the cache entry; see [yieldTo]. */
    private class Writing(val uri: Uri, val writer: CacheWriter) {
        val done = java.util.concurrent.CountDownLatch(1)
    }

    @Volatile private var writing: Writing? = null

    /** How long a player opening a song waits for its pre-load to let go: one read's worth on a slow link. */
    private const val YIELD_WAIT_MS = 2_000L

    /**
     * Called as the player opens [uri] through the song cache (`AudioCache.playbackFactory`, on the
     * loader's thread). A pre-load still writing that song holds its cache entry for the whole
     * download, and a reader that finds an entry locked streams the song past the cache: the
     * next song, or a crossfade's standby opening it seconds before the end, came down twice at
     * once, on the one song that had to buffer in time. So the pre-load gives way: its writer
     * stops, and the player waits (at most [YIELD_WAIT_MS]) for the entry to be free, then reads
     * and caches the rest itself.
     */
    fun yieldTo(uri: Uri) {
        val now = writing ?: return
        if (!sameSong(now.uri, uri)) return
        now.writer.cancel()
        if (!now.done.await(YIELD_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            Log.d(TAG, "pre-load ${uri.getQueryParameter("id")}: still writing after ${YIELD_WAIT_MS} ms")
        }
    }

    private fun sameSong(a: Uri, b: Uri): Boolean =
        a == b || (a.host == b.host && a.getQueryParameter("id")?.let { it == b.getQueryParameter("id") } == true)

    /**
     * Ids upstream's YouTube read-ahead must leave alone: Navidrome songs stream from their own
     * server, a live station has nothing to read ahead, and (fixes upstream) files on a WebDAV or
     * SMB share have no YouTube stream to fetch.
     */
    fun skipsReadAhead(id: String): Boolean =
        NavidromeSettings.isNavidromeId(id) || com.music.bitchord.data.navidrome.NavidromeRadio.isRadio(id) ||
            com.music.bitchord.data.webdav.WebDavConfig.isWebDavId(id) || com.music.bitchord.data.smb.SmbConfig.isSmbId(id)

    /** Stops with upstream's read-ahead (a pause, the service going): files shouldn't keep coming down with nothing playing. */
    fun cancel() {
        pending = emptyList()
        job?.cancel()
    }

    /** Called with the player whenever upstream's read-ahead is (`PlaybackService.prefetchAround`). */
    fun around(player: Player) {
        val count = ForkSettings.preloadSongs.value
        // The songs that really play next: in shuffle order with shuffle on, not the list's.
        val timeline = player.currentTimeline
        val indices = buildList {
            var index = player.currentMediaItemIndex
            while (size < count && !timeline.isEmpty && index != C.INDEX_UNSET) {
                // Repeat-one would name the current song again: read the queue as plain repeat-off order then.
                val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
                index = timeline.getNextWindowIndex(index, repeat, player.shuffleModeEnabled)
                if (index == C.INDEX_UNSET || index == player.currentMediaItemIndex || index in this) break
                add(index)
            }
        }
        val uris = indices.mapNotNull { index ->
            val item = player.getMediaItemAt(index)
            item.localConfiguration?.uri?.takeIf {
                NavidromeSettings.isNavidromeId(item.mediaId) && (it.scheme == "https" || it.scheme == "http")
            }
        }
        if (uris == pending) return
        pending = uris
        job?.cancel()
        if (uris.isEmpty()) return
        job = scope.launch {
            delay(DELAY_MS)
            var failed = false
            for (uri in uris) {
                ForkNetwork.awaitDownload()
                if (AudioCache.isFullyCached(uri)) continue
                val source = AudioCache.forkReadAhead() ?: return@launch
                val writer = CacheWriter(source, DataSpec(uri), null, null)
                val now = Writing(uri, writer)
                writing = now
                // cache() blocks this thread, so the job can't finish being cancelled until it
                // returns: a sibling waits for the cancel and stops the writer (invokeOnCompletion
                // would only fire after cache() had read the whole file anyway).
                val stopper = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        writer.cancel()
                    }
                }
                try {
                    runInterruptible { writer.cache() }
                    Log.d(TAG, "pre-loaded ${uri.getQueryParameter("id")}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Losing the cache entry to the player (it reached the song first) or a dropped connection.
                    Log.d(TAG, "pre-load ${uri.getQueryParameter("id")}: ${e.message}")
                    failed = true
                } finally {
                    stopper.cancel()
                    if (writing === now) writing = null
                    now.done.countDown()
                }
            }
            // A song that failed (a dropped connection) is tried again at the next read-ahead, not skipped for good.
            if (failed && pending == uris) pending = emptyList()
        }
    }
}
