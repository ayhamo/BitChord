package com.music.bitchord.fork

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log

/**
 * Fork (owner, 2026-11-06): a song no decoder on the device can play is skipped, with a toast.
 *
 * Seen with the Navidrome Starboy album (Dolby Atmos, `audio/eac3-joc`) on the x86_64 emulator,
 * where the FFmpeg extension does not load and Android has no E-AC-3 decoder: Media3 raises no
 * error for a track no renderer supports, it enables nothing and runs the song's clock in silence.
 * Worse, a period with no enabled track counts as fully buffered, so the player went on preparing
 * the next song, and the next, each holding its `moov` and cover art (~5.7 MB): 17 of them ran the
 * app out of memory (192 MB heap). Skipping as soon as the tracks are known keeps the playing
 * period up with the loading one, so the prepared ones behind it are released.
 *
 * Unplayable means: the song has audio and not one audio track is supported. A music video whose
 * video can't be decoded but whose audio can is left alone. A run of skips shows one toast that
 * counts them, once the run settles; a queue where nothing can play (as many skips in a row as it has songs, repeat
 * included) pauses instead of going round for ever.
 *
 * Called from the service's player listener and after a crossfade hands the session to the other
 * player (that player's tracks arrived while nothing was listening to it), on the main thread.
 */
object ForkUnplayableSkip {

    private const val TAG = "BitChordPlayback"

    private val main = Handler(Looper.getMainLooper())

    /** Where a run of skips stands: reset by any song that has a playable audio track. */
    internal data class State(
        /** Songs skipped in a row. */
        val run: Int = 0,
        val lastSkippedId: String? = null,
        /** The song the last skip went to: only it carries [run] on. */
        val expectedId: String? = null,
    )

    internal enum class Notice { SKIPPED, SKIPPED_MANY, STOPPED, NONE_LEFT }

    /** What to do about an unplayable song: the next [state], skip (else pause), and which toast. */
    internal data class Step(val state: State, val skip: Boolean, val notice: Notice)

    private var state = State()
    private var toast: Toast? = null

    /**
     * The decision for an unplayable [itemId], apart from the player, so it can be tested. Null when
     * it is left alone: onTracksChanged can come twice for one song (a quality swap, the adopt path
     * after it). A skipped song is left at once, so only the one a run paused on can come back; a tap
     * on it plays again ([playWhenReady]) and is checked anew.
     *
     * Only the song a skip went to continues the run. Anything else is a new start (another queue, a
     * tap): seen 2026-11-06, Trilogy right after the Starboy run paused on its first song ("run 22 of
     * 11") instead of skipping it. A queue where nothing can play ([count] skips in a row, repeat
     * included) pauses instead of going round for ever.
     */
    internal fun step(state: State, itemId: String, playWhenReady: Boolean, hasNext: Boolean, count: Int, nextId: () -> String?): Step? {
        if (itemId == state.lastSkippedId && !playWhenReady) return null
        val run = (if (itemId == state.expectedId) state.run else 0) + 1
        val canGoOn = hasNext && run < count
        val notice = when {
            !canGoOn && run > 1 -> Notice.NONE_LEFT
            !canGoOn -> Notice.STOPPED
            run > 1 -> Notice.SKIPPED_MANY
            else -> Notice.SKIPPED
        }
        return Step(State(run, itemId, if (canGoOn) nextId() else null), canGoOn, notice)
    }

    fun check(context: Context, player: Player, tracks: Tracks) {
        if (tracks.groups.isEmpty()) return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty()) return
        // allowExceedsCapabilities: a format beyond what the decoder declares (24-bit/192 kHz FLAC,
        // say) is still tried by Media3 and usually plays; only "no decoder at all" is skipped.
        if (audio.any { it.isSupported(true) }) {
            state = State()
            return
        }
        val item = player.currentMediaItem ?: return
        val count = player.mediaItemCount
        val step = step(state, item.mediaId, player.playWhenReady, player.hasNextMediaItem(), count) {
            player.getMediaItemAt(player.nextMediaItemIndex).mediaId
        } ?: return
        state = step.state
        val format = audio.first().getTrackFormat(0)
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val kind = describe(format.sampleMimeType, format.codecs)
        val run = step.state.run
        Log.w(TAG, "unplayable: '$title' ($kind, ${format.sampleMimeType}) has no decoder here; ${if (step.skip) "skipping" else "pausing"} (run $run of $count)")
        if (step.skip) player.seekToNextMediaItem() else player.pause()
        val text = when (step.notice) {
            Notice.NONE_LEFT -> context.getString(R.string.fork_unplayable_none, kind)
            Notice.STOPPED -> context.getString(R.string.fork_unplayable_stopped, title, kind)
            Notice.SKIPPED_MANY -> context.getString(R.string.fork_unplayable_skipped_many, run, kind)
            Notice.SKIPPED -> context.getString(R.string.fork_unplayable_skipped, title, kind)
        }
        // One toast once the run settles: a show/cancel per skip (21 in a second on the Starboy album)
        // overran Android's toast queue ("Adding more than one toast window", BadTokenException) and
        // the last one, the one that counts, never showed.
        val app = context.applicationContext
        main.removeCallbacks(showToast)
        pendingText = text
        pendingContext = app
        main.postDelayed(showToast, TOAST_SETTLE_MS)
    }

    private var pendingText: String? = null
    private var pendingContext: Context? = null

    private val showToast = Runnable {
        val text = pendingText ?: return@Runnable
        val app = pendingContext ?: return@Runnable
        pendingText = null
        toast?.cancel()
        toast = Toast.makeText(app, text, Toast.LENGTH_LONG).also { it.show() }
    }

    private const val TOAST_SETTLE_MS = 700L

    /** A short name for the format, for the toast. */
    internal fun describe(mime: String?, codecs: String?): String = when (mime) {
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (E-AC-3)"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X -> "DTS"
        MimeTypes.AUDIO_MPEGH_MHA1, MimeTypes.AUDIO_MPEGH_MHM1 -> "MPEG-H"
        null -> codecs ?: "this format"
        else -> mime.substringAfter('/').uppercase()
    }
}
