package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeDiscordCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** The cover a presence push sends ([url], null for none) and when its state was handed in. */
internal data class ForkDiscordArt(val url: String?, val askedAt: Long)

/**
 * Fork: puts the Discord cover in front of each `DesktopDiscordRpc` push, the PC side of the
 * phone's `DiscordRPC` hook (both use [NavidromeDiscordCover]: only a public cover, and a server
 * song gets the same song's YouTube or iTunes picture instead of none).
 *
 * The phone simply waits for the cover inside its push, because each push there is a job the next
 * one cancels. The PC's pushes instead run one at a time on a single-thread scope, which keeps
 * them in order only because none of them suspends: a push that waited there for a lookup would
 * let the next track's push run first, and then put the old track back up when its cover arrived.
 * So the wait happens here, off that scope, and the push is queued onto it only once the cover is
 * known, and only if no later state (another track, a pause, presence switched off) was handed in
 * meanwhile ([drop] is called by `clear()`). A cover already known (any non-server song, a server
 * song looked up before, one whose own cover is public) is queued at once, exactly as before.
 *
 * [ForkDiscordArt.askedAt] is when the state was handed in, which is what the position in it
 * belongs to: the presence's start and end instants are counted from it, so a lookup's wait
 * doesn't shift Discord's progress bar.
 */
internal class ForkDiscordCoverQueue(
    private val px: Int,
    private val known: (Song, Int) -> NavidromeDiscordCover.Answer = NavidromeDiscordCover::now,
    private val lookUp: suspend (Song, Int) -> String? = { song, size -> NavidromeDiscordCover.coverFor(song, size) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val asked = AtomicLong()
    private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs [push] on [scope] with [song]'s cover, unless a later [then] or [drop] came first. */
    fun then(scope: CoroutineScope, song: Song, push: suspend CoroutineScope.(ForkDiscordArt) -> Unit) {
        val turn = asked.incrementAndGet()
        val at = clock()
        when (val answer = known(song, px)) {
            is NavidromeDiscordCover.Answer.Ready -> scope.launch {
                if (turn == asked.get()) push(ForkDiscordArt(answer.url, at))
            }
            NavidromeDiscordCover.Answer.Pending -> lookups.launch {
                val url = runCatching { lookUp(song, px) }.getOrNull()
                scope.launch { if (turn == asked.get()) push(ForkDiscordArt(url, at)) }
            }
        }
    }

    /** Pushes asked for before this are not sent. */
    fun drop() {
        asked.incrementAndGet()
    }
}
