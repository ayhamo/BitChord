package com.music.bitchord.desktop

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Crossfade on skip: the arm timeout's cut and the audio thread's blend both finished the same
 * skip when they met at the 3 s mark. [ForkCrossfadeOnSkip.claim] lets exactly one through.
 */
class ForkSkipClaimTest {

    @Test
    fun onlyTheFirstClaimWinsAndTheTicketStaysHeld() {
        val ticket = Any()
        val holder = AtomicReference<Any?>(ticket)
        assertTrue(ForkCrossfadeOnSkip.claim(holder, ticket))
        assertFalse(ForkCrossfadeOnSkip.claim(holder, ticket))
        // Still non-null: whatever holds back during a skip keeps holding until the winner clears it.
        assertNotNull(holder.get())
    }

    @Test
    fun aReplacedTicketCannotBeClaimed() {
        val holder = AtomicReference<Any?>(Any())
        assertFalse(ForkCrossfadeOnSkip.claim(holder, Any()))
        holder.set(null)
        assertFalse(ForkCrossfadeOnSkip.claim(holder, Any()))
    }

    @Test
    fun racingFinishersNeverBothWin() {
        repeat(2_000) {
            val ticket = Any()
            val holder = AtomicReference<Any?>(ticket)
            val wins = AtomicInteger()
            val start = CyclicBarrier(2)
            val racers = List(2) {
                thread {
                    start.await()
                    if (ForkCrossfadeOnSkip.claim(holder, ticket)) wins.incrementAndGet()
                }
            }
            racers.forEach { it.join() }
            assertEquals(1, wins.get())
        }
    }
}
