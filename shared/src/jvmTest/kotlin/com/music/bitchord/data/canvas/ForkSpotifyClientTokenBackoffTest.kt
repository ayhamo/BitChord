package com.music.bitchord.data.canvas

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkSpotifyClientTokenBackoffTest {

    @Test
    fun `a failed mint is not asked again for ten minutes`() {
        var now = 1_000L
        val backoff = ForkSpotifyClientTokenBackoff(now = { now })
        assertFalse(backoff.blocks("id"))
        backoff.failed("id")
        assertTrue(backoff.blocks("id"))
        now += ForkSpotifyClientTokenBackoff.WINDOW_MS - 1
        assertTrue(backoff.blocks("id"))
        now += 1
        assertFalse(backoff.blocks("id"), "asked again once the window is over")
    }

    @Test
    fun `the backoff is keyed to the client id and cleared on sign-in`() {
        val backoff = ForkSpotifyClientTokenBackoff(now = { 5_000L })
        backoff.failed("old")
        assertFalse(backoff.blocks("new"), "another client id is asked at once")
        assertTrue(backoff.blocks("old"))
        backoff.clear()
        assertFalse(backoff.blocks("old"))
    }
}
