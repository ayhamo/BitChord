package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.TrackFeatures
import com.music.bitchord.playback.smart.automixv2.ForkV2Analysis
import com.music.bitchord.playback.smart.automixv2.ForkV2Automix
import com.music.bitchord.playback.smart.automixv2.ForkV2EvidenceStore
import com.music.bitchord.playback.smart.automixv2.ForkV2Json
import com.music.bitchord.playback.smart.automixv2.ForkV2MixOverride
import com.music.bitchord.playback.smart.automixv2.ForkV2Planner
import java.io.File

/**
 * Fork: the Spotify benchmark (the extension's spec/automix/spotify-benchmark.md): BitChord's analyser,
 * as it runs in the app, over every song of the shared answer key, offline (nothing plays, no queue).
 * Started from the debug bridge (`POST /bench`), since the analyser (the native library and the ONNX
 * models) only loads in the Windows app.
 *
 * - Rule 4: each song of [key] analysed from its YouTube audio in [audio] (the one shared copy, never
 *   downloaded again: a song with no file there is counted, not fetched), written as analysis.schema.json
 *   to `<out>/<videoId>.json`. Its v2 evidence is measured from the same buffer and kept with it, never in
 *   the app's own analyses (rule 1).
 * - Rule 5's cues: Auto blend plans (default settings, both songs' analyses ours, no loop mix) of every mix of the
 *   exports in [exports] whose songs are both in the key, at playhead [pos] (30 s, earliest 34.5 s), written to
 *   `<out>/plans.json` (format spotify-bench-plans) for the shared scorer, spotify_bench.mjs. Classic (Advanced
 *   AutoDJ, version 2) unless asked otherwise, as spotify_bench.mjs --plan and the pc/ and phone/ plans are
 *   (spotify-benchmark.md; master, 2026-11-25: v3 plans had pushed the mix-out median to 84 beats, a mismatch,
 *   not a regression); `version=3` [&curator=1] plans Automix v2 as `--plan --version 3 [--curator]` does.
 *
 * Layout (5b418181): the bench folder holds `audio/`, `key.json`, and `bitchord/` for this app's files.
 *
 * A song already written is kept (a second run goes on where one stopped).
 */
internal object ForkSpotifyBench {

    @Volatile var status: String = "idle"
        private set

    @Volatile private var running = false

    /** [bench] the shared folder ([key] `key.json`, [audio] `audio/`, out `bitchord/`), [exports] the Spotify exports' folder. */
    fun start(bench: File, exports: File?, pos: Double, evidence: Boolean = false, version: Int = 2, curator: Boolean = false): String {
        val key = File(bench, "key.json")
        val audio = File(bench, "audio")
        val out = File(bench, "bitchord")
        if (running) return "already running: $status"
        if (!key.isFile) return "no key file at $key"
        if (!audio.isDirectory) return "no audio folder at $audio"
        running = true
        status = "starting"
        Thread({
            try {
                run(key, audio, out, exports ?: bench.parentFile, pos, evidence, version, curator)
            } catch (t: Throwable) {
                status = "failed: ${t.message}"
            } finally {
                running = false
            }
        }, "BitChord-SpotifyBench").apply { isDaemon = true }.start()
        return "started"
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(key: File, audio: File, out: File, exports: File?, pos: Double, evidence: Boolean, version: Int, curator: Boolean) {
        out.mkdirs()
        val songs = (ForkV2Json.parse(key.readText()) as Map<String, Any?>)["songs"] as? Map<String, Any?> ?: emptyMap()
        val analyzer = DesktopTrackAnalyzer()
        var ev: ForkV2Analysis? = null
        analyzer.forkEvidence = { id, mono -> ev = ForkV2EvidenceStore.evidence(id, mono, TrackFeatures.sampleRate) }
        var done = 0
        var kept = 0
        var noAudio = 0
        var failed = 0
        val analyses = HashMap<String, ForkV2Analysis>()
        if (evidence) remeasure(songs.keys.toList(), audio, out, analyzer)
        for ((i, id) in songs.keys.withIndex()) {
            status = "analysing ${i + 1}/${songs.size} (written $done, kept $kept, no audio $noAudio, failed $failed)"
            val target = File(out, "$id.json")
            // kept when it is of this analysis' version; one of another is analysed again (ForkV2Analysis.VERSION)
            val stored = if (target.isFile) runCatching { ForkV2Analysis.fromJsonText(target.readText()) }.getOrNull() else null
            if (stored != null && stored.analysisVersion == ForkV2Analysis.VERSION) {
                analyses[id] = stored
                kept++
                continue
            }
            val file = audioFor(audio, id)
            if (file == null) {
                noAudio++
                continue
            }
            ev = null
            val r = runCatching { analyzer.forkAnalyseFile(id, file) }
            val t = r.getOrNull()
            if (t == null || !t.isUsable) {
                // why a song failed, for the bench log (a swallowed throw read only as "failed")
                DesktopTrackLog.log("bench: $id failed: " + (r.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" } ?: if (t == null) "no analysis" else "not usable"))
                failed++
                continue
            }
            val a = ForkV2Automix.analysisWith(t, id, ev)
            analyses[id] = a
            val tmp = File(out, "$id.json.tmp")
            tmp.writeText(ForkV2Json.write(a.toJson()))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            done++
        }
        analyzer.forkEvidence = null
        analyzer.release()
        val plans = if (exports != null) plans(exports, analyses, songs.keys, pos, out, version, curator) else 0
        status = "done: ${songs.size} songs, written $done, kept $kept, no audio $noAudio, failed $failed; $plans plans"
    }

    /**
     * The evidence-only pass ([evidence]): each kept analysis with its whole-song evidence measured again from the
     * same decode the analysis makes, its beats, vocals and the rest kept, as the app's backfill does when the
     * evidence's version changes (ForkV2EvidenceStore; the key since ev2). [WORKERS] songs at once: a decode and
     * the evidence are plain functions of the file.
     */
    private fun remeasure(ids: List<String>, audio: File, out: File, analyzer: DesktopTrackAnalyzer) {
        val next = java.util.concurrent.atomic.AtomicInteger()
        val done = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(WORKERS) {
            Thread({
                while (true) {
                    val id = ids.getOrNull(next.getAndIncrement()) ?: break
                    status = "evidence ${done.get() + failed.get() + 1}/${ids.size} (measured ${done.get()}, failed ${failed.get()})"
                    val target = File(out, "$id.json")
                    val r = runCatching {
                        val a = ForkV2Analysis.fromJsonText(target.readText())
                        val file = audioFor(audio, id) ?: error("no audio")
                        val mono = analyzer.forkDecodeFile(file) ?: error("no decode (native analyser ${com.music.bitchord.playback.smart.NativeAnalysisLibrary.available}, cancelled ${analyzer.forkCancelled()})")
                        val b = ForkV2Automix.withEvidence(a, ForkV2EvidenceStore.evidence(id, mono, TrackFeatures.sampleRate))
                        val tmp = File(out, "$id.json.tmp")
                        tmp.writeText(ForkV2Json.write(b.toJson()))
                        if (!tmp.renameTo(target)) {
                            target.delete()
                            tmp.renameTo(target)
                        }
                    }
                    if (r.isSuccess) {
                        done.incrementAndGet()
                    } else {
                        failed.incrementAndGet()
                        DesktopTrackLog.log("bench: $id evidence failed: ${r.exceptionOrNull()?.message}")
                    }
                }
            }, "BitChord-SpotifyBench-$it").apply { isDaemon = true; start() }
        }
        threads.forEach { it.join() }
        DesktopTrackLog.log("bench: evidence measured again for ${done.get()} songs, ${failed.get()} failed")
    }

    private const val WORKERS = 4

    /** The song's audio in [dir]: `<videoId>.<any extension>` (no partial downloads). */
    private fun audioFor(dir: File, id: String): File? =
        dir.listFiles { f -> f.isFile && f.nameWithoutExtension == id && !f.name.endsWith(".part") && !f.name.endsWith(".tmp") }
            ?.maxByOrNull { it.length() }

    @Suppress("UNCHECKED_CAST")
    private fun plans(
        exports: File, analyses: Map<String, ForkV2Analysis>, keyed: Set<String>, pos: Double, out: File, version: Int, curator: Boolean,
    ): Int {
        val files = if (exports.isDirectory) exports.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name } else listOf(exports)
        val rows = ArrayList<Map<String, Any?>>()
        for (f in files) {
            val doc = runCatching { ForkV2Json.parse(f.readText()) as Map<String, Any?> }.getOrNull() ?: continue
            if (doc["format"] != "spotify-mix-export") continue
            for (m in doc["mixes"] as? List<Map<String, Any?>> ?: emptyList()) {
                val fromId = (m["from"] as? Map<String, Any?>)?.get("youtubeVideoId") as? String ?: continue
                val toId = (m["to"] as? Map<String, Any?>)?.get("youtubeVideoId") as? String ?: continue
                if (fromId !in keyed || toId !in keyed || fromId == toId) continue // (a song into itself: skipped, as spotify_bench.mjs does)
                val a = analyses[fromId] ?: continue
                val b = analyses[toId] ?: continue
                status = "planning ${f.name} #${m["index"]}"
                // the mix's songs' speeds (f18, f19: spotify-mixes.md rule 10), as the app plans them
                val settings = ForkV2Automix.settings(0)
                settings.version = version
                settings.speedOut = ForkV2MixOverride.speedOf(m["f18"])
                settings.speedIn = ForkV2MixOverride.speedOf(m["f19"])
                // the curator blend where it fits, asked over Auto's plain blend as the app does (ForkV2Planner.planAuto;
                // spotify_bench.mjs --plan --curator, fe61438)
                val p = runCatching {
                    if (curator && version == 3) ForkV2Planner.planAuto(settings, fromId, toId, a, b, a.durationSec, pos, pos + EARLIEST_LEAD, null, null)
                    else ForkV2Planner.plan(settings, fromId, toId, a, b, a.durationSec, pos, pos + EARLIEST_LEAD, null)
                }.getOrNull() ?: continue
                rows += linkedMapOf(
                    "playlist" to f.name.removeSuffix(".json"),
                    "index" to m["index"],
                    "from" to fromId,
                    "to" to toId,
                    "style" to p.style,
                    "curator" to true,
                    "mixOutTrackTime" to p.mixOutTrackTime,
                    "mixInTrackTime" to p.mixInTrackTime,
                    "durationSec" to p.durationSec,
                    "bars" to p.bars,
                ).apply { if (!p.curator) remove("curator") }
            }
        }
        val doc = linkedMapOf(
            "format" to "spotify-bench-plans", "version" to 1, "app" to "bitchord",
            "analysisVersion" to analyses.values.firstNotNullOfOrNull { it.analysisVersion }, "plans" to rows,
        )
        File(out, "plans.json").writeText(ForkV2Json.write(doc))
        return rows.size
    }

    /** The plan's earliest mix-out after the playhead (the benchmark's 30 s → 34.5 s). */
    private const val EARLIEST_LEAD = 4.5
}
