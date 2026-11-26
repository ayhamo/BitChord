package com.music.bitchord.data.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The combined audit 2026-11-26 step 2: every spelling of this machine is refused. */
class ForkThisMachineTest {

    @Test
    fun everySpellingOfThisMachine() {
        for (h in listOf(
            "localhost", "LOCALHOST.", "a.localhost", "127.0.0.1", "127.0.0.2", "127.1", "2130706433", "0x7f000001",
            "0177.0.0.1", "0.0.0.0", "0.1.2.3", "0", "::1", "[::1]", "::", "[::]", "0:0:0:0:0:0:0:1",
            "::ffff:127.0.0.1", "[::ffff:7f00:1]", "::127.0.0.1", "fe80::1%lo0".replace("fe80::1", "::1"),
        )) assertTrue(ForkThisMachine.isThisMachine(h), h)
    }

    @Test
    fun otherHostsAreNot() {
        for (h in listOf("8.8.8.8", "example.com", "cdn.example", "192.168.0.214", "10.0.0.1", "::ffff:8.8.8.8", "2001:db8::1", "127.example", "1.2.3.4.5", "256.0.0.1", ""))
            assertFalse(ForkThisMachine.isThisMachine(h), h)
    }

    @Test
    fun aNameIsNeverLookedUp() {
        // letters outside hex with a colon: not a literal, so nothing is resolved and it is not this machine
        assertFalse(ForkThisMachine.isThisMachine("localhost.evil:80"))
        assertFalse(ForkThisMachine.isThisMachine("gggg::1"))
    }
}
