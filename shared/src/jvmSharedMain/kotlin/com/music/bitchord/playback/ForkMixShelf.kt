package com.music.bitchord.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Fork: the filters an Automix v2 mix rides on each song (the lanes of
 * [com.music.bitchord.playback.smart.automixv2.ForkV2Transition.frameAt]): a 250 Hz low shelf (the
 * bass swap of a preset or a loop), a 4 kHz high shelf (a preset's highs crossing), and the low- and
 * high-pass cuts, as the extension's `BiquadFilterNode`s (Web Audio's shelf: RBJ cookbook, slope 1; its
 * lowpass / highpass: one second-order section, Q 1/sqrt 2, so 12 dB per octave). The cuts used to
 * be the apps' transition filters: two sections, 24 dB/oct (BitChord's DJ slope), twice as steep as
 * the extension's. Those stay open during these mixes now; BitChord's own style still uses them.
 *
 * Gains are aimed with [set] from any thread and glide there per 64-frame block (at most 0.5 dB a
 * block, ~25 ms for the full 12 dB swap) so a jump is never a click. At 0 dB and settled the block
 * is passed through untouched, not a sample read, so every other mix stays byte-identical.
 *
 * It also plays Automix v2's echo out (editor.md §5, versions.md "Tempo too far apart"): the
 * extension feeds the beat before the catch into a one-beat delay line with feedback 0.53. With the
 * send open for exactly one beat and a delay of one beat, what comes out from the catch on is that
 * beat again and again, each pass 0.53 of the last: so the shelf keeps the last beat it passed
 * ([echoRecord], [listen]) and, on [echoCatch], cuts the dry sound in 10 ms and plays that beat back
 * under the decay for the echo's 8 beats, its last 50 ms faded. The deck's fader stays up meanwhile
 * (the dry cut is here), so the repeats are heard over the next song.
 *
 * An editor mix's Effects lane, mid band and Looping lane (editor.md §12, §13) ride here too:
 * - the mid band: a 1 kHz peaking filter, Q 0.7, between the shelves ([aimMid]); 0 dB at rest, passed through;
 * - the Looping lane ([loopStart]): from the block it is asked on, the next [loopStart]'s seconds of the deck repeat
 *   until [loopStop], before the filters, so they still move over it;
 * - on the phone, the deck's fader ([aimFader]; its player's volume held at 1), and after it [fx]: the deck's sends to
 *   the effect returns and its noise ([ForkMixFx]), which ring on after the fader is down. The PC fades in its mixer
 *   and runs one [ForkMixFx] for both decks there.
 */
class ForkMixShelf {

    @Volatile private var targetLowDb = 0f
    @Volatile private var targetHighDb = 0f
    @Volatile private var targetLowPassHz = OPEN_HZ
    @Volatile private var targetHighPassHz = OFF_HZ
    @Volatile private var snapPending = false
    @Volatile private var targetMidDb = 0f
    private var midDb = 0f
    private var midState = FloatArray(0)
    private val midCoef = FloatArray(5)

    /** The phone's effect returns and noise for this deck (after [aimFader]'s fader). */
    val fx = ForkMixFx()

    /**
     * The fader's aim, the main thread's only write to it: 0..1 aimed, NaN released (glide back to 1, then let go). The
     * audio thread alone owns [faderOn] and [fader]. Both threads wrote faderOn and a release flag before, so an aim
     * landing as the audio thread finished a release (it saw faderOn still set, skipped its snap, then the audio thread
     * cleared faderOn) left the new mix's fader off and its deck at full level (audit 2026-11-26 part 1b #3).
     */
    @Volatile private var targetFader = Float.NaN

    /** [reset]'s ask: off at once, at 1, no glide (consumed by the audio thread before it reads [targetFader]). */
    @Volatile private var faderDropPending = false
    private var faderOn = false
    private var fader = 1f

    /** The mid band's aim in dB (editor.md §12; 0: flat), any thread. */
    fun aimMid(db: Float) {
        targetMidDb = if (db.isNaN()) 0f else db.coerceIn(-24f, 0f)
    }

    /**
     * The phone's in-sink fader (editor.md §12: the sends are taken after it, the noise and the returns after that): its
     * first aim starts there with no glide (the incoming deck is silent then), later ones glide 5 ms per full swing.
     */
    fun aimFader(g: Float) {
        targetFader = if (g.isNaN()) 1f else g.coerceIn(0f, 1f)
    }

    // ───────── B's late entry (mix-file.md rule 2) ─────────

    /** 0: off; 1: shut (B waits for its late entry: silent, whatever the fader says); 2: rising to the curve. */
    @Volatile private var lateGate = 0
    @Volatile private var lateRiseFrom = false
    private var lateRise = 0

    /** B's late entry is held: this deck is silent, a player started early included, until [lateOpen]. */
    fun lateHold() {
        lateGate = 1
    }

    /** B starts: from its next frame it rises to the curve over [LATE_RISE_SEC]. */
    fun lateOpen() {
        if (lateGate != 1) return
        lateRiseFrom = true
        lateGate = 2
    }

    /** No late entry: the gate is out of the way. */
    fun lateOff() {
        lateGate = 0
    }

    /** [lateHold]'s silence, then [lateOpen]'s rise, frame by frame. */
    private fun gate(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        when (lateGate) {
            1 -> java.util.Arrays.fill(samples, 0, sampleCount, 0f)
            2 -> {
                if (lateRiseFrom) {
                    lateRiseFrom = false
                    lateRise = 0
                }
                val len = kotlin.math.max(1, (LATE_RISE_SEC * sampleRate).toInt())
                val frames = sampleCount / channelCount
                var f = 0
                while (f < frames && lateRise < len) {
                    val g = lateRise.toFloat() / len
                    val base = f * channelCount
                    for (c in 0 until channelCount) samples[base + c] *= g
                    lateRise++
                    f++
                }
                if (lateRise >= len) lateGate = 0
            }
        }
    }

    /** The mix is over: the fader back up, the mid band flat, nothing sent and no loop; the returns ring on. */
    fun fxRest() {
        targetFader = Float.NaN
        targetMidDb = 0f
        fx.rest()
        loopStop()
    }

    // ───────── the Looping lane ─────────

    @Volatile private var loopAsk = 0.0
    @Volatile private var loopStopPending = false
    private var loopBuf = FloatArray(0)
    private var loopLen = 0
    private var loopFilled = 0
    private var loopAt = 0
    private var loopChannels = 0
    private var loopRate = 0

    /** Written by the audio thread only ([loop]), read by [loopStart] on the main thread. */
    @Volatile private var loopOn = false

    /**
     * From the next block, the next [sec] seconds of this deck repeat (once until [loopStop]). A stop the audio thread
     * hasn't reached yet doesn't hold a new start back: [loop] takes the stop first, then this. It did before, and a
     * sink whose player was retired before another block ran kept loopOn for good, so a later mix's Looping lane on it
     * never engaged (audit 2026-11-26 part 1b #2).
     */
    fun loopStart(sec: Double) {
        // (the stop read before the loop: [loop] clears loopOn before it clears the stop, so no order of the two threads
        // reads a loop on with its stop already gone)
        if (!(sec > 0) || !sec.isFinite() || loopAsk > 0 || (!loopStopPending && loopOn)) return
        loopAsk = sec
    }

    fun loopStop() {
        loopAsk = 0.0
        loopStopPending = true
    }

    private fun loop(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        if (loopStopPending) {
            // (loopOn first: [loopStart] reading between the two writes sees the stop pending or the loop off, never
            // a loop on with no stop to come)
            loopOn = false
            loopStopPending = false
        }
        // what it kept is in another format now: played on, it would come out at the wrong speed or channel layout
        if (loopOn && (channelCount != loopChannels || sampleRate != loopRate)) loopOn = false
        val ask = loopAsk
        if (ask > 0 && !loopOn) {
            loopAsk = 0.0
            loopLen = maxOf(channelCount, (ask * sampleRate).toInt() * channelCount)
            if (loopBuf.size < loopLen) loopBuf = FloatArray(loopLen)
            loopFilled = 0
            loopAt = 0
            loopChannels = channelCount
            loopRate = sampleRate
            loopOn = true
        }
        if (!loopOn) return
        for (i in 0 until sampleCount) {
            if (loopFilled < loopLen) {
                loopBuf[loopFilled++] = samples[i] // the first pass is the deck itself, kept
            } else {
                samples[i] = loopBuf[loopAt]
                if (++loopAt == loopLen) loopAt = 0
            }
        }
    }

    private var lowDb = 0f
    private var highDb = 0f
    private var lowPassHz = OPEN_HZ
    private var highPassHz = OFF_HZ
    private var channels = 0
    private var rate = 0
    private var dirty = false

    // Per channel, two biquads in transposed direct form II: z1, z2 each.
    private var lowState = FloatArray(0)
    private var highState = FloatArray(0)
    private val lowCoef = FloatArray(5)
    private val highCoef = FloatArray(5)

    // The cuts: per channel, one state-variable section each (two integrator states), the transition
    // filters' own section, so a cutoff can move every block without a click.
    private var lowPassState = FloatArray(0)
    private var highPassState = FloatArray(0)
    private val lowPassCoef = FloatArray(3)
    private val highPassCoef = FloatArray(3)

    /**
     * Aims the shelves, in dB (0 = flat), and the cuts, in Hz ([OPEN_HZ] / [OFF_HZ]: no cut), clamped
     * to the range the transition filters take.
     */
    fun set(lowDb: Float, highDb: Float, lowPassHz: Float = OPEN_HZ, highPassHz: Float = OFF_HZ) {
        // A NaN aim is flat / open: coerceIn passes NaN through, and one NaN kept the dB glide walking down 0.5 dB a
        // block for good (or, after a snap, made every coefficient and sample NaN, which process16's roundToInt
        // threw on), and a NaN cutoff stuck the log glide at NaN until the next snap.
        targetLowDb = if (lowDb.isNaN()) 0f else lowDb.coerceIn(-24f, 0f)
        targetHighDb = if (highDb.isNaN()) 0f else highDb.coerceIn(-24f, 0f)
        targetLowPassHz = if (lowPassHz.isNaN()) OPEN_HZ else lowPassHz.coerceIn(MIN_HZ, OPEN_HZ)
        targetHighPassHz = if (highPassHz.isNaN()) OFF_HZ else highPassHz.coerceIn(OFF_HZ, MAX_HIGH_PASS_HZ)
    }

    /** All flat and open. */
    fun open() = set(0f, 0f)

    /** Jumps to these settings with no glide and no history (a new mix on a deck that held another's). */
    fun snap(lowDb: Float, highDb: Float, lowPassHz: Float = OPEN_HZ, highPassHz: Float = OFF_HZ) {
        set(lowDb, highDb, lowPassHz, highPassHz)
        snapPending = true
    }

    /**
     * Starts flat and open with no history, then glides to these settings: a deck that is already
     * playing when a mix begins. A [snap] there would jump an audible song straight into the curve's
     * first cut (Advanced AutoDJ's filter mix starts the outgoing low-pass at 7 kHz): a click and a
     * sudden dulling, where the transition filters this replaced glided in from open.
     */
    fun startOpen(lowDb: Float, highDb: Float, lowPassHz: Float = OPEN_HZ, highPassHz: Float = OFF_HZ) {
        set(lowDb, highDb, lowPassHz, highPassHz)
        openPending = true
    }

    @Volatile private var openPending = false

    /** A seek: the filters' history is from before it, so the next block starts clean at the aimed gains. */
    fun flush() {
        snapPending = true
        echoPark() // what it kept is from before the seek
        fx.clear()
        loopStop()
    }

    /** The sink is torn down: flat, with no history. */
    fun reset() {
        snap(0f, 0f)
        echoPark()
        targetMidDb = 0f
        targetFader = Float.NaN
        faderDropPending = true
        fx.clear()
        loopStop()
    }

    val isShaping: Boolean
        get() = !flat(targetLowDb) || !flat(targetHighDb) || !flat(lowDb) || !flat(highDb) ||
            !flat(targetMidDb) || !flat(midDb) || cutting(targetLowPassHz, targetHighPassHz) || cutting(lowPassHz, highPassHz)

    /** Whether the loop, the fader or the effects touch a block (besides the filters). */
    private val extra: Boolean
        get() = loopOn || loopAsk > 0 || loopStopPending || faderBusy || fx.busy || lateGate != 0

    /** Whether [fade] has anything to do: the fader is on, aimed, or a reset waits (audio thread). */
    private val faderBusy: Boolean get() = faderOn || faderDropPending || !targetFader.isNaN()

    /** Interleaved [samples], [sampleCount] of them, in place. */
    fun process(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        if (channelCount < 1 || sampleRate <= 0 || sampleCount <= 0) return
        if (loopOn || loopAsk > 0 || loopStopPending) loop(samples, sampleCount, channelCount, sampleRate)
        shape(samples, sampleCount, channelCount, sampleRate)
        if (faderBusy) fade(samples, sampleCount, channelCount, sampleRate)
        fx.process(samples, sampleCount, channelCount, sampleRate)
        if (lateGate != 0) gate(samples, sampleCount, channelCount, sampleRate)
    }

    /** The in-sink fader, ramped per frame at most [FADER_SEC] for a full swing; back at 1 with nothing aimed, it lets go. */
    private fun fade(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        if (faderDropPending) {
            faderDropPending = false
            faderOn = false
            fader = 1f
        }
        val aimed = targetFader
        if (!faderOn) {
            if (aimed.isNaN()) return
            // the first aim starts there with no glide (the incoming deck is silent then)
            faderOn = true
            fader = aimed
        }
        val target = if (aimed.isNaN()) 1f else aimed
        val step = 1f / (FADER_SEC * sampleRate)
        val frames = sampleCount / channelCount
        for (f in 0 until frames) {
            val d = target - fader
            fader = if (kotlin.math.abs(d) <= step) target else fader + if (d > 0) step else -step
            if (fader == 1f) continue
            val base = f * channelCount
            for (c in 0 until channelCount) samples[base + c] *= fader
        }
        if (aimed.isNaN() && fader == 1f) faderOn = false
    }

    private fun shape(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        seenRate = sampleRate
        seenChannels = channelCount
        val echoOn = echoBusy()
        if (openPending) {
            openPending = false
            lowDb = 0f
            midDb = 0f
            highDb = 0f
            lowPassHz = OPEN_HZ
            highPassHz = OFF_HZ
            clearHistory()
        }
        if (snapPending) {
            snapPending = false
            lowDb = targetLowDb
            midDb = targetMidDb
            highDb = targetHighDb
            lowPassHz = targetLowPassHz
            highPassHz = targetHighPassHz
            clearHistory()
        }
        if (!isShaping) {
            if (dirty) {
                clearHistory()
                dirty = false
            }
            if (echoOn) echo(samples, sampleCount, channelCount, sampleRate)
            return
        }
        if (channelCount != channels || sampleRate != rate || lowState.size != channelCount * 2) {
            channels = channelCount
            rate = sampleRate
            lowState = FloatArray(channelCount * 2)
            midState = FloatArray(channelCount * 2)
            highState = FloatArray(channelCount * 2)
            lowPassState = FloatArray(channelCount * 2)
            highPassState = FloatArray(channelCount * 2)
        }
        dirty = true
        val frames = sampleCount / channelCount
        var frame = 0
        while (frame < frames) {
            lowDb = step(lowDb, targetLowDb)
            midDb = step(midDb, targetMidDb)
            highDb = step(highDb, targetHighDb)
            lowPassHz = settle(glide(lowPassHz, targetLowPassHz), targetLowPassHz)
            highPassHz = settle(glide(highPassHz, targetHighPassHz), targetHighPassHz)
            val lowOn = !flat(lowDb)
            val highOn = !flat(highDb)
            // A shelf back at flat while a cut still runs drops its history, as the cuts do: kept, a later re-engage
            // started from the old z1/z2 (a click).
            if (lowOn) shelf(lowCoef, LOW_SHELF_HZ, lowDb.toDouble(), low = true) else lowState.fill(0f)
            val midOn = !flat(midDb)
            if (midOn) peak(midCoef, MID_HZ, midDb.toDouble()) else midState.fill(0f)
            if (highOn) shelf(highCoef, HIGH_SHELF_HZ, highDb.toDouble(), low = false) else highState.fill(0f)
            val lowPassOn = lowPassHz < OPEN_HZ
            val highPassOn = highPassHz > OFF_HZ
            if (lowPassOn) section(lowPassCoef, lowPassHz) else lowPassState.fill(0f)
            if (highPassOn) section(highPassCoef, highPassHz) else highPassState.fill(0f)
            val end = minOf(frames, frame + BLOCK)
            for (f in frame until end) {
                val base = f * channelCount
                for (c in 0 until channelCount) {
                    var x = samples[base + c]
                    if (highPassOn) x = svf(highPassCoef, highPassState, c, x, high = true)
                    if (lowPassOn) x = svf(lowPassCoef, lowPassState, c, x, high = false)
                    if (lowOn) x = biquad(lowCoef, lowState, c, x)
                    if (midOn) x = biquad(midCoef, midState, c, x)
                    if (highOn) x = biquad(highCoef, highState, c, x)
                    samples[base + c] = x
                }
            }
            frame = end
        }
        if (echoOn) echo(samples, sampleCount, channelCount, sampleRate)
    }

    /**
     * The 16-bit sink's path: native-order PCM16 between [buffer]'s position and limit, in place
     * (position untouched). With float output off — most Bluetooth routes — Media3 runs the
     * transition filter on 16-bit samples, so without this the bass swap and highs crossing were
     * never heard there. Flat and settled, nothing is read.
     */
    fun process16(buffer: ByteBuffer, channelCount: Int, sampleRate: Int) {
        // the format is noted on the flat path too: echoRecord sizes its buffer from it (Bluetooth routes run here)
        if (channelCount >= 1 && sampleRate > 0) {
            seenRate = sampleRate
            seenChannels = channelCount
        }
        if (!isShaping && !snapPending && !echoBusy() && !extra) {
            if (dirty) {
                clearHistory()
                dirty = false
            }
            return
        }
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val start = view.position()
        val count = view.remaining() / 2
        if (count == 0) return
        if (scratch.size < count) scratch = FloatArray(count)
        for (i in 0 until count) scratch[i] = view.getShort(start + i * 2) / 32768f
        process(scratch, count, channelCount, sampleRate)
        for (i in 0 until count) {
            val x = scratch[i]
            val s = if (x.isNaN()) 0 else (x * 32768f).roundToInt().coerceIn(-32768, 32767) // roundToInt throws on NaN
            view.putShort(start + i * 2, s.toShort())
        }
    }

    private var scratch = FloatArray(0)

    // ───────── the echo out ─────────

    @Volatile private var seenRate = 0
    @Volatile private var seenChannels = 0
    @Volatile private var echoRecording = false
    @Volatile private var echoCaught = false
    /** The catch to start, taken by the audio thread with a compare-and-clear, so a newer catch is never wiped. */
    private val echoCommand = AtomicReference<DoubleArray?>(null)
    @Volatile private var echoParkPending = false
    @Volatile private var pendingTail: FloatArray? = null

    /** The last [tailFrames] frames passed (interleaved, [tailChannels] wide), [tailWrite] the next slot. */
    @Volatile private var tail = FloatArray(0) // replaced on the audio thread, its size read by echoRecord's caller
    private var tailFrames = 0
    private var tailChannels = 0
    private var tailWrite = 0
    private var tailFilled = 0

    /** Ringing: frames since the catch ([echoN]) of [echoTotal]; the beat starts at [echoFrom] in [tail]. */
    private var ringing = false
    private var echoN = 0
    private var echoTotal = 0
    private var echoBeat = 1
    private var echoCut = 1
    private var echoFade = 1
    private var echoFrom = 0
    private var echoFeedback = 0f
    private var echoGain = 1f
    private var echoWet = false

    /**
     * The catch's 10 ms overlap: the dry cut still sounding under the repeat at full sums to up to 2×
     * (+5.4 dB peaks measured on the phones, 2026-11-24). Limited to −1 dBFS on those frames only;
     * the repeats alone are the one source and pass untouched once it has released.
     */
    private val echoLimiter = ForkMixLimiter()

    private fun echoBusy() = echoRecording || ringing || echoCommand.get() != null || echoParkPending

    /**
     * Starts keeping the last [ECHO_KEEP_SEC] passed, for an echo out to come (any thread; the buffer
     * is allocated here, not on the audio thread, and only when the one held is too small).
     */
    fun echoRecord() {
        if (echoRecording) return
        val rate = if (seenRate > 0) maxOf(seenRate, 48_000) else 96_000
        val channels = maxOf(seenChannels, 2)
        val need = (ECHO_KEEP_SEC * rate).toInt() * channels
        if (tail.size < need && (pendingTail?.size ?: 0) < need) pendingTail = FloatArray(need)
        // A park still pending is left for the audio thread to take (it clears the ring and the old tail, then keeps
        // recording). Cleared here, as it was, the PC's every-block record wiped a park before `listen` could see it.
        echoRecording = true
    }

    /**
     * The catch: from the next block the dry sound is cut over [cutSec] and the last [beatSec] passed
     * plays [beats] beats long, each pass [feedback] of the last. Once per echo ([echoPark] clears it).
     */
    fun echoCatch(beatSec: Double, beats: Int, feedback: Double, cutSec: Double) {
        // non-finite: a NaN feedback made every repeat NaN, an infinite beat rang Int.MAX_VALUE frames
        if (echoCaught || !(beatSec > 0) || !beatSec.isFinite() || beats <= 0 || !feedback.isFinite() || !cutSec.isFinite()) return
        echoCaught = true
        echoCommand.set(doubleArrayOf(beatSec, beats.toDouble(), feedback, cutSec))
    }

    /**
     * A new blend starts on this deck: a ring left from the last one (no single-deck block came between them, a skip
     * right after a hand-over) is over, and its tail is that blend's, not this deck's. The next catch starts afresh.
     */
    fun echoEndRing() {
        if (ringing || echoCommand.get() != null) echoParkPending = true
        echoCaught = false
        echoCommand.set(null)
    }

    /** The echo is over or never came: the dry sound back, nothing kept. */
    fun echoPark() {
        echoRecording = false
        echoCaught = false
        echoCommand.set(null)
        echoParkPending = true
    }

    /** Keeps [samples] for an echo out without touching them (the PC's playing deck before a blend). */
    fun listen(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        if (channelCount < 1 || sampleRate <= 0 || sampleCount <= 0) return
        seenRate = sampleRate
        seenChannels = channelCount
        if (echoParkPending || ringing) {
            // parked, or the last blend's echo is over: this deck plays on its own and keeps afresh
            echoParkPending = false
            ringing = false
            echoCaught = false
            echoCommand.set(null)
            tailFilled = 0
        }
        if (!echoRecording) return
        adoptTail(channelCount)
        keep(samples, sampleCount / channelCount, channelCount)
    }

    private fun adoptTail(channelCount: Int) {
        pendingTail?.let {
            tail = it
            pendingTail = null
            tailChannels = 0
        }
        if (tailChannels != channelCount) {
            tailChannels = channelCount
            tailFrames = tail.size / channelCount
            tailWrite = 0
            tailFilled = 0
        }
    }

    private fun keep(samples: FloatArray, frames: Int, channelCount: Int) {
        if (tailFrames == 0) return
        for (f in 0 until frames) {
            System.arraycopy(samples, f * channelCount, tail, tailWrite * channelCount, channelCount)
            if (++tailWrite == tailFrames) tailWrite = 0
        }
        tailFilled = minOf(tailFrames, tailFilled + frames)
    }

    private fun echo(samples: FloatArray, sampleCount: Int, channelCount: Int, sampleRate: Int) {
        if (echoParkPending) {
            echoParkPending = false
            ringing = false
            tailFilled = 0
        }
        val frames = sampleCount / channelCount
        val command = echoCommand.get()
        // compare-and-clear: a newer catch set since the read is left for the next block, never wiped by this one
        if (command != null && !ringing && echoCommand.compareAndSet(command, null)) {
            adoptTail(channelCount)
            echoBeat = maxOf(1, (command[0] * sampleRate).roundToInt())
            echoTotal = (command[1] * command[0] * sampleRate).roundToInt()
            echoFeedback = command[2].toFloat()
            echoCut = maxOf(1, (command[3] * sampleRate).roundToInt())
            echoFade = maxOf(1, (ECHO_FADE_SEC * sampleRate).roundToInt())
            // never heard the whole beat (a blend joined late): the dry cut alone
            echoWet = tailFilled >= echoBeat && tailFrames >= echoBeat
            echoFrom = if (echoWet) (tailWrite - echoBeat + tailFrames) % tailFrames else 0
            echoN = 0
            echoGain = 1f
            echoLimiter.reset()
            ringing = true
        }
        if (!ringing) {
            if (echoRecording) {
                adoptTail(channelCount)
                keep(samples, frames, channelCount)
            }
            return
        }
        val caughtAt = echoN
        for (f in 0 until frames) {
            val base = f * channelCount
            val n = echoN
            if (n >= echoTotal) {
                for (c in 0 until channelCount) samples[base + c] = 0f
                continue
            }
            val into = n % echoBeat
            if (into == 0 && n > 0) echoGain *= echoFeedback
            val dry = (1f - n.toFloat() / echoCut).coerceAtLeast(0f)
            val level = echoGain * minOf(1f, (echoTotal - n).toFloat() / echoFade)
            val at = if (echoWet) ((echoFrom + into) % tailFrames) * channelCount else 0
            for (c in 0 until channelCount) {
                val wet = if (echoWet) tail[at + c] * level else 0f
                samples[base + c] = samples[base + c] * dry + wet
            }
            echoN = n + 1
        }
        if (echoWet) echoLimiter.process(samples, sampleCount, channelCount, sampleRate, active = (echoCut - caughtAt).coerceIn(0, frames))
    }

    /** The echo's level (dry plus repeats) [sec] after the catch, for a headroom trim: 1, then 0.53 a beat. */
    fun echoLevel(sec: Double, beatSec: Double, beats: Int, feedback: Double): Float {
        if (sec < 0) return 1f
        if (!(beatSec > 0) || sec >= beats * beatSec) return 0f
        return Math.pow(feedback, kotlin.math.floor(sec / beatSec)).toFloat()
    }

    private fun clearHistory() {
        lowState.fill(0f)
        midState.fill(0f)
        highState.fill(0f)
        lowPassState.fill(0f)
        highPassState.fill(0f)
    }

    /** One topology-preserving state-variable section (Q 1/sqrt 2) at [hz]: a1, a2, a3 into [k]. */
    private fun section(k: FloatArray, hz: Float) {
        val g = tan(PI * hz.coerceIn(MIN_HZ, rate * MAX_CUTOFF_FRACTION) / rate).toFloat()
        val a1 = 1f / (1f + g * (g + BUTTERWORTH_K))
        k[0] = a1
        k[1] = g * a1
        k[2] = g * k[1]
    }

    /** [x] through [channel]'s section: its low-pass output, or with [high] its high-pass one. */
    private fun svf(k: FloatArray, z: FloatArray, channel: Int, x: Float, high: Boolean): Float {
        val i = channel * 2
        val ic1 = z[i]
        val ic2 = z[i + 1]
        val v3 = x - ic2
        val v1 = k[0] * ic1 + k[1] * v3
        val v2 = ic2 + k[1] * ic1 + k[2] * v3
        z[i] = 2f * v1 - ic1
        z[i + 1] = 2f * v2 - ic2
        return if (high) x - BUTTERWORTH_K * v1 - v2 else v2
    }

    /** A cutoff's step towards [target] per block, in log frequency, as the transition filters glide. */
    private fun glide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_HZ))
        val to = ln(target.coerceAtLeast(MIN_HZ))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    /** Lands on [target] once within [SETTLED_HZ] of it, so an opening cut ends switched off. */
    private fun settle(current: Float, target: Float): Float = if (abs(current - target) <= SETTLED_HZ) target else current

    private fun cutting(lowPass: Float, highPass: Float) = lowPass < OPEN_HZ || highPass > OFF_HZ

    private fun biquad(k: FloatArray, z: FloatArray, channel: Int, x: Float): Float {
        val i = channel * 2
        val y = k[0] * x + z[i]
        z[i] = k[1] * x - k[3] * y + z[i + 1]
        z[i + 1] = k[2] * x - k[4] * y
        return y
    }

    /** RBJ shelf, slope 1, normalised: b0, b1, b2, a1, a2 into [k]. */
    private fun shelf(k: FloatArray, hz: Double, db: Double, low: Boolean) {
        val a = 10.0.pow(db / 40)
        val w0 = 2 * PI * (hz.coerceAtMost(rate * 0.45)) / rate
        val cw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0) // slope 1: (A + 1/A)(1/S - 1) + 2 = 2
        val sa = 2 * sqrt(a) * alpha
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        if (low) {
            b0 = a * ((a + 1) - (a - 1) * cw + sa)
            b1 = 2 * a * ((a - 1) - (a + 1) * cw)
            b2 = a * ((a + 1) - (a - 1) * cw - sa)
            a0 = (a + 1) + (a - 1) * cw + sa
            a1 = -2 * ((a - 1) + (a + 1) * cw)
            a2 = (a + 1) + (a - 1) * cw - sa
        } else {
            b0 = a * ((a + 1) + (a - 1) * cw + sa)
            b1 = -2 * a * ((a - 1) + (a + 1) * cw)
            b2 = a * ((a + 1) + (a - 1) * cw - sa)
            a0 = (a + 1) - (a - 1) * cw + sa
            a1 = 2 * ((a - 1) - (a + 1) * cw)
            a2 = (a + 1) - (a - 1) * cw - sa
        }
        k[0] = (b0 / a0).toFloat()
        k[1] = (b1 / a0).toFloat()
        k[2] = (b2 / a0).toFloat()
        k[3] = (a1 / a0).toFloat()
        k[4] = (a2 / a0).toFloat()
    }

    /** RBJ peaking band (Web Audio's peaking: Q as it is), normalised: b0, b1, b2, a1, a2 into [k]. */
    private fun peak(k: FloatArray, hz: Double, db: Double) {
        val a = 10.0.pow(db / 40)
        val w0 = 2 * PI * (hz.coerceAtMost(rate * 0.45)) / rate
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * MID_Q)
        val a0 = 1 + alpha / a
        k[0] = ((1 + alpha * a) / a0).toFloat()
        k[1] = (-2 * cw / a0).toFloat()
        k[2] = ((1 - alpha * a) / a0).toFloat()
        k[3] = (-2 * cw / a0).toFloat()
        k[4] = ((1 - alpha / a) / a0).toFloat()
    }

    private fun step(current: Float, target: Float): Float {
        val d = target - current
        return if (abs(d) <= MAX_STEP_DB) target else current + if (d > 0) MAX_STEP_DB else -MAX_STEP_DB
    }

    private fun flat(db: Float) = abs(db) < 0.01f

    companion object {
        /** A low-pass here is no cut, as the transition filters' `OPEN_HZ`. */
        const val OPEN_HZ = 20_000f

        /** A high-pass here is no cut, as the transition filters' `OFF_HZ`. */
        const val OFF_HZ = 20f

        /** The extension's shelf corners (crossfade.js blendShape). */
        const val LOW_SHELF_HZ = 250.0
        const val HIGH_SHELF_HZ = 4_000.0

        /** The mid band (editor.md §12 rule 3, fx-plus.js): 1 kHz, Q 0.7. */
        const val MID_HZ = 1_000.0
        private const val MID_Q = 0.7

        /** The in-sink fader's fastest full swing (a drawn gate is an 8 ms ramp). */
        private const val FADER_SEC = 0.005f

        /** B's rise to the curve once its late entry starts (mix-file.md rule 2: within 10 ms). */
        const val LATE_RISE_SEC = 0.01

        private const val MIN_HZ = 10f

        /** What the shelf keeps for an echo out: more than a beat at any tempo the planner mixes. */
        private const val ECHO_KEEP_SEC = 2.0

        /** The echo's last 50 ms are faded out (editor.md §5 rule 3). */
        private const val ECHO_FADE_SEC = 0.05

        /** Automix v2's filter mix opens the next song from up to 2.4 kHz (ForkV2Mix.djShape), so this is above it. */
        private const val MAX_HIGH_PASS_HZ = 2_500f
        private const val MAX_CUTOFF_FRACTION = 0.45f
        private const val BLOCK = 64
        private const val MAX_STEP_DB = 0.5f

        /** The transition filters' glide per 64-frame block, so a cut moves as fast as it did there. */
        private const val GLIDE_RATE = 0.05f
        private const val SETTLED_HZ = 1f

        /** 1/Q of a second-order Butterworth section (Q = 1/sqrt 2): the extension's 12 dB/oct. */
        private val BUTTERWORTH_K = sqrt(2.0).toFloat()
    }
}
