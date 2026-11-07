package com.music.bitchord.fork

import com.music.bitchord.data.AppUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: the phone's build number in the update check ([ForkAppBuild]). */
class ForkAppBuildTest {

    /**
     * Upstream's own rule (`AppUpdateChecker.isNewer`, private there), reached by reflection so
     * these tests follow it if upstream ever changes how versions compare.
     */
    private val upstreamIsNewer = AppUpdateChecker::class.java
        .getDeclaredMethod("isNewer", String::class.java, String::class.java)
        .apply { isAccessible = true }

    private fun isNewer(latest: String, current: String): Boolean =
        upstreamIsNewer.invoke(AppUpdateChecker, latest, current) as Boolean

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

    @Test
    fun `a local build shows no build number in Settings`() {
        assertEquals(0, ForkAppBuild.current) // the test build gets no -Pbitchord.build
        assertEquals("", ForkAppBuild.suffix)
    }
}
