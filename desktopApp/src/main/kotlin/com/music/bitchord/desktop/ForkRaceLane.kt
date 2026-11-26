package com.music.bitchord.desktop

import kotlin.coroutines.cancellation.CancellationException

/**
 * Fork: one source's lane in the PC's addon race (`DesktopSourceRegistry.firstSubstitute`). A lane
 * that throws (a parser, a matcher, a check on a malformed row) is that source's miss, as on the
 * phone (`SourceResolver.attempt` logs "X failed"). It escaped before: the `async` child failed
 * `firstSubstitute`'s `coroutineScope`, the other lanes were cancelled, and `resolveLive`'s select on
 * the lookup rethrew, so a song failed to resolve even when YouTube had answered. Cancellation still
 * goes through (a sibling won, or the timeout hit).
 */
internal object ForkRaceLane {
    suspend fun <T> miss(sourceName: String, songTitle: String, lane: suspend () -> T?): T? =
        try {
            lane()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DesktopTrackLog.log("$sourceName failed for '$songTitle' (${e.javaClass.simpleName}: ${e.message}); counted as a miss")
            null
        }
}
