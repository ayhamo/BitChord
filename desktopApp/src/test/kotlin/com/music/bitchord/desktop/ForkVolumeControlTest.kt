package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** Fork: the top bar speaker's mute and the wheel's 5% steps (ForkVolumeControl.kt, web `wheelVolume.ts`). */
class ForkVolumeControlTest {

    @Test
    fun wheelUpIsLouderDownIsQuieter() {
        assertEquals(0.55f, ForkVolume.afterWheel(0.5f, -1f))
        assertEquals(0.45f, ForkVolume.afterWheel(0.5f, 1f))
        assertEquals(0.5f, ForkVolume.afterWheel(0.5f, 0f))
    }

    @Test
    fun wheelStaysInRangeOnWholePercents() {
        assertEquals(1f, ForkVolume.afterWheel(0.98f, -3f))
        assertEquals(0f, ForkVolume.afterWheel(0.02f, 3f))
        var v = 0.3f
        repeat(7) { v = ForkVolume.afterWheel(v, -1f) }
        assertEquals(0.65f, v)
    }

    @Test
    fun theSpeakerMutesAndComesBack() {
        val muted = ForkVolume.toggle(0.7f, before = null)
        assertEquals(ForkVolume.Toggle(next = 0f, before = 0.7f), muted)
        assertEquals(0.7f, ForkVolume.toggle(0f, before = muted.before).next)
        // Nothing sensible to come back to: full.
        assertEquals(1f, ForkVolume.toggle(0f, before = null).next)
        assertEquals(1f, ForkVolume.toggle(0f, before = 0f).next)
    }

    @Test
    fun theGlyphShowsTheLevel() {
        assertEquals(ForkVolume.Glyph.MUTED, ForkVolume.glyph(0f))
        assertEquals(ForkVolume.Glyph.LOW, ForkVolume.glyph(0.2f))
        assertEquals(ForkVolume.Glyph.HIGH, ForkVolume.glyph(0.8f))
    }
}
