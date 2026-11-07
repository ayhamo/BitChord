package com.music.bitchord.fork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: the phone's build number in the update check ([ForkAppBuild]). */
class ForkAppBuildTest {

    /** Upstream's rule (AppUpdateChecker.isNewer): numeric parts, a "-beta" older than its release. */
    private fun isNewer(latest: String, current: String): Boolean {
        fun parse(raw: String) = raw.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } to raw.contains('-')
        val (l, lPre) = parse(latest)
        val (c, cPre) = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return cPre && !lPre
    }

    private fun offers(latest: String, latestBuild: Int, current: String, currentBuild: Int) =
        ForkAppBuild.offers(latest, latestBuild, current, currentBuild, ::isNewer)

    @Test
    fun `the build is read from its own line in the notes`() {
        assertEquals(42, ForkAppBuild.buildOf("BitChord 1.9.1\r\n\r\nBuild: 42\r\n\r\nSigning certificate …"))
        assertEquals(7, ForkAppBuild.buildOf("**Build:** 7"))
        assertEquals(0, ForkAppBuild.buildOf(null))
        assertEquals(0, ForkAppBuild.buildOf("### New in this build\n- fixed the build 12 crash"))
    }

    @Test
    fun `a higher version is offered whatever the build`() {
        assertTrue(offers("1.9.2", 0, "1.9.1", 50))
        assertFalse(offers("1.9.0", 99, "1.9.1", 1))
    }

    @Test
    fun `the same version is offered only with a higher build`() {
        assertTrue(offers("1.9.1", 43, "1.9.1", 42))
        assertFalse(offers("1.9.1", 42, "1.9.1", 42))
        assertFalse(offers("1.9.1", 41, "1.9.1", 42))
        assertFalse(offers("1.9.1", 0, "1.9.1", 0)) // releases from before the build number
        assertTrue(offers("1.9.1", 5, "1.9.1", 0)) // a local build is offered the release
    }

    @Test
    fun `a beta and its release keep upstream's order`() {
        assertTrue(offers("1.9.1", 0, "1.9.1-beta1", 10))
        assertFalse(offers("1.9.1-beta1", 99, "1.9.1", 1))
    }
}
