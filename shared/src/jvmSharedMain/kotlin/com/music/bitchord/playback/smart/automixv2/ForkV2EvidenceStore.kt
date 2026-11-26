package com.music.bitchord.playback.smart.automixv2

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Fork: Automix v2's whole-song evidence, kept per track beside each app's analyses. The ported
 * planner reads five things the fork's [com.music.bitchord.playback.smart.TrackAnalysis] has no
 * field for (the sub-bass ratio, the bass and loudness curves, the whole-song and per-0.5 s pitch
 * profiles) and the key the PC's way ([ForkKeyExtractor], analysis-cases.md "The key"); the extension
 * measures them from the decoded song while analysing it (host.js lowEnd, chromaEvidence,
 * loudnessCurve). Both apps already decode the whole song once for their DSP pass, so [measure] is
 * called right there (one line in the phone's `TrackAnalyzer.structure`, one in the PC's
 * `DesktopTrackAnalyzer.analyse`) on the same mono buffer, and the result is written next to the
 * analyses as the port's own JSON ([ForkV2Analysis.toJson], only the evidence fields and the key
 * filled). Kept out of TrackAnalysis on purpose: that is upstream's model and its stored format.
 *
 * The buffer is the DSP pass's: mono at the native analyser's rate (11 025 Hz on both apps), not the
 * extension's 44.1/48 kHz. Everything measured stays under its Nyquist (sub-bass and bass under
 * 400 Hz; pitch profiles 55 Hz-5 kHz; the K-weighting's shelf is near 1.5 kHz), so the values match
 * the extension's within the resampling's accuracy, but they are not bit-identical to it.
 *
 * Songs analysed before this store existed have no evidence until it is measured for them: the
 * planner skips the rules that need it meanwhile (no sub-bass veto, no beatless bridge, no loudness
 * trim, no key rules), as the extension does for an analysis made before them. Since 2026-11-19
 * [ForkV2EvidenceBackfill] has each app measure it once, in the background, when Automix v2 plans a
 * pair with such a song (an evidence-only decode, no models).
 *
 * Lookups ([get]) run on the players' threads every plan: a hit is a map read, a miss reads the
 * file once and remembers the answer (also "none"), so nothing is read twice.
 */
object ForkV2EvidenceStore {

    /** Bumped when what is measured changes: older files are ignored (measured again on the next analysis). */
    private const val VERSION = "ev3" // ev2: the key the PC's way (ForkKeyExtractor), on its 0.77-0.93 scale; ev3: the power curve (ForkV2Sections)

    @Volatile
    private var dir: File? = null

    private val cache = ConcurrentHashMap<String, ForkV2Analysis>()
    private val none = ConcurrentHashMap.newKeySet<String>()

    /** The folder under each app's data folder that holds one JSON file per song. */
    const val DIR_NAME = "automix_v2_evidence"

    /** Keeps the evidence in [base]/[DIR_NAME]; also makes [ForkV2Automix] read it. Idempotent. */
    fun install(base: File) {
        val directory = File(base, DIR_NAME)
        if (dir == directory) return
        directory.mkdirs()
        dir = directory
        cache.clear()
        none.clear()
        ForkV2Automix.evidence = ::get
        ForkV2EvidenceBackfill.install()
    }

    /** [id]'s evidence, or null when it was never measured. */
    fun get(id: String): ForkV2Analysis? {
        if (id.isBlank()) return null
        cache[id]?.let { return it }
        if (id in none) return null
        val file = fileFor(id) ?: return null
        val read = runCatching {
            if (!file.isFile) return@runCatching null
            val a = ForkV2Analysis.fromJsonText(file.readText())
            a.takeIf { it.analysisVersion == VERSION }
        }.getOrNull()
        if (read == null) none.add(id) else cache[id] = read
        return read
    }

    /**
     * Measures [id]'s evidence from its whole decoded song ([mono] at [rate] Hz) and keeps it under
     * [base] (as [install]). Never
     * throws: a failure (memory included) only leaves the song without evidence, and must not fail
     * the analysis it runs inside.
     */
    fun measure(base: File, id: String, mono: FloatArray, rate: Double) {
        if (id.isBlank() || mono.isEmpty() || !(rate > 0)) return
        try {
            install(base)
            val a = evidence(id, mono, rate)
            val file = fileFor(id) ?: return
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(ForkV2Json.write(a.toJson()))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            cache[id] = a
            none.remove(id)
        } catch (_: Throwable) {
            // OutOfMemoryError included: the analysis itself goes on without evidence.
        }
    }

    /**
     * [id]'s evidence measured from [mono] at [rate] Hz, kept nowhere ([measure] keeps it; the Spotify benchmark,
     * spotify-benchmark.md rule 1, keeps its own outside the app's data).
     */
    fun evidence(id: String, mono: FloatArray, rate: Double): ForkV2Analysis {
        val a = ForkV2Analysis()
        a.fileKey = id
        a.durationSec = mono.size / rate
        ForkV2Evidence.measure(mono, rate, a)
        ForkKeyExtractor.detect(mono, rate.roundToInt())?.let {
            a.keyName = it.name
            a.keyConfidence = it.strength
        }
        a.analysisVersion = VERSION
        return a
    }

    /** Drops what is held in memory (tests). */
    internal fun forget() {
        cache.clear()
        none.clear()
    }

    private fun fileFor(id: String): File? {
        val d = dir ?: return null
        val safe = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return File(d, "$safe-${id.hashCode().toUInt().toString(16)}.json")
    }
}
