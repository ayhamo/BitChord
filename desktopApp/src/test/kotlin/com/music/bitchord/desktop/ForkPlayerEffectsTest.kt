package com.music.bitchord.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.player.FORK_COVER_HOLD_MAX_MS
import com.music.bitchord.ui.player.FORK_GLOW_REACH
import com.music.bitchord.ui.player.ForkBackdropPulse
import com.music.bitchord.ui.player.ForkBassGlow
import com.music.bitchord.ui.player.ForkBassOnsets
import com.music.bitchord.ui.player.ForkCoverFade
import com.music.bitchord.ui.player.ForkCoverFadeState
import com.music.bitchord.ui.player.forkCoverFadeHoldMs
import com.music.bitchord.ui.player.forkCropPlacement
import com.music.bitchord.ui.player.forkEaseInOutSine
import com.music.bitchord.ui.player.forkGlowAccent
import com.music.bitchord.ui.player.forkGlowHalo
import com.music.bitchord.ui.player.forkGlowIdle
import java.util.prefs.Preferences
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Fork: everything behind "Cover fade" and "Bass glow" ([ForkPlayerEffects]) that runs without a
 * screen: the fade's memory, hold and curve, where the old cover is drawn; the meter's kick reading
 * on synthetic audio, the onsets (hits, never level) and their decay, the halo's place, owner,
 * colour and idling; the two switches' saving, and that a backup carries "Cover fade" between the
 * PC and the phone while "Bass glow" stays a PC setting. The drawing itself is checked in the app.
 */
class ForkPlayerEffectsTest {

    private val frame = 1f / 60f

    private val prefs = Preferences.userRoot().node("com.music.bitchord.desktop")
    private val keys = listOf(ForkPlayerEffects.KEY_COVER_FADE, ForkPlayerEffects.KEY_BASS_GLOW, "fork_backdrop_pulse")
    private lateinit var savedPrefs: Map<String, String?>
    private var savedFlags = Triple(false, false, false)

    @BeforeTest
    fun keep() {
        ForkBackdropPulseSetting.enabled // its first read loads the saved switch: not in the middle of a test
        savedPrefs = keys.associateWith { prefs.get(it, null) }
        savedFlags = Triple(ForkCoverFade.enabled.value, ForkBassGlow.enabled.value, ForkBackdropPulse.enabled.value)
    }

    @AfterTest
    fun putBack() {
        savedPrefs.forEach { (key, value) -> if (value == null) prefs.remove(key) else prefs.put(key, value) }
        prefs.flush()
        ForkCoverFade.enabled.value = savedFlags.first
        ForkBassGlow.enabled.value = savedFlags.second
        ForkBackdropPulse.enabled.value = savedFlags.third
        ForkBackdropPulse.silence()
    }

    /** Steps [onsets] through [seconds] of a meter at [bass]; returns each frame's flash. */
    private fun run(onsets: ForkBassOnsets, bass: Float, seconds: Float): List<Float> =
        List((seconds / frame).toInt()) { onsets.step(bass, frame) }

    @Test
    fun `the dissolve eases in and out`() {
        assertEquals(0f, forkEaseInOutSine(0f), 1e-6f)
        assertEquals(0.5f, forkEaseInOutSine(0.5f), 1e-6f)
        assertEquals(1f, forkEaseInOutSine(1f), 1e-6f)
        // Slow at both ends: the first and last tenth move less than a straight line would.
        assertTrue(forkEaseInOutSine(0.1f) < 0.1f)
        assertTrue(forkEaseInOutSine(0.9f) > 0.9f)
    }

    @Test
    fun `the first cover appears as it is, later ones dissolve from the last one shown`() {
        val fade = ForkCoverFadeState<String>()
        val a = "cover A"
        val b = "cover B"
        assertNull(fade.outgoingFor(a), "nothing shown yet: no fade")
        assertNull(forkCoverFadeHoldMs(hasOutgoing = false, loaded = false))
        fade.loaded(a)
        assertNull(fade.outgoingFor(a), "the same request recomposed is no change")
        assertSame(a, fade.outgoingFor(b))
        // B still loading: A is held, at most the give-up time; once B is up, it dissolves at once.
        assertEquals(FORK_COVER_HOLD_MAX_MS, forkCoverFadeHoldMs(hasOutgoing = true, loaded = false))
        assertEquals(0L, forkCoverFadeHoldMs(hasOutgoing = true, loaded = true))
        fade.loaded(b)
        assertSame(b, fade.outgoingFor("cover C"), "a skip mid-fade starts from the cover mostly showing")
    }

    @Test
    fun `a cover that never comes lets the old one go`() {
        val fade = ForkCoverFadeState<String>()
        fade.loaded("cover A")
        assertSame("cover A", fade.outgoingFor("no artwork"))
        fade.gaveUp() // the hold ran out
        assertNull(fade.outgoingFor("the next song"), "the placeholder showed, so nothing to fade from")
        assertTrue(FORK_COVER_HOLD_MAX_MS in 1_000L..10_000L)
    }

    @Test
    fun `requests are compared by identity, as the sleeve builds a new one per song`() {
        val fade = ForkCoverFadeState<StringBuilder>()
        val first = StringBuilder("same url")
        fade.loaded(first)
        assertSame(first, fade.outgoingFor(StringBuilder("same url")))
    }

    @Test
    fun `the old cover is drawn cropped and centred, as the sleeve draws its own`() {
        val square = Size(300f, 300f)
        val (wideAt, wideSize) = forkCropPlacement(square, Size(1600f, 900f))!!
        assertEquals(300f, wideSize.height, 1e-3f)
        assertEquals(533.33f, wideSize.width, 0.01f)
        assertEquals(-116.67f, wideAt.x, 0.01f)
        assertEquals(0f, wideAt.y, 1e-3f)
        val (tallAt, tallSize) = forkCropPlacement(square, Size(100f, 200f))!!
        assertEquals(300f, tallSize.width, 1e-3f)
        assertEquals(-150f, tallAt.y, 1e-3f)
        assertEquals(Offset.Zero to square, forkCropPlacement(square, Size(50f, 50f)))
        assertNull(forkCropPlacement(square, Size.Unspecified))
        assertNull(forkCropPlacement(square, Size(0f, 10f)))
    }

    @Test
    fun `the halo sits round the sleeve, or the upper middle with none`() {
        val cover = Rect(100f, 200f, 500f, 600f)
        val (center, radius) = forkGlowHalo(cover, origin = Offset(50f, 50f), size = Size(1000f, 800f))
        assertEquals(Offset(250f, 350f), center, "in the backdrop's own coordinates")
        assertEquals(400f * FORK_GLOW_REACH, radius, 1e-3f)
        val (alone, reach) = forkGlowHalo(null, Offset.Zero, Size(1000f, 800f))
        assertEquals(Offset(500f, 320f), alone)
        assertEquals(400f * FORK_GLOW_REACH, reach, 1e-3f)
    }

    @Test
    fun `a sleeve leaving keeps another sleeve's place`() {
        val portrait = Any()
        val wide = Any()
        ForkBassGlow.place(portrait, Rect(0f, 0f, 300f, 300f))
        ForkBassGlow.place(wide, Rect(10f, 10f, 410f, 410f))
        ForkBassGlow.release(portrait) // the old layout leaving after the new one laid out
        assertEquals(Rect(10f, 10f, 410f, 410f), ForkBassGlow.anchor)
        ForkBassGlow.place(wide, Rect(0f, 0f, 1f, 300f)) // folded away: ignored
        assertEquals(Rect(10f, 10f, 410f, 410f), ForkBassGlow.anchor)
        ForkBassGlow.release(wide)
        assertNull(ForkBassGlow.anchor)
    }

    @Test
    fun `the glow idles only with nothing heard and nothing showing`() {
        assertTrue(forkGlowIdle(kick = 0f, shown = 0f))
        assertFalse(forkGlowIdle(kick = 0f, shown = 0.2f), "a flash still decaying needs its frames")
        assertFalse(forkGlowIdle(kick = 0.1f, shown = 0f))
        assertTrue(forkGlowIdle(kick = ForkBassOnsets.FLOOR / 2, shown = 0f))
    }

    /**
     * [seconds] of a loud bass line (55 Hz at 0.6) with a kick (50 Hz at full scale for 60 ms)
     * every half second, through the meter in 1024-frame stereo blocks at 48 kHz: each block's
     * duration and the pulse's and the glow's readings after it.
     */
    private fun meterLoudMaster(seconds: Float, meter: ForkBackdropPulseMeter = ForkBackdropPulseMeter()): List<Triple<Float, Float, Float>> {
        val rate = 48_000
        val frames = 1024
        val samples = FloatArray(frames * 2)
        var t = 0
        return List((seconds * rate / frames).toInt()) {
            for (i in 0 until frames) {
                val time = (t + i).toDouble() / rate
                val kick = if (time % 0.5 < 0.06) sin(2 * PI * 50 * time) else 0.0
                val value = (0.6 * sin(2 * PI * 55 * time) + kick).toFloat()
                samples[2 * i] = value
                samples[2 * i + 1] = value
            }
            t += frames
            meter.meter(samples, samples.size, 2, rate)
            Triple(frames.toFloat() / rate, ForkBackdropPulse.bass, ForkBackdropPulse.kick)
        }
    }

    @Test
    fun `a loud master's kicks reach the glow though the pulse's meter is pinned`() {
        ForkBackdropPulse.enabled.value = false
        ForkBassGlow.enabled.value = true // the glow alone keeps the meter running
        val blocks = meterLoudMaster(4.5f)
        val settled = blocks.drop(blocks.size / 9) // after the first half second
        // The pulse's own reading sits at its ceiling: the first glow, which read it, never lit.
        assertTrue(settled.count { it.second > 0.95f } > settled.size * 0.8, "pulse meter not pinned")
        val onsets = ForkBassOnsets()
        var flashes = 0
        var lit = false
        blocks.forEach { (seconds, _, kick) ->
            val value = onsets.step(kick, seconds)
            if (value > 0.3f && !lit) flashes++
            lit = value > 0.3f
        }
        assertEquals(9, flashes, "one flash per kick")
    }

    @Test
    fun `the meter rests while both switches are off and settles on silence`() {
        ForkBassGlow.enabled.value = true
        ForkBackdropPulse.enabled.value = false
        val meter = ForkBackdropPulseMeter() // the engine's one meter
        meterLoudMaster(0.2f, meter)
        assertTrue(ForkBackdropPulse.kick > 0f)
        ForkBassGlow.enabled.value = false
        meterLoudMaster(0.05f, meter)
        assertEquals(0f, ForkBackdropPulse.kick)
        assertEquals(0f, ForkBackdropPulse.bass)
        ForkBassGlow.enabled.value = true
        meter.meter(FloatArray(2048) { 0.5f }, 2048, 2, 48_000)
        meter.silence() // paused
        assertEquals(0f, ForkBackdropPulse.kick)
    }

    @Test
    fun `both switches are saved and read back at start`() {
        ForkPlayerEffects.setCoverFade(true)
        ForkPlayerEffects.setBassGlow(true)
        assertEquals("true", prefs.get(ForkPlayerEffects.KEY_COVER_FADE, null))
        assertTrue(ForkPlayerEffects.bassGlowOn)
        ForkCoverFade.enabled.value = false
        ForkBassGlow.enabled.value = false
        ForkPlayerEffects.install()
        assertTrue(ForkCoverFade.enabled.value)
        assertTrue(ForkBassGlow.enabled.value)
        ForkPlayerEffects.setCoverFade(false)
        ForkPlayerEffects.setBassGlow(false)
        ForkPlayerEffects.install()
        assertFalse(ForkCoverFade.enabled.value)
        assertFalse(ForkPlayerEffects.bassGlowOn)
    }

    @Test
    fun `a steady bass line holds no glow`() {
        val onsets = ForkBassOnsets()
        run(onsets, 0.5f, 2f) // the first sound is a hit; let it pass and the average catch up
        val held = run(onsets, 0.5f, 3f)
        assertTrue(held.all { it == 0f }, "lit on a level: ${held.max()}")
    }

    @Test
    fun `a kick flashes quickly and is gone within 400 ms`() {
        val onsets = ForkBassOnsets()
        run(onsets, 0.25f, 2f)
        run(onsets, 0.25f, 1f)
        val kick = run(onsets, 0.7f, 0.05f)
        val peak = kick.max()
        assertTrue(peak > 0.5f, "peak $peak")
        // Up within the kick's first frames (a ~25 ms attack), not ramping over a beat.
        assertTrue(kick.take(3).max() > 0.4f)
        // The meter lets go slowly (its own release); the flash does not wait for it.
        val after = run(onsets, 0.45f, 0.4f)
        val at250 = after[(0.25f / frame).toInt() - 1]
        assertTrue(at250 < peak * 0.25f, "still ${at250 / peak} of the peak at 250 ms")
        assertTrue(after.last() < 0.05f, "still ${after.last()} at 400 ms")
    }

    @Test
    fun `each kick of a beat is its own flash`() {
        val onsets = ForkBassOnsets()
        run(onsets, 0.2f, 2f)
        var flashes = 0
        var lit = false
        repeat(8) { // 120 bpm: a kick every 0.5 s
            (run(onsets, 0.65f, 0.06f) + run(onsets, 0.2f, 0.44f)).forEach { value ->
                if (value > 0.3f && !lit) flashes++
                lit = value > 0.3f
            }
        }
        assertEquals(8, flashes)
    }

    @Test
    fun `quiet and loud masters flash alike`() {
        // A ratio, not a step: the same kick over a bass line ten times quieter or louder.
        listOf(0.01f, 0.1f, 1f).forEach { line ->
            val onsets = ForkBassOnsets()
            run(onsets, line, 2f)
            val peak = (run(onsets, line * 2.5f, 0.06f) + run(onsets, line, 0.1f)).max()
            assertTrue(peak > 0.5f, "a line at $line: peak $peak")
        }
        // Under the floor nothing is heard, whatever the ratio.
        val faint = ForkBassOnsets()
        run(faint, 0.0005f, 1f)
        assertEquals(0f, run(faint, 0.0015f, 0.1f).max())
    }

    @Test
    fun `silence settles to nothing`() {
        val onsets = ForkBassOnsets()
        run(onsets, 0.2f, 1f)
        run(onsets, 0.8f, 0.05f)
        val paused = run(onsets, 0f, 1f)
        assertEquals(0f, paused.last())
        assertEquals(0f, ForkBassOnsets().step(0f, frame))
    }

    @Test
    fun `the glow takes the most colourful swatch, at full brightness`() {
        val accent = forkGlowAccent(listOf(Color(0.2f, 0.2f, 0.2f), Color(0.4f, 0.1f, 0.1f), Color(0.5f, 0.5f, 0.55f)))
        assertEquals(1f, accent.red, 1e-3f)
        assertEquals(0.25f, accent.green, 0.01f) // sRGB colours keep 8 bits a channel
        assertEquals(Color.White, forkGlowAccent(emptyList()))
        assertEquals(Color.White, forkGlowAccent(listOf(Color.Black)))
    }

    @Test
    fun `a backup carries Cover fade to the phone and keeps Bass glow on the PC`() {
        assertTrue(ForkBackup.FORK.any { it.phoneKey == "cover_fade" && it.pcKey == ForkPlayerEffects.KEY_COVER_FADE })
        assertFalse(ForkBackup.pcOnly(ForkPlayerEffects.KEY_COVER_FADE))
        assertTrue(ForkBackup.pcOnly(ForkPlayerEffects.KEY_BASS_GLOW))
    }
}
