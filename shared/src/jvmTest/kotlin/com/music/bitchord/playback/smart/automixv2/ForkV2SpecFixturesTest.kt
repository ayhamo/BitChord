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
        }
        for (d in diffs) println("DIFF $d")
        assertTrue(compared >= 10, "expected v2 and v3 for 5 pairs, compared $compared")
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

    private fun planPair(pair: Map<String, Any?>, version: Int): Map<String, Any?> {
        val songs = pair["songs"] as List<Any?>
        val a = songs[0] as Map<String, Any?>
        val b = songs[1] as Map<String, Any?>
        val from = ForkV2Analysis.fromJson(a["analysis"] as Map<String, Any?>)
        val to = ForkV2Analysis.fromJson(b["analysis"] as Map<String, Any?>)
        val pos = max(30.0, from.durationSec - 95)
        val earliest = pos + ForkV2Mix.HAND_LEAD + 0.5

        val s = ForkV2Planner.Settings()
        s.version = version
        // the extension plans the blend without sung spans (its plan() takes none)
        val blend = ForkV2Planner.plan(s, a["id"] as String?, b["id"] as String?, from, to, from.durationSec, pos, earliest, null)

        val sung = ForkV2Planner.Sung()
        sung.out = spans(a["sung"])
        sung.`in` = spans(b["sung"])
        val cur = ForkV2Planner.asOutgoing(from)
        val loop: Any?
        var lp: ForkV2Planner.LoopPlan? = null
        if (ForkV2Planner.gridOk(cur) && ForkV2Planner.gridOk(to)) {
            lp = ForkV2Planner.planLoopMix(version, cur, audio(a), to, audio(b), pos + ForkV2Planner.LOOP_SETUP_SEC, sung)
            loop = loopJson(lp)
        } else {
            loop = one("none", "beat grid too weak")
        }

        val m = LinkedHashMap<String, Any?>()
        m["pos"] = pos
        m["earliest"] = earliest
        // what plays: the loop mix when it found bars (Automix v2: and it scores over the blend)
        val choice = ForkV2Planner.loopOrBlend(version, blend, lp)
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
        m["style"] = p.style
        if (p.shape != null) m["shape"] = p.shape
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
        const val TOLERANCE = 1e-3
    }
}
