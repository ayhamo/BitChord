package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The PC's addon race: lanes logging at the same moment on several threads, one of them throwing. */
class ForkRaceLaneTest {

    @Test
    fun twoLanesLogAtOnceAndAThrowingOneIsOnlyItsOwnMiss() = runBlocking {
        repeat(20) { round ->
            val results = coroutineScope {
                listOf(
                    async(Dispatchers.Default) {
                        ForkRaceLane.miss("Addon A", "song $round") {
                            repeat(100) { DesktopTrackLog.log("lane A line $it") }
                            error("a malformed row")
                        }
                    },
                    async(Dispatchers.Default) {
                        ForkRaceLane.miss("Addon B", "song $round") {
                            repeat(100) { DesktopTrackLog.log("lane B line $it") }
                            "B's copy"
                        }
                    },
                ).awaitAll()
            }
            assertEquals(listOf(null, "B's copy"), results, "round $round")
        }
    }

    @Test
    fun aCancelledLaneStaysCancelled() {
        assertFailsWith<CancellationException> {
            runBlocking { ForkRaceLane.miss<String>("Addon A", "song") { throw CancellationException("a sibling won") } }
        }
    }
}
