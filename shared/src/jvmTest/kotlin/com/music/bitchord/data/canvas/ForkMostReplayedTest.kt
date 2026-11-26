package com.music.bitchord.data.canvas

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which songs have a "Most replayed" curve to look up (YouTube ids only). */
class ForkMostReplayedTest {

    @Test
    fun onlyYouTubeIdsAreLookedUp() {
        assertTrue(ForkMostReplayed.eligible("dQw4w9WgXcQ"))
        assertFalse(ForkMostReplayed.eligible("navidrome:123"))
        assertFalse(ForkMostReplayed.eligible("content://media/external/audio/media/42"))
        assertFalse(ForkMostReplayed.eligible(""))
    }

    @Test
    fun playFromTheBestPartSaysOneThingAndAlwaysPlays() = runBlocking {
        val said = mutableListOf<String>()
        var looked = 0
        // A locked party: told at once, nothing looked up, nothing played.
        assertNull(ForkMostReplayed.startForPlay({ true }, "locked", { said += it }) { looked++; 60_000L })
        assertEquals(listOf("locked"), said)
        assertEquals(0, looked)
        // No part: from the start, said once.
        said.clear()
        assertEquals(0L, ForkMostReplayed.startForPlay({ false }, "locked", { said += it }) { null })
        assertEquals(listOf(ForkBestParts.FROM_START), said)
        // A part: nothing said.
        said.clear()
        assertEquals(60_000L, ForkMostReplayed.startForPlay({ false }, "locked", { said += it }) { 60_000L })
        assertTrue(said.isEmpty())
    }
}
