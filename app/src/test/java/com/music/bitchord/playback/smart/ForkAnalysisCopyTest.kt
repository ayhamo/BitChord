package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkAnalysisCopyTest {

    /** The analyzer reads the Automix copy like the plain entry, and still no substitute or upgrade. */
    @Test
    fun theCopyCountsAsTheCanonicalOpusRendition() {
        assertEquals("abc#automix", ForkAnalysisCopy.keyFor("abc"))
        assertTrue(AutomixAnalysisSource.isCanonicalYouTubeRendition("abc", "abc"))
        assertTrue(AutomixAnalysisSource.isCanonicalYouTubeRendition("abc", "abc#automix"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("abc", "other#automix"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("abc", "abc#alt"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("abc", "abc#automix#alt"))
    }
}
