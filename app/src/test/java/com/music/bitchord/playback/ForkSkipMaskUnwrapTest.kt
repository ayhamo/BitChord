package com.music.bitchord.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Audit 2026-11-24, 3B #1: the session holds the skip mask (a [ForwardingPlayer]) around upstream's
 * `SessionPlayer`, so v1.10's `mediaSession?.player as? SessionPlayer` casts were always null.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ForkSkipMaskUnwrapTest {

    /** Stands in for the private `SessionPlayer`. */
    private interface Session : Player

    private fun session(): Session =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Session::class.java)) { _, _, _ -> null } as Session

    @Test
    fun theSessionPlayerIsFoundBehindTheMask() {
        val inner = session()
        val masked: Player = object : ForwardingPlayer(inner) {}
        assertNull("the plain cast the merge brought in", masked as? Session)
        assertSame(inner, masked.forkUnmasked<Session>())
        assertSame("an unmasked one as it is", inner, (inner as Player).forkUnmasked<Session>())
        assertNull((null as Player?).forkUnmasked<Session>())
    }
}
