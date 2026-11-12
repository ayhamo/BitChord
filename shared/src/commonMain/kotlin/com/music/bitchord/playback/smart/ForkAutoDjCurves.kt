package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/**
 * Fork: how an Original / Advanced AutoDJ mix ([ForkAutoDj]) sounds at a point in it, the
 * extension's `crossfade.js` (`beginAutomix`, `blendShape`, `djShape`), as plain numbers both
 * apps' renderers apply: the two faders, each song's low-pass and high-pass corner, and each
 * song's 250 Hz low shelf and 4 kHz high shelf in dB.
 *
 * Original AutoDJ (version 1):
 *  - every style: equal-power faders (sin / cos);
 *  - blend: the next song's low shelf at the swap depth (-12 dB from 8 bars, -6 dB shorter) until
 *    the middle, then the bass changes hands over one bar centred on it; the playing song's high
 *    shelf 0 to -8 dB across the blend, the next song's -8 to 0;
 *  - filter mix: the same bass swap at -12 dB, the playing song through a high-pass rising
 *    20 Hz to 1.8 kHz (exponential), the next song through a low-pass opening 350 Hz to 20 kHz;
 *  - crossfade: faders only.
 *
 * Advanced AutoDJ (version 2), `djShape`, the phone's own Advanced Automix shapes as the extension
 * took them: the bass handed over through high-pass corners (20 Hz open, 200 Hz gone) in half a beat
 * ending at the middle bar line; the next song in through a high-pass that opens; the playing song's
 * highs closing through a low-pass; the next song's fader full by the swap and the playing one
 * falling after it; [ForkAutoDj.Shape.clash] (both singing) deepening the filters and moving the
 * faders towards a vocal-safe pair. Its 1/sqrt(in + out) trim is the players' own headroom trim,
 * so the faders here are left untrimmed (the extension's floor of 0.5 is never reached: in + out
 * stays under 2).
 */
object ForkAutoDjCurves {

    const val OPEN_HZ = 20_000.0
    const val OFF_HZ = 20.0
    const val LOW_SHELF_HZ = 250.0
    const val HIGH_SHELF_HZ = 4_000.0
    const val HIGH_SHELF_DB = -8.0

    /** One point of a mix. Reused by the caller (the PC's audio thread allocates nothing per block). */
    class Frame {
        var gainIn = 0f
        var gainOut = 1f
        var inLowPassHz = OPEN_HZ.toFloat()
        var inHighPassHz = OFF_HZ.toFloat()
        var outLowPassHz = OPEN_HZ.toFloat()
        var outHighPassHz = OFF_HZ.toFloat()
        var inLowDb = 0f
        var outLowDb = 0f
        var inHighDb = 0f
        var outHighDb = 0f

        fun reset() {
            gainIn = 0f; gainOut = 1f
            inLowPassHz = OPEN_HZ.toFloat(); inHighPassHz = OFF_HZ.toFloat()
            outLowPassHz = OPEN_HZ.toFloat(); outHighPassHz = OFF_HZ.toFloat()
            inLowDb = 0f; outLowDb = 0f; inHighDb = 0f; outHighDb = 0f
        }
    }

    private const val S = PI / 2

    /** [shape] at progress [progress] (0..1 of the mix) into [frame]. */
    fun at(shape: ForkAutoDj.Shape, progress: Float, frame: Frame) {
        val p = progress.toDouble().coerceIn(0.0, 1.0)
        frame.reset()
        if (shape.v2 && shape.style != ForkAutoDj.Style.CROSSFADE) {
            dj(shape, p, frame)
            return
        }
        frame.gainIn = sin(p * S).toFloat()
        frame.gainOut = cos(p * S).toFloat()
        when (shape.style) {
            ForkAutoDj.Style.BLEND -> {
                bassSwap(shape, p, shape.swapDb, frame)
                frame.outHighDb = (HIGH_SHELF_DB * p).toFloat()
                frame.inHighDb = (HIGH_SHELF_DB * (1 - p)).toFloat()
            }
            ForkAutoDj.Style.FILTER -> {
                bassSwap(shape, p, ForkAutoDj.KILL_DB, frame)
                frame.outHighPassHz = (20.0 * (1800.0 / 20.0).pow(p)).toFloat()
                frame.inLowPassHz = (350.0 * (20_000.0 / 350.0).pow(p)).toFloat()
            }
            ForkAutoDj.Style.CROSSFADE -> Unit
        }
    }

    /** The low shelves: the next song's held at [depth] until the bar centred on the middle, the two crossing over it. */
    private fun bassSwap(shape: ForkAutoDj.Shape, p: Double, depth: Double, frame: Frame) {
        val bar = shape.barFraction.coerceIn(0.0, 0.5)
        val x = if (bar <= 0) (if (p >= 0.5) 1.0 else 0.0) else ((p - (0.5 - bar / 2)) / bar).coerceIn(0.0, 1.0)
        frame.inLowDb = (depth * (1 - x)).toFloat()
        frame.outLowDb = (depth * x).toFloat()
    }

    private fun glide(a: Double, b: Double, x: Double) = a * (b / a).pow(x.coerceIn(0.0, 1.0))
    private fun bassCut(x: Double) = glide(20.0, 200.0, x)
    private fun entryHp(p: Double, top: Double, openBy: Double) =
        if (p >= openBy) 20.0 else glide(20.0, top, (1 - p / openBy).pow(0.35))

    private fun dj(shape: ForkAutoDj.Shape, p: Double, frame: Frame) {
        val clash = shape.clash.coerceIn(0.0, 1.0)
        val grid = shape.beats
        fun onBar(f: Double) = if (grid >= 8) (round(f * grid / 4) * 4 / grid).coerceIn(4 / grid, 1 - 4 / grid) else f
        val w = if (grid >= 2) 0.5 / grid else 0.2
        val swapAt = onBar(0.5)
        val h = ((p - (swapAt - w)) / w).coerceIn(0.0, 1.0)
        var r: Double
        var f: Double
        if (shape.style == ForkAutoDj.Style.FILTER) {
            val top = glide(1200.0, 2400.0, clash)
            val openBy = onBar(0.6 + 0.18 * clash)
            frame.outLowPassHz = glide(7000.0, 300.0, p.pow(0.75)).toFloat()
            frame.outHighPassHz = bassCut(h).toFloat()
            frame.inHighPassHz = max(entryHp(p, top, openBy), bassCut(1 - h)).toFloat()
            r = sin(p.pow(0.8) * S)
            f = cos(p.pow(1.6) * S)
        } else {
            val top = glide(520.0, 950.0, clash)
            val openBy = onBar(0.45 + 0.25 * clash)
            val from = onBar(0.3 - 0.18 * clash)
            val floor = glide(2200.0, 1100.0, clash)
            val fallFrom = max(0.0, min(min(swapAt, 0.5), if (grid > 0) 1 - 4 / grid else 0.5))
            frame.outLowPassHz = glide(OPEN_HZ, floor, (p - from) / (1 - from)).toFloat()
            frame.outHighPassHz = bassCut(h).toFloat()
            frame.inHighPassHz = max(bassCut(1 - h), entryHp(p, top, openBy)).toFloat()
            fun held(plain: Double, dj: Double) = plain + (dj - plain) * (1 - clash)
            r = held(sin(p * S), sin((p / swapAt).coerceIn(0.0, 1.0) * S))
            f = held(cos(p * S), if (p <= fallFrom) 1.0 else cos((p - fallFrom) / (1 - fallFrom) * S))
        }
        r += (sin(p.pow(1.65) * S) - r) * clash
        f += (cos(p.pow(0.65) * S) - f) * clash
        frame.gainIn = r.toFloat()
        frame.gainOut = f.toFloat()
    }

    /** The extension's trim on a version-2 pair (for tests and the mix view): 1/sqrt(in + out) past 1, never under 0.5. */
    fun trim(gainIn: Float, gainOut: Float): Float {
        val sum = gainIn + gainOut
        return if (sum <= 1f) 1f else max(0.5f, 1f / kotlin.math.sqrt(sum))
    }
}
