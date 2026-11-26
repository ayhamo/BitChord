package com.music.bitchord.playback.smart.automixv2

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** analysis-cases.md "The curator blend" [all], the extension's test_automix_plan.mjs "The curator blend" (fe61438). */
class ForkV2CuratorTest {

    /** test_automix_plan.mjs song(): a steady grid from 0.4 s, flat energy, no intro, the outro 20 s before the end. */
    private fun song(bpm: Double, sec: Double, keyName: String = "C major", keyConfidence: Double = 0.9, introEnd: Double = 0.0) = ForkV2Analysis().apply {
        this.bpm = bpm
        beatIntervalSec = 60 / bpm
        firstDownbeatSec = 0.4
        gridConfidence = 1.0
        durationSec = sec
        energyCurve = DoubleArray(ceil(sec / (beatIntervalSec * 16)).toInt()) { 1.0 }
        energy = 1.0
        introEndSec = introEnd
        outroStartSec = sec - 20
        tailGridKnown = false
        this.keyName = keyName
        this.keyConfidence = keyConfidence
    }

    private fun ask(cur: ForkV2Analysis, next: ForkV2Analysis, curator: Boolean, ov: ForkV2MixOverride? = null): ForkV2Planner.Plan {
        val s = ForkV2Automix.settings(0)
        s.curator = curator
        return ForkV2Planner.plan(s, "a", "b", cur, next, cur.durationSec, 20.0, 24.5, null, ov)
    }

    private val cur = song(120.0, 200.0)
    private val next = song(120.0, 200.0, introEnd = 40.0)

    @Test
    fun autosBlendSitsEightBarsOnTheDownbeatNearest31sBeforeTheEnd() {
        val p = ask(cur, next, true)
        assertTrue(p.curator)
        assertEquals("blend", p.style)
        assertEquals(8, p.bars)
        assertTrue(abs(p.mixOutTrackTime - 168.4) < 1e-6, "mix-out ${p.mixOutTrackTime}") // 169 s, nearest downbeat (0.4 + 2 s bars)
        assertTrue(abs(p.mixInTrackTime - 32.4) < 1e-6, "mix-in ${p.mixInTrackTime}") // the phrase at 48.4 s less 16 s
        assertEquals(null, p.anchor)
        // not asked for (the loop mix won, or Classic): the usual blend, no flag
        assertFalse(ask(cur, next, false).curator)
    }

    @Test
    fun keysLengthIntroAndAskedForFallBackToTheUsualBlend() {
        assertFalse(ask(cur, song(120.0, 200.0, keyConfidence = 0.3, introEnd = 40.0), true).curator, "keys untrusted")
        assertFalse(ask(cur, song(120.0, 200.0, keyName = "F# major", introEnd = 40.0), true).curator, "keys clash")
        assertFalse(ask(song(120.0, 110.0), next, true).curator, "a song under 2 min")
        assertFalse(ask(cur, song(120.0, 200.0), true).curator, "no intro in the next song")
        val bars4 = ForkV2MixOverride.fromJson(mapOf("v" to 1.0, "bars" to 4.0))
        assertFalse(ask(cur, next, true, bars4).curator, "a length asked for wins")
        val s16 = ForkV2Automix.settings(16).also { it.curator = true }
        assertFalse(ForkV2Planner.plan(s16, "a", "b", cur, next, 200.0, 20.0, 24.5, null).curator, "Up to N bars wins")
        val s2 = ForkV2Planner.Settings().also { it.version = 2; it.curator = true }
        assertFalse(ForkV2Planner.plan(s2, "a", "b", cur, next, 200.0, 20.0, 24.5, null).curator, "Classic")
        assertFalse(ForkV2Planner.plan(ForkV2Automix.settings(0).also { it.curator = true }, "a", "b", cur, next, 200.0, 170.0, 174.5, null).curator, "too late")
    }

    @Test
    fun theFallbackIsTheUsualBlendExactly() {
        val clash = song(120.0, 200.0, keyName = "F# major", introEnd = 40.0)
        assertEquals(ask(cur, clash, false).toString(), ask(cur, clash, true).toString())
        val a = ask(cur, clash, true)
        val b = ask(cur, clash, false)
        assertEquals(b.mixOutTrackTime, a.mixOutTrackTime)
        assertEquals(b.mixInTrackTime, a.mixInTrackTime)
        assertEquals(b.bars, a.bars)
        assertEquals(b.anchor?.mixOut, a.anchor?.mixOut)
    }

    @Test
    fun theAppsAskForItOnlyOverAPlainBlend() {
        val s = ForkV2Automix.settings(0)
        val p = ForkV2Planner.planAuto(s, "a", "b", cur, next, 200.0, 20.0, 24.5, null, null)
        assertTrue(p.curator)
        assertFalse(s.curator, "the settings are left as they were")
        val clash = song(120.0, 200.0, keyName = "F# major", introEnd = 40.0)
        assertEquals(ask(cur, clash, false).toString(), ForkV2Planner.planAuto(s, "a", "b", cur, clash, 200.0, 20.0, 24.5, null, null).toString())
    }
}
