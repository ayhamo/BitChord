package com.music.bitchord.desktop

import com.music.bitchord.data.canvas.ForkMostReplayed
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork: the PC's seeks for the best part, as on the phone (`ForkMostReplayedPhone`): "Play from the
 * best part" ([playFromBestPart]) and "Your best part"'s Now and Listen from here. (Its "Jump to the
 * most replayed part" went with the switch to v1.10's Best Part pill, which shows the same spot.)
 *
 * The seek is the window's own `seekPlayer` ([install], from `DesktopApp`), the one the scrubber uses:
 * it clamps to the song, does nothing while a party locks the controls, and tells the party a local
 * seek happened. The lookup runs on a scope of its own, since the menu closes on the tap.
 */
internal object ForkMostReplayedDesktop {

    @Volatile private var seek: ((Long) -> Unit)? = null
    @Volatile private var playingId: () -> String? = { null }
    @Volatile private var position: () -> Long = { 0L }
    @Volatile private var play: ((Song) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * From the window: its seek, the song playing now, where it is, and its "play this song" (for
     * "Play from the best part").
     */
    fun install(seek: (Long) -> Unit, playingId: () -> String?, position: () -> Long = { 0L }, play: ((Song) -> Unit)? = null) {
        this.seek = seek
        this.playingId = playingId
        this.position = position
        this.play = play
        com.music.bitchord.data.canvas.ForkBestParts.player = bestPartPlayer
    }

    /** "Your best part"'s Now and Listen from here (canvas.md §14 rule 5), through the window's seek. */
    private val bestPartPlayer = object : com.music.bitchord.data.canvas.ForkBestParts.Player {
        override fun positionOf(songId: String): Long? = ForkMostReplayedDesktop.positionOf(songId)
        override fun seek(songId: String, ms: Long) {
            if (playingId() != songId || DesktopListenTogether.state.value.controlsLocked) return
            ForkMostReplayedDesktop.seek?.invoke(ms)
        }
    }

    /** Where [songId] is now while it plays; null otherwise. */
    fun positionOf(songId: String): Long? = if (playingId() == songId) position().coerceAtLeast(0) else null

    /**
     * "Play from the best part" (Prism's song-menu row): [song]'s best part
     * ([ForkMostReplayed.bestPart]: your own, else the most replayed, else the chorus), then the song
     * played from there (seeking once the window plays it).
     */
    fun playFromBestPart(song: Song) {
        // A second tap replaces the first (as on the phone); on the UI thread.
        pending?.cancel()
        pending = scope.launch {
            val durationMs = com.music.bitchord.data.canvas.ForkMusicVideoMatch.lengthOf(song.durationText) * 1000
            // canvas.md §14 rule 6: 5 s to find it; without one, the song from the start, saying so.
            val ms = ForkMostReplayed.startForPlay(
                locked = { DesktopListenTogether.state.value.controlsLocked },
                lockedText = "Only the party host can change playback",
                say = { ForkNotice.show(it) },
            ) { withContext(Dispatchers.IO) { runCatching { ForkMostReplayed.bestPart(song.videoId, durationMs) }.getOrNull() } } ?: return@launch
            if (playingId() != song.videoId) {
                val p = play ?: return@launch
                p(song)
                // The engine opens the stream first; seek once the song is the one playing (up to 8 s).
                var waited = 0
                while (waited < 40 && !(playingId() == song.videoId && position() > 0)) {
                    kotlinx.coroutines.delay(200)
                    waited++
                }
                if (playingId() != song.videoId) return@launch
            }
            ForkMostReplayedDesktop.seek?.invoke(ms)
        }
    }

    private var pending: kotlinx.coroutines.Job? = null
}
