package com.music.bitchord.playback.smart.automixv2

import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shared automix fixtures (the YT Music Extension's spec/automix, its master copy): the
 * ported planner on the 5 real pairs against the extension's golden plans, planned the way
 * scripts/automix_fixtures.mjs does it. A port of the phone's SpecFixturesTest.java.
 *
 * The spec is read from where it is mounted, not copied into the repo: RULEBOOK_SPEC, else
 * /spec (the Docker build mounts the extension's spec/ there). A missing spec fails the test.
 */
@Suppress("UNCHECKED_CAST")
class ForkV2SpecFixturesTest {

    @Test
    fun plansMatchTheExtensionsGoldenPlans() {
        val spec = specDir()
        val pairs = pairFiles(spec)
        val diffs = ArrayList<String>()
        var compared = 0
        for (f in pairs) {
            val pair = ForkV2Json.parse(f.readText()) as Map<String, Any?>
            val golden = ForkV2Json.parse(File(spec, "automix/golden/" + f.name).readText()) as Map<String, Any?>
            for (version in 1..3) {
                // the goldens hold only the versions the extension still plays (2 and 3 since Phase 3)
                if (!golden.containsKey("v$version")) continue
                val got = planPair(pair, version)
                compare(f.name + " v" + version, golden["v$version"], got, diffs)
                compared++
            }
            // the rows planned with lyrics (spec fef76a9, versions.md "Where a song is sung" rule 5)
            for ((row, how) in LYRIC_ROWS) {
                if (!golden.containsKey(row)) {
                    diffs.add("${f.name}: no $row row in the golden plans")
                    continue
                }
                compare(f.name + " " + row, golden[row], planPair(pair, 3, how.first, how.second), diffs)
                compared++
            }
        }
        for (d in diffs) println("DIFF $d")
        assertTrue(compared >= 35, "expected v2, v3 and the 5 lyric rows for 5 pairs, compared $compared")
        assertTrue(diffs.isEmpty(), diffs.size.toString() + " differences from the golden plans (printed above): " + (diffs.firstOrNull() ?: ""))
    }

    /**
     * The hard rules (the extension's PLAN.md, automix_fixtures.mjs MUST): Vaina Loca into SI NO ES
     * CONTIGO keeps the loop under Automix v2; Stereo Love into Midnight Sun (11% apart) is never a loop.
     */
    @Test
    fun automixV2KeepsTheHardRules() {
        val pairs = pairFiles(specDir())
        var checked = 0
        for (f in pairs) {
            val n = f.name
            if (!n.startsWith("1-") && !n.startsWith("2-")) continue
            val plays = planPair(ForkV2Json.parse(f.readText()) as Map<String, Any?>, 3)["plays"]
            if (n.startsWith("1-")) assertTrue("loop" == plays, "$n under v3 must play a loop, plays $plays")
            else assertTrue("loop" != plays, "$n under v3 must never play a loop")
            checked++
        }
        assertTrue(checked == 2, "both rule pairs present")
    }

    /**
     * versions.md "Tempo too far apart" and "Where the blend sits" (automix_fixtures.mjs, spec 9df4da6):
     * Stereo Love plays a filter mix, Unethical and Poker Face an echo out; Vaina Loca's blend ends where
     * its outro begins (163.5 s) and starts before Classic's 160.98 s.
     */
    @Test
    fun automixV2PlacesTheBlendAndMixesUnmatchedTempos() {
        val pairs = pairFiles(specDir())
        val want = mapOf("2-" to "filter", "3-" to "echo", "5-" to "echo")
        var checked = 0
        for (f in pairs) {
            val n = f.name
            val blend = planPair(ForkV2Json.parse(f.readText()) as Map<String, Any?>, 3)["blend"] as Map<String, Any?>
            want.entries.firstOrNull { n.startsWith(it.key) }?.let { (_, w) ->
                val got = if (blend["shape"] == "echo") "echo" else blend["style"]
                assertTrue(got == w, "$n under v3 must play ${if (w == "echo") "an echo out" else "a filter mix"}, plays $got")
                checked++
            }
            if (n.startsWith("1-")) {
                val a = blend["anchor"] as Map<String, Any?>?
                val out = blend["mixOutTrackTime"] as Double
                assertTrue(a?.get("kind") == "outro" && abs((a["sec"] as Double) - 163.53) < 0.1 && out < 160.98, "$n: the blend must end on the outro (163.5 s), starting before 160.98 s; got $a from $out")
                checked++
            }
        }
        assertTrue(checked == 4, "all four rule pairs present")
    }

    /**
     * spotify-mixes.md rule 10 (b74ce10): no echo out at a song speed [all]. Unethical's pair is an echo out at x1; with
     * both songs at x1.02 (the played tempos as far apart as the BPMs) the ladder skips the echo out's rung and goes
     * from the filter mix to the hand-over, the reason ending "(no echo out at a playlist speed)".
     */
    @Test
    fun noEchoOutAtAPlaylistSpeed() {
        val f = pairFiles(specDir()).first { it.name.startsWith("3-") }
        val pair = ForkV2Json.parse(f.readText()) as Map<String, Any?>
        val songs = pair["songs"] as List<Any?>
        val from = ForkV2Analysis.fromJson((songs[0] as Map<String, Any?>)["analysis"] as Map<String, Any?>)
        val to = ForkV2Analysis.fromJson((songs[1] as Map<String, Any?>)["analysis"] as Map<String, Any?>)
        val pos = max(30.0, from.durationSec - 95)
        fun plan(speed: Double): ForkV2Planner.Plan {
            val s = ForkV2Planner.Settings()
            s.version = 3
            s.speedOut = speed
            s.speedIn = speed
            return ForkV2Planner.plan(s, "a", "b", from, to, from.durationSec, pos, pos + ForkV2Mix.HAND_LEAD + 0.5, null)
        }
        val plain = plan(1.0)
        assertTrue(plain.style == "blend" && plain.shape == "echo", "${f.name} at x1 is an echo out, plays ${plain.shape ?: plain.style}: ${plain.reason}")
        val p = plan(1.02)
        assertTrue(p.shape != "echo", "${f.name} at x1.02 must not be an echo out: ${p.reason}")
        assertTrue(p.handoff || p.bridge, "the ladder goes from the filter mix to the hand-over, plays ${p.shape ?: p.style}: ${p.reason}")
        assertTrue(p.reason.orEmpty().contains("filter mix (no echo out at a playlist speed) fits"), "the reason: ${p.reason}")
        assertTrue(abs(p.speedOut - 1.02) < 1e-9 && abs(p.speedIn - 1.02) < 1e-9, "the plan keeps the songs' speeds")
    }

    /**
     * versions.md "Where a song is sung" rule 5 and its fixture rules (automix_fixtures.mjs): with
     * lyrics, Unethical and Poker Face don't fall to the hand-over; with only the playing song's,
     * Poker Face -> Let Me Love You is an echo out.
     */
    @Test
    fun automixV2PlansWithLyricsAsTheSpecSays() {
        val pairs = pairFiles(specDir())
        var checked = 0
        for (f in pairs) {
            val n = f.name
            val pair = ForkV2Json.parse(f.readText()) as Map<String, Any?>
            if (n.startsWith("3-") || n.startsWith("5-")) {
                for (how in listOf("lines", "lrc")) {
                    val g = planPair(pair, 3, how, false)
                    val b = g["blend"] as Map<String, Any?>
                    assertTrue(g["plays"] == "loop" || b["handoff"] != true, "$n with $how lyrics plays a hand-over: ${b["reason"]}")
                    checked++
                }
            }
            if (n.startsWith("5-")) {
                for (how in listOf("lines", "lrc")) {
                    val b = planPair(pair, 3, how, true)["blend"] as Map<String, Any?>
                    assertTrue(b["handoff"] != true && b["shape"] == "echo", "$n mixed with $how lyrics must be an echo out, plays ${b["shape"] ?: b["style"]}: ${b["reason"]}")
                    checked++
                }
            }
        }
        assertTrue(checked == 6, "both rule pairs present")
    }

    /** versions.md "The loop mix's baseline" rule 2: Vaina Loca's loop as the user's baseline (d26dc16) plans it, by itself. */
    @Test
    fun vainaLocasLoopIsTheBaseline() {
        val f = pairFiles(specDir()).first { it.name.startsWith("1-") }
        val pair = ForkV2Json.parse(f.readText()) as Map<String, Any?>
        for ((row, how) in listOf("v2" to Triple(2, null, false), "v3" to Triple(3, null, false), "v3lyrics" to Triple(3, "lines", false), "v3lrc" to Triple(3, "lrc", false))) {
            val m = planPair(pair, how.first, how.second, how.third)
            val l = m["loop"] as Map<String, Any?>
            val out = l["out"] as Map<String, Any?>
            val inn = l["in"] as Map<String, Any?>
            fun n(o: Any?) = (o as? Number)?.toDouble()
            val ok = m["plays"] == "loop" && n(l["totalBars"]) == 12.0 && n(l["rampBars"]) == 3.0 && n(out["bars"]) == 4.0 && n(inn["bars"]) == 4.0 &&
                abs(((out["loop"] as List<*>)[0] as Double) - 153.31) < 0.02 && abs(((inn["loop"] as List<*>)[0] as Double) - 9.6) < 0.02
            assertTrue(ok, "$row: plays ${m["plays"]}, ${out["loop"]} / ${inn["loop"]}, ${l["totalBars"]} bars: must be the baseline, 4-bar loops at 153.31 / 9.60 s, ramp 3, 12 bars")
        }
    }

    /**
     * A pair planned as automix_fixtures.mjs planPair does. [lyrics] null: as captured (the blend
     * without lyrics, the loop with the captured spans); "lines": both songs on their lyric line
     * shapes under "Where a song is sung"; "lrc": the same lines cut to plain LRC (a start only).
     * [mixed]: the playing song on its lyrics, the next on its vocal mask; "old" there is the
     * playing song's captured old-rule spans.
     */
    private fun planPair(pair: Map<String, Any?>, version: Int, lyrics: String? = null, mixed: Boolean = false): Map<String, Any?> {
        val songs = pair["songs"] as List<Any?>
        val a = songs[0] as Map<String, Any?>
        val b = songs[1] as Map<String, Any?>
        val from = ForkV2Analysis.fromJson(a["analysis"] as Map<String, Any?>)
        val to = ForkV2Analysis.fromJson(b["analysis"] as Map<String, Any?>)
        val pos = max(30.0, from.durationSec - 95)
        val earliest = pos + ForkV2Mix.HAND_LEAD + 0.5

        fun spansOf(song: Map<String, Any?>, read: (List<ForkV2Sung.LineShape>) -> Array<DoubleArray> = ForkV2Sung::sungSpansFromLines): Array<DoubleArray>? {
            if (lyrics == null) return spans(song["sung"])
            val lines = song["lines"] as? List<Any?> ?: return null
            val shapes = lines.map { o ->
                val l = o as Map<String, Any?>
                val lrc = lyrics == "lrc"
                val wt = if (lrc) null else (l["wordTimes"] as? List<Any?>)?.let { w -> Array(w.size) { ForkV2Json.nums(w[it])!! } }
                ForkV2Sung.LineShape(
                    start = (l["start"] as Number).toDouble(),
                    end = if (lrc) Double.NaN else (l["end"] as? Number)?.toDouble() ?: Double.NaN,
                    words = (l["words"] as Number).toInt(),
                    wordTimes = wt,
                )
            }
            return read(shapes).takeIf { it.isNotEmpty() }
        }
        val sung = ForkV2Planner.Sung()
        if (mixed) {
            sung.out = if (lyrics == "old") spans(a["sung"]) else spansOf(a)
            sung.`in` = null
        } else {
            sung.out = spansOf(a)
            sung.`in` = spansOf(b)
        }
        // the loop mix reads lines as the baseline does (automix.js loopSpansFromLines, 020f8a1)
        val loopSung = if (lyrics != null && lyrics != "old") ForkV2Planner.Sung().also {
            it.out = spansOf(a, ForkV2Sung::loopSpansFromLines)
            it.`in` = if (mixed) null else spansOf(b, ForkV2Sung::loopSpansFromLines)
        } else sung

        val s = ForkV2Planner.Settings()
        s.version = version
        // as captured, the extension plans the blend without sung spans (nothing primed); with
        // lyrics it primes both songs' spans, which its blend planner reads (lyricSpans, carved); the loop reads loopSung
        var blend = ForkV2Planner.plan(s, a["id"] as String?, b["id"] as String?, from, to, from.durationSec, pos, earliest, if (lyrics == null) null else sung)

        val cur = ForkV2Planner.asOutgoing(from)
        val loop: Any?
        var lp: ForkV2Planner.LoopPlan? = null
        if (ForkV2Planner.gridOk(cur) && ForkV2Planner.gridOk(to)) {
            lp = ForkV2Planner.planLoopMix(version, cur, audio(a), to, audio(b), pos + ForkV2Planner.LOOP_SETUP_SEC, loopSung)
            loop = loopJson(lp)
        } else {
            loop = one("none", "beat grid too weak")
        }

        val m = LinkedHashMap<String, Any?>()
        m["pos"] = pos
        m["earliest"] = earliest
        // what plays: the loop mix when it found bars (Automix v2: and it scores over the blend)
        val choice = ForkV2Planner.loopOrBlend(version, blend, lp)
        // Automix v2 with no loop mix: the curator blend (analysis-cases.md "The curator blend"), chosen after
        // the loop-or-blend score, which keeps the usual blend's numbers (automix_fixtures.mjs, fe61438)
        if (version == 3 && !choice.loop) {
            s.curator = true
            blend = ForkV2Planner.plan(s, a["id"] as String?, b["id"] as String?, from, to, from.durationSec, pos, earliest, if (lyrics == null) null else sung)
        }
        m["plays"] = if (choice.loop) "loop" else blend.style
        if (choice.why != null) m["choice"] = choice.why
        m["loop"] = loop
        m["blend"] = blendJson(blend)
        return m
    }

    /** The decoded song as the extension's loop search reads it (automix_fixtures.mjs bufferOf, automix.js rms). */
    private fun audio(song: Map<String, Any?>): ForkV2Planner.Audio {
        val loud = ForkV2Json.nums(song["loudness20Hz"])!!
        val decoded = ForkV2Json.num(song, "decodedSec")
        val rate = 320
        val data = FloatArray(loud.size * 16)
        for (w in loud.indices) data.fill(loud[w].toFloat(), w * 16, w * 16 + 16)
        return object : ForkV2Planner.Audio {
            override fun duration(): Double = decoded

            override fun rms(from: Double, to: Double): Double {
                val s = max(0.0, floor(from * rate)).toInt()
                val e = min(data.size.toDouble(), floor(to * rate)).toInt()
                var ss = 0.0
                var n = 0
                var i = s
                while (i < e) {
                    ss += data[i] * data[i] // float * float, as the Java does
                    n++
                    i += 16
                }
                return if (n > 0) sqrt(ss / n) else 0.0
            }
        }
    }

    private fun spans(o: Any?): Array<DoubleArray>? {
        if (o !is List<*>) return null
        return Array(o.size) { ForkV2Json.nums(o[it])!! }
    }

    private fun loopJson(lp: ForkV2Planner.LoopPlan?): Any? {
        if (lp == null) return null
        if (lp.none != null) return one("none", lp.none)
        val m = LinkedHashMap<String, Any?>()
        m["out"] = sideJson(lp.out!!)
        m["in"] = sideJson(lp.`in`!!)
        m["fold"] = lp.fold
        m["enterBars"] = lp.enterBars.toDouble()
        m["rampBars"] = lp.rampBars.toDouble()
        m["exitBars"] = lp.exitBars
        m["totalBars"] = lp.totalBars.toDouble()
        m["curves"] = curvesJson(lp.curves)
        return m
    }

    private fun sideJson(s: ForkV2Planner.LoopSide): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["key"] = s.key
        m["loop"] = listOf(s.loopStart, s.loopEnd)
        m["bars"] = s.bars.toDouble()
        m["beatSec"] = s.beatSec
        m["bpm"] = s.bpm
        m["vocal"] = s.vocal
        m["checkedBy"] = s.checkedBy
        return m
    }

    /** The plan as the extension's planner returns it: a hand-over or bridge has only its own fields. */
    private fun blendJson(p: ForkV2Planner.Plan): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        if (p.handoff || p.bridge) {
            m["style"] = p.style
            m["fallback"] = p.fallback
            m[if (p.handoff) "handoff" else "bridge"] = true
            m["reason"] = p.reason
            m["mixOutTrackTime"] = p.mixOutTrackTime
            m["durationSec"] = p.durationSec
            m["mixInTrackTime"] = p.mixInTrackTime
            m["rate"] = p.rate
            m["bars"] = p.bars.toDouble()
            m["swapDb"] = p.swapDb
            m["barSec"] = p.barSec
            m["bpm"] = null
            m["inBpm"] = null
            if (p.bridge) m["trim"] = trimJson(p.trim)
            m["curves"] = curvesJson(p.curves)
            return m
        }
        if ("filter" == p.style) {
            // automix.js filterMix's own fields
            m["style"] = p.style
            m["fallback"] = p.fallback
            m["reason"] = p.reason
            m["v2"] = p.v2
            m["clash"] = p.clash
            m["rerouted"] = p.rerouted
            p.anchor?.let { m["anchor"] = anchorJson(it) }
            m["mixOutTrackTime"] = p.mixOutTrackTime
            m["durationSec"] = p.durationSec
            m["mixInTrackTime"] = p.mixInTrackTime
            m["rate"] = p.rate
            m["bars"] = p.bars.toDouble()
            m["swapDb"] = p.swapDb
            m["barSec"] = p.barSec
            m["bpm"] = p.bpm
            m["inBpm"] = p.inBpm
            m["curves"] = curvesJson(p.curves)
            return m
        }
        m["style"] = p.style
        if (p.shape != null) m["shape"] = p.shape
        p.anchor?.let { m["anchor"] = anchorJson(it) }
        if (p.curator) m["curator"] = true
        p.echo?.let { m["echo"] = linkedMapOf("beats" to it.beats.toDouble(), "delaySec" to it.delaySec, "feedback" to it.feedback, "cutSec" to it.cutSec) }
        if ("muffle" == p.shape) m["muffleLead"] = p.muffleLead
        m["fallback"] = p.fallback
        m["reason"] = p.reason
        if (p.v3) {
            m["subBassContrast"] = p.subBassContrast
            m["swapAt"] = p.swapAt
            m["swapMoved"] = p.swapMoved
            val h = p.harmonic
            if (h == null) {
                m["harmonic"] = null
            } else {
                val hm = LinkedHashMap<String, Any?>()
                hm["score"] = h.score
                hm["confidence"] = h.confidence
                hm["keyScore"] = h.keyScore
                hm["chromaScore"] = h.chromaScore
                hm["severeClash"] = h.severeClash
                hm["short"] = h.shortBlend
                m["harmonic"] = hm
            }
            m["trim"] = trimJson(p.trim)
        }
        // every blend carries its glide (null: none), Classic's too
        val g = p.glide
        if (g == null) {
            m["glide"] = null
        } else {
            val gm = LinkedHashMap<String, Any?>()
            gm["q"] = g.q
            gm["beats"] = g.beats.toDouble()
            gm["beatOutSec"] = g.beatOutSec
            m["glide"] = gm
        }
        m["v2"] = p.v2
        m["clash"] = p.clash
        m["rerouted"] = p.rerouted
        m["vocalsBy"] = p.vocalsBy
        m["keyClash"] = p.keyClash
        m["mixOutTrackTime"] = p.mixOutTrackTime
        m["durationSec"] = p.durationSec
        m["mixInTrackTime"] = p.mixInTrackTime
        m["rate"] = p.rate
        m["outRate"] = p.outRate
        m["rampBeats"] = p.rampBeats.toDouble()
        m["rampSongSec"] = p.rampSongSec
        m["beatSec"] = p.beatSec
        m["bars"] = p.bars.toDouble()
        m["swapDb"] = p.swapDb
        m["barSec"] = p.barSec
        m["bpm"] = p.bpm
        m["outBpm"] = p.outBpm
        m["inBpm"] = p.inBpm
        m["curves"] = curvesJson(p.curves)
        return m
    }

    private fun anchorJson(a: ForkV2Planner.Anchor): Map<String, Any?> = linkedMapOf("kind" to a.kind, "sec" to a.sec, "score" to a.score)

    private fun trimJson(t: ForkV2Planner.Trim?): Map<String, Any?>? {
        if (t == null) return null
        val m = LinkedHashMap<String, Any?>()
        m["diffDb"] = t.diffDb
        m["outDb"] = t.outDb
        m["inDb"] = t.inDb
        return m
    }

    private fun curvesJson(c: ForkV2Curves?): Map<String, Any?>? {
        if (c == null) return null
        val m = LinkedHashMap<String, Any?>()
        m["durationSec"] = c.durationSec
        m["out"] = deckJson(c.out)
        m["in"] = deckJson(c.`in`)
        return m
    }

    private fun deckJson(deck: Map<String, Array<DoubleArray>>): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for ((k, pts) in deck) m[k] = pts.map { listOf(it[0], it[1]) }
        return m
    }

    private fun compare(path: String, want: Any?, got0: Any?, diffs: MutableList<String>) {
        val got = if (got0 is Double && got0.isNaN()) null else got0
        if (want is Number && got is Number) {
            val w = want.toDouble()
            val g = got.toDouble()
            if (abs(w - g) > TOLERANCE) diffs.add("$path: want $w, got $g")
        } else if (want is Map<*, *> && got is Map<*, *>) {
            val w = want as Map<String, Any?>
            val g = got as Map<String, Any?>
            for (k in w.keys) compare("$path.$k", w[k], g[k], diffs)
            for (k in g.keys) if (!w.containsKey(k)) diffs.add("$path.$k: not in the golden plan")
        } else if (want is List<*> && got is List<*>) {
            if (want.size != got.size) diffs.add("$path: want $want, got $got")
            else for (i in want.indices) compare("$path[$i]", want[i], got[i], diffs)
        } else if (if (want == null) got != null else want != got) {
            diffs.add("$path: want $want, got $got")
        }
    }

    private fun one(k: String, v: Any?): Map<String, Any?> = linkedMapOf(k to v)

    private fun pairFiles(spec: File): List<File> {
        val pairs = File(spec, "automix/pairs").listFiles { _, n -> n.endsWith(".json") }
        if (pairs == null || pairs.isEmpty()) fail("no pair files in $spec/automix/pairs")
        return pairs.sortedBy { it.name }
    }

    /** The extension's spec/: RULEBOOK_SPEC, else /spec. Missing is a failure, not a skip. */
    private fun specDir(): File {
        val dir = File(System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec")
        if (!File(dir, "automix").isDirectory) {
            org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true")
            fail("the extension's spec is not at $dir (set RULEBOOK_SPEC, or mount it at /spec): no automix/ there")
        }
        return dir
    }

    private companion object {
        /** The golden rows planned with lyrics (automix_fixtures.mjs): row -> (lyrics, mixed). */
        val LYRIC_ROWS = linkedMapOf(
            "v3lyrics" to ("lines" to false),
            "v3lrc" to ("lrc" to false),
            "v3mixedOld" to ("old" to true),
            "v3mixed" to ("lines" to true),
            "v3mixedLrc" to ("lrc" to true),
        )
        const val TOLERANCE = 1e-3
    }
}
