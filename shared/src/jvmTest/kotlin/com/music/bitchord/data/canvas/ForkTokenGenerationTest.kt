package com.music.bitchord.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A token minted across a sign-out is never kept, whichever order the sign-out and the mint's store land in. */
class ForkTokenGenerationTest {

    private class Cache {
        val gen = ForkTokenGeneration()
        var token: String? = null

        fun signOut() {
            gen.bump()
            token = null
        }

        /** A mint started at [started] storing [minted], as SpotifyToken does: store, then check, undo when stale. */
        fun store(started: Int, minted: String): String? {
            token = minted
            if (!gen.current(started)) {
                token = null
                return null
            }
            return minted
        }
    }

    @Test
    fun `a sign-out before the store voids the mint`() {
        val c = Cache()
        val started = c.gen.start()
        c.signOut()
        assertNull(c.store(started, "old account"))
        assertNull(c.token)
    }

    @Test
    fun `a sign-out after the store clears it`() {
        val c = Cache()
        val started = c.gen.start()
        assertEquals("old account", c.store(started, "old account"))
        c.signOut()
        assertNull(c.token)
    }

    @Test
    fun `a mint under the current sign-in is kept`() {
        val c = Cache()
        c.signOut()
        val started = c.gen.start()
        assertEquals("new account", c.store(started, "new account"))
        assertEquals("new account", c.token)
    }
}
