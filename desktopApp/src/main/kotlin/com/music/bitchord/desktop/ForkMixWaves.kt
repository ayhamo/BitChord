package com.music.bitchord.desktop

import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Fork: the Automix popup's waveforms on the PC, as the phone draws them.
 *
 * The phone (`CrossfadeController.prefetchMixWaves` → `TrackAnalyzer.mixWaveform` → `MixWaveform`)
 * decodes the two regions a blend covers 30 s before it, a second decode of each song, and reduces
 * them to three peak envelopes (below 200 Hz, the middle, above 2.5 kHz) at 40 columns a second.
 *
 * The PC already decodes every song it analyses from the first sample to the last, at the stream's
 * own rate, before resampling it for the DSP pass (`DesktopTrackAnalyzer.decode`). So the envelopes
 * are measured there, over the whole song, from audio that is already in memory: no second download,
 * no second decode, and the blend can sit anywhere in the song. What is kept is the raw peaks; each
 * popup's slice is normalised on its own, as the phone normalises the region it decoded, so the
 * picture reads the same.
 *
 * The phone measures fresh for every blend and keeps nothing. The PC's analysis runs once a song and
 * is then restored from disk for every later play, so the peaks are kept on disk next to it
 * (`analysis/mixwaves-v1`, about 30 KB for a four-minute song, eight bits a column on a square-root
 * scale), or a song analysed yesterday would never show a waveform. A song analysed before this was
 * added has none until it is analysed again; its lanes stay empty, which the popup already handles.
 *
 * The filter maths is the phone's `MixWaveform.build`, copied rather than moved: that file is
 * upstream's and built on Android's `MediaDataSource`, and the phone stays exactly as it is.
 */

/** The pure part: measuring the bands, slicing them for a blend, and packing them for the disk. */
internal object ForkMixWaveMath {
    /** The phone's `MIX_WAVE_BINS_PER_SECOND`. */
    const val BINS_PER_SECOND = 40.0

    /** The phone's `MixWaveform.LOW_HZ` / `HIGH_HZ`: below is the bass layer, above the highs. */
    private const val LOW_HZ = 200.0
    private const val HIGH_HZ = 2_500.0

    /** The phone's per-band ceilings in `MixWaveform.build`: the highs sit lowest, so the bass reads. */
    const val LOW_CEILING = 1f
    const val MID_CEILING = 0.82f
    const val HIGH_CEILING = 0.5f

    /** Raw (unnormalised) peaks per band, [binsPerSecond] columns a second from the song's first sample. */
    class Peaks(val binsPerSecond: Double, val low: FloatArray, val mid: FloatArray, val high: FloatArray) {
        val bins: Int get() = low.size
    }

    /** A blend's stretch of a song, normalised for drawing, covering [start]..[end] seconds. */
    class Slice(val low: FloatArray, val mid: FloatArray, val high: FloatArray, val start: Double, val end: Double)

    /**
     * The three bands over [samples], mono at [rate] from the song's start. The phone's filters:
     * two one-pole stages per split, a gentle 12 dB/octave, which is all a picture needs and cheap
     * enough at full rate (a four-minute song is a few tens of milliseconds on the analysis thread).
     * Run over the whole song in one go, they never need the phone's 50 ms warm-up.
     */
    fun measure(samples: FloatArray, rate: Double, binsPerSecond: Double = BINS_PER_SECOND): Peaks? {
        if (rate <= 0.0 || samples.isEmpty() || binsPerSecond <= 0.0) return null
        val bins = ceil(samples.size / rate * binsPerSecond).toInt().coerceAtLeast(1)
        val low = FloatArray(bins)
        val mid = FloatArray(bins)
        val high = FloatArray(bins)
        val lowA = (1.0 - exp(-2.0 * PI * LOW_HZ / rate)).toFloat()
        val highA = (1.0 - exp(-2.0 * PI * HIGH_HZ / rate)).toFloat()
        val binStep = binsPerSecond / rate
        var l1 = 0f
        var l2 = 0f
        var h1 = 0f
        var h2 = 0f
        for (index in samples.indices) {
            val x = samples[index]
            l1 += lowA * (x - l1)
            l2 += lowA * (l1 - l2)
            h1 += highA * (x - h1)
            h2 += highA * (h1 - h2)
            val bin = (index * binStep).toInt().coerceAtMost(bins - 1)
            val lo = abs(l2)
            val mi = abs(h2 - l2)
            val hi = abs(x - h2)
            if (lo > low[bin]) low[bin] = lo
            if (mi > mid[bin]) mid[bin] = mi
            if (hi > high[bin]) high[bin] = hi
        }
        return Peaks(binsPerSecond, low, mid, high)
    }

    /**
     * [from]..[to] seconds of [peaks], on whole columns, each band normalised over the slice alone
     * as the phone normalises the region it decoded. Null when under two columns of it exist.
     */
    fun slice(peaks: Peaks, from: Double, to: Double): Slice? {
        val first = floor(max(0.0, from) * peaks.binsPerSecond).toInt().coerceIn(0, peaks.bins)
        val last = ceil(to * peaks.binsPerSecond).toInt().coerceIn(0, peaks.bins)
        if (last - first < 2) return null
        val low = peaks.low.copyOfRange(first, last).also { normalize(it, LOW_CEILING) }
        val mid = peaks.mid.copyOfRange(first, last).also { normalize(it, MID_CEILING) }
        val high = peaks.high.copyOfRange(first, last).also { normalize(it, HIGH_CEILING) }
        return Slice(low, mid, high, first / peaks.binsPerSecond, last / peaks.binsPerSecond)
    }

    /**
     * The phone's `MixWaveform.normalize`: loud passages reach [ceiling], judged by the 97th
     * percentile rather than the single biggest peak, which one transient would otherwise set for
     * the whole picture.
     */
    fun normalize(band: FloatArray, ceiling: Float) {
        val sorted = band.filter { it > 0f }.sorted()
        if (sorted.isEmpty()) return
        val reference = sorted[(sorted.size * 0.97).toInt().coerceAtMost(sorted.size - 1)]
        if (reference <= 1e-6f) return
        for (index in band.indices) band[index] = (band[index] / reference * ceiling).coerceAtMost(ceiling * 1.08f)
    }

    private const val MAGIC = 0x42434d57 // "BCMW"
    private const val VERSION = 1

    /**
     * Eight bits a column per band, on a square-root scale of that band's loudest column. Slices are
     * normalised by ratio, so the scale drops out; the square root keeps the quiet columns, where a
     * linear byte would round a fade's tail to nothing, within about a percent.
     */
    fun encode(peaks: Peaks): ByteArray {
        val bytes = ByteArrayOutputStream(16 + peaks.bins * 3)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeDouble(peaks.binsPerSecond)
            out.writeInt(peaks.bins)
            for (band in arrayOf(peaks.low, peaks.mid, peaks.high)) {
                val top = band.maxOrNull()?.takeIf { it > 0f } ?: 0f
                out.writeFloat(top)
                for (value in band) {
                    out.writeByte(if (top <= 0f) 0 else (sqrt((value / top).coerceIn(0f, 1f)) * 255f).roundToInt())
                }
            }
        }
        return bytes.toByteArray()
    }

    /** What [encode] wrote, or null for anything else (an older layout, a truncated file). */
    fun decode(data: ByteArray): Peaks? = runCatching {
        DataInputStream(data.inputStream()).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return@runCatching null
            val binsPerSecond = input.readDouble()
            val bins = input.readInt()
            if (binsPerSecond <= 0.0 || bins <= 0 || bins > MAX_BINS) return@runCatching null
            val bands = Array(3) {
                val top = input.readFloat()
                FloatArray(bins) { _ ->
                    val q = input.readUnsignedByte() / 255f
                    q * q * top
                }
            }
            Peaks(binsPerSecond, bands[0], bands[1], bands[2])
        }
    }.getOrNull()

    /** Twenty minutes at 40 a second with room over: the analyser's own ceiling (`MAX_SECONDS`). */
    private const val MAX_BINS = 100_000

    /**
     * [view] with both decks' waveforms filled from [outgoing] and [incoming] (either may be null,
     * leaving that deck's lanes empty). Each slice is the deck's own shown span padded by [pad]
     * seconds of the outgoing timeline (the incoming's scaled by its stretch), as the phone pads the
     * region it decodes (`MIX_PREFETCH_PAD_SECONDS`), so the normalisation sees the same audio.
     */
    fun withWaves(view: MixTransitionView, outgoing: Peaks?, incoming: Peaks?, pad: Double = PAD_SECONDS): MixTransitionView {
        val outSpan = view.outEnd - view.outStart
        val ratio = if (outSpan > 0) ((view.inEnd - view.inStart) / outSpan).takeIf { it > 0 } ?: 1.0 else 1.0
        fun MixDeck.filled(peaks: Peaks?, padding: Double): MixDeck {
            val slice = peaks?.let { slice(it, waveStart - padding, waveEnd + padding) } ?: return this
            return withWave(slice.low, slice.mid, slice.high, slice.start, slice.end)
        }
        return view.withDecks(view.outgoing.filled(outgoing, pad), view.incoming.filled(incoming, pad * ratio))
    }

    /** The phone's `MIX_PREFETCH_PAD_SECONDS`. */
    const val PAD_SECONDS = 3.0
}

/**
 * Where the peaks live between being measured and being drawn. [directory] is resolved on first use,
 * so the tests can hand in a folder of their own and nothing reads the analysis home until needed.
 */
internal class ForkMixWaveStore(private val directory: () -> File) {
    /**
     * Peaks measured by the decode of the pass running on this thread, waiting for the pass to
     * succeed. A thread-local because the decode does not know which song it is decoding; the
     * analyser runs one pass at a time on one thread, and each successful pass decodes before it
     * keeps, so what is here is always this pass's.
     */
    private val pending = ThreadLocal<ForkMixWaveMath.Peaks?>()

    /** The last few songs' peaks: the popup only ever wants the playing pair. */
    private val memory = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ForkMixWaveMath.Peaks>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ForkMixWaveMath.Peaks>) = size > MEMORY_ENTRIES
        },
    )

    /** From the analysis decode: [samples] are the whole song, mono at [rate]. Never throws. */
    fun measured(samples: FloatArray, rate: Double) {
        pending.set(runCatching { ForkMixWaveMath.measure(samples, rate) }.getOrNull())
    }

    /** The pass for [trackId] succeeded: what it measured is kept, in memory and on disk. */
    fun keep(trackId: String) {
        val peaks = pending.get() ?: return
        pending.remove()
        if (trackId.isBlank()) return
        memory[trackId] = peaks
        runCatching {
            val folder = directory().also { it.mkdirs() }
            val file = File(folder, fileNameFor(trackId))
            val temporary = File(folder, file.name + ".tmp")
            try {
                temporary.writeBytes(ForkMixWaveMath.encode(peaks))
                // Not File.renameTo: on Windows it fails when the destination exists (AnalysisStore's note).
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
            prune(folder)
        }.onFailure { DesktopTrackLog.log("automix: could not keep the mix waveform of $trackId: ${it.message}") }
    }

    /** [trackId]'s peaks, from memory or the disk. Reads a file: never from the audio thread. */
    fun peaksFor(trackId: String): ForkMixWaveMath.Peaks? {
        if (trackId.isBlank()) return null
        memory[trackId]?.let { return it }
        val file = runCatching { File(directory(), fileNameFor(trackId)) }.getOrNull() ?: return null
        if (!file.isFile) return null
        val peaks = runCatching { ForkMixWaveMath.decode(file.readBytes()) }.getOrNull()
        if (peaks == null) {
            file.delete()
            return null
        }
        memory[trackId] = peaks
        return peaks
    }

    /** The analysis store's ceiling, oldest first, so the two folders age together. */
    private fun prune(folder: File) {
        val files = folder.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    /** AnalysisStore's naming: a track id is not guaranteed to be a legal filename. */
    private fun fileNameFor(trackId: String): String = "${trackId.hashCode().toUInt()}_${trackId.length}.wave"

    private companion object {
        const val MEMORY_ENTRIES = 6
        const val MAX_FILES = 2_000
    }
}

/** The PC's one store, and the popup's filling. */
internal object ForkMixWaves {
    private val store = ForkMixWaveStore { DesktopAnalysisRuntime.analysisHome().resolve("mixwaves-v1") }

    /** One daemon thread for the popup's file reads: a blend's view is built on the audio thread. */
    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BitChord-MixWaves").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    /** `DesktopTrackAnalyzer.decode`'s hook. */
    fun measured(samples: FloatArray, rate: Double) = store.measured(samples, rate)

    /** `DesktopTrackAnalyzer.run`'s hook, once the analysis is in. */
    fun keep(trackId: String) = store.keep(trackId)

    /**
     * Fills [shown]'s lanes off the audio thread and puts the result into [view] only if [shown] is
     * still what it holds: a blend that ended, or was replaced, in the milliseconds the read takes is
     * left alone (the phone's `republishWaves` checks the ids it is showing for the same reason).
     */
    fun fillLater(view: MutableStateFlow<MixTransitionView?>, shown: MixTransitionView, outId: String, inId: String) {
        runCatching {
            worker.execute {
                runCatching {
                    val outgoing = store.peaksFor(outId)
                    val incoming = store.peaksFor(inId)
                    if (outgoing == null && incoming == null) return@runCatching
                    view.compareAndSet(shown, ForkMixWaveMath.withWaves(shown, outgoing, incoming))
                }.onFailure { DesktopTrackLog.log("automix: mix waveforms not drawn: ${it.message}") }
            }
        }
    }
}
