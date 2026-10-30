package com.music.bitchord.data.navidrome

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Plays reported back to the Navidrome server (fork-only), so its web UI shows
 * what is playing and its play counts, "Recently played" and "Most played"
 * (which also feed the Home shelves) include what was heard on the phone.
 *
 * Called from the same two places as ListenBrainz in `PlaybackService`: "now
 * playing" when a song starts or resumes, a finished play when it ended on its
 * own (not when it was skipped), the same rule ListenBrainz follows.
 * Best-effort: a failure is logged and forgotten.
 */
object NavidromeScrobble {

    private const val TAG = "BitChordNavidrome"

    fun nowPlaying(song: Song, scope: CoroutineScope) = send(song, scope, submission = false, timeMs = null)

    fun finished(song: Song, startMs: Long, scope: CoroutineScope) = send(song, scope, submission = true, timeMs = startMs)

    private fun send(song: Song, scope: CoroutineScope, submission: Boolean, timeMs: Long?) {
        if (!NavidromePlatform.scrobble() || !NavidromeSettings.isNavidromeId(song.videoId)) return
        if (!NavidromeSettings.isConfigured() || NavidromePlatform.offline.value) return // offline mode: nothing goes to the server
        val id = song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)
        scope.launch {
            runCatching { NavidromeRepository.client().scrobble(id, submission, timeMs) }
                .onSuccess { Log.d(TAG, "scrobble $id (submission=$submission): ok") }
                .onFailure { Log.d(TAG, "scrobble $id (submission=$submission): ${it.message}") }
        }
    }
}
