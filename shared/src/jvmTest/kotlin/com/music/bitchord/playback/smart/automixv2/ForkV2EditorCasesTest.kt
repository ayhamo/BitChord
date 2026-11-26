package com.music.bitchord.playback.smart.automixv2

import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The transition editor (the extension's spec/automix/editor.md), checked as the phone patches'
 * EditorCasesTest.java checks it, ported:
 * - editor-cases.json, all four of its groups: `curves` (each preset and lane shape as curves,
 *   presetCurves), `windows` (a saved position read back on a grid, resolveWindow), `plans` (an
 *   override planned on two steady songs: the window played, glide, kick lock and the warnings word
 *   for word) and `draw` (§10's drawn curves: pointCurves, presetCurves with drawn lanes,
 *   normalizeOverride on drawings, shapeDrawing, a drawn override planned);
 * - override.schema.json: the port's value lists are the schema's, and what it writes uses only the
 *   schema's fields;
 * - the override itself (§1): migration of the old picks, nothing-but-Auto, the JSON round trip, the
 *   10 ms nudges and snapping;
 * - how the planner plays an override (§4, §6, §9), with the engine's deck lanes;
 * - the store's cap and lock (§1) at the spec's numbers ([ForkV2OverrideStore]).
 *
 * The spec is read where it is mounted, as [ForkV2SpecFixturesTest] reads it: RULEBOOK_SPEC, else
 * /spec. Missing, it fails locally and is skipped on CI.
 *
 * Not checked here, because the port has no such function yet: the editor's `windowLimits` and
 * `editorGrid` (automix.js; editor.md §6's drag limits). editor-cases.json has no group for them;
 * the planner's own clamp of a saved window is checked through `plans`.
 */
@Suppress("UNCHECKED_CAST")
class ForkV2EditorCasesTest {

    @AfterTest
    fun clearStore() {
        ForkV2OverrideStore.clear()
    }

    // ── editor-cases.json ──

    @Test
    fun presetAndLaneCurvesMatchTheCases() {
        val cases = cases()["curves"] as List<Any?>
        assertEquals(27, cases.size)
        val diffs = ArrayList<String>()
        for (o in cases) {
            val c = o as Map<String, Any?>
            val ov = ForkV2MixOverride.fromJson(c["override"])
            val d = (c["D"] as Number).toDouble()
            val beat = (c["beatSec"] as? Number)?.toDouble() ?: Double.NaN
            val got = ForkV2MixOverride.presetCurves(ov, d, beat)
            val want = c["expect"]
            if (want == null) {
                if (got != null) diffs.add("${c["name"]}: expected refused, got ${ForkV2Json.write(asJson(got))}")
                continue
            }
            if (got == null) {
                diffs.add("${c["name"]}: refused")
                continue
            }
            if (!same(want, asJson(got))) diffs.add("${c["name"]}:\n  got  ${ForkV2Json.write(asJson(got))}\n  want ${ForkV2Json.write(want)}")
        }
        assertTrue(diffs.isEmpty(), "${diffs.size} of ${cases.size} differ:\n" + diffs.joinToString("\n"))
    }

    @Test
    fun savedWindowPositionsReadBackAsTheCasesSay() {
        val cases = cases()["windows"] as List<Any?>
        assertEquals(8, cases.size)
        val diffs = ArrayList<String>()
        for (o in cases) {
            val c = o as Map<String, Any?>
            val p = c["pos"] as Map<String, Any?>
            val pos = ForkV2MixOverride.Position(
                (p["bar"] as Number).toInt(),
                (p["beat"] as? Number)?.toInt() ?: 0,
                (p["nudgeMs"] as? Number)?.toInt() ?: 0,
                (p["sec"] as Number).toDouble(),
            )
            val g = c["grid"] as Map<String, Any?>?
            val r = if (g == null) {
                ForkV2MixOverride.resolveWindow(pos, Double.NaN, Double.NaN)
            } else {
                ForkV2MixOverride.resolveWindow(pos, (g["downbeatSec"] as Number).toDouble(), (g["beatSec"] as Number).toDouble())
            }!!
            val got = LinkedHashMap<String, Any?>()
            got["sec"] = r.sec
            got["moved"] = r.moved
            got["snapped"] = r.snapped
            val want = c["expect"] as Map<String, Any?>
            if (want.containsKey("bar")) got["bar"] = r.bar
            if (!same(want, got)) diffs.add("${c["name"]}: got ${ForkV2Json.write(got)}, want ${ForkV2Json.write(want)}")
        }
        assertTrue(diffs.isEmpty(), diffs.joinToString("\n"))
    }

    /** The plans cases, warnings word for word (the expected list is compared element by element). */
    @Test
    fun overridesPlayTheWindowsTheCasesSay() {
        val cases = cases()["plans"] as List<Any?>
        assertEquals(11, cases.size)
        val diffs = ArrayList<String>()
        for (o in cases) {
            val c = o as Map<String, Any?>
            val engine = (c["engine"] as Number).toInt()
            val s = ForkV2Planner.Settings()
            s.version = engine
            // the override's songs' speeds (spotify-mixes.md rule 10), as the app passes them
            val ov = ForkV2MixOverride.fromJson(c["override"])
            ov?.let { s.speedOut = it.speedOut; s.speedIn = it.speedIn }
            val from = steady("a", c["out"] as Map<String, Any?>)
            val to = steady("b", c["in"] as Map<String, Any?>)
            val pos = (c["pos"] as Number).toDouble()
            val p = ForkV2Planner.plan(s, "a", "b", from, to, from.durationSec, pos, pos + 4.5, null, ov)
            val got = LinkedHashMap<String, Any?>()
            got["style"] = p.style
            got["bars"] = p.bars.toDouble()
            got["durationSec"] = p.durationSec
            got["mixOutTrackTime"] = p.mixOutTrackTime
            got["mixInTrackTime"] = p.mixInTrackTime
            got["glide"] = p.glide != null
            got["kickLock"] = engine == 3 && ForkV2Planner.kickLockAllowed(p)
            got["warnings"] = ArrayList<Any?>(p.warnings.orEmpty())
            if ((c["expect"] as Map<String, Any?>).containsKey("speedOut")) {
                got["outRate"] = p.outRate
                got["rate"] = p.rate
                got["speedOut"] = p.speedOut
                got["speedIn"] = p.speedIn
            }
            val want = c["expect"]
            if (!same(want, got)) diffs.add("${c["name"]}:\n  got  ${ForkV2Json.write(got)}\n  want ${ForkV2Json.write(want)}")
        }
        assertTrue(diffs.isEmpty(), "${diffs.size} of ${cases.size} differ:\n" + diffs.joinToString("\n"))
    }

    /**
     * editor.md §10, group `draw` (editor_cases.mjs): pointCurves, presetCurves with drawn and named
     * lanes, normalizeOverride on drawings (count: the points kept per deck), shapeDrawing, and a
     * drawn override planned on two steady songs (engine 2, the runner's default).
     */
    @Test
    fun drawnCurvesMatchTheCases() {
        val cases = (cases()["draw"] as List<Any?>).map { it as Map<String, Any?> }.filter { it["name"] != null }
        assertEquals(17, cases.size)
        val diffs = ArrayList<String>()
        for (c in cases) {
            val got: Any? = when (c["fn"]) {
                "pointCurves" -> {
                    val g = (c["glide"] as Map<String, Any?>?)?.let {
                        ForkV2Planner.Glide((it["q"] as Number).toDouble(), (it["beats"] as Number).toInt(), (it["beatOutSec"] as Number).toDouble())
                    }
                    val d = (c["D"] as? Number)?.toDouble() ?: ForkV2Planner.glideSteps(g!!.q, g.beats, g.beatOutSec).total
                    val lanes = c["lanes"] as Map<String, Any?>
                    val ds = lanes.mapNotNull { (k, v) -> ForkV2Drawing.normalize(k, v) as? ForkV2Drawing }
                    val pc = ForkV2Drawing.pointCurves(ds, d, (c["beatSec"] as Number).toDouble(), g)
                    mapOf("out" to side(pc.out), "in" to side(pc.`in`))
                }
                "presetCurves" -> {
                    val beat = (c["beatSec"] as Number).toDouble()
                    ForkV2MixOverride.presetCurves(ForkV2MixOverride.fromJson(c["override"]), (c["D"] as Number).toDouble(), beat, beat, null)?.let { asJson(it) }
                }
                "normalizeOverride" -> {
                    val j = ForkV2MixOverride.fromJson(c["override"])?.toJson()
                    if (j != null && c["count"] == true) {
                        val lanes = (j["lanes"] as Map<String, Any?>).mapValues { (_, v) ->
                            if (v is Map<*, *>) mapOf("out" to count(v["out"]), "in" to count(v["in"])) else v
                        }
                        j + ("lanes" to lanes)
                    } else j
                }
                "shapeDrawing" -> {
                    val d = ForkV2Drawing.shapeDrawing(
                        c["lane"] as String, c["shape"] as String, (c["D"] as Number).toDouble(), (c["beatSec"] as Number).toDouble(), null, c["unit"] as String,
                    )
                    if (d is ForkV2Drawing) d.toJson() else d
                }
                "plan" -> {
                    val s = ForkV2Planner.Settings()
                    s.version = 2
                    val from = steady("a", c["out"] as Map<String, Any?>)
                    val to = steady("b", c["in"] as Map<String, Any?>)
                    val pos = (c["pos"] as Number).toDouble()
                    val p = ForkV2Planner.plan(s, "a", "b", from, to, from.durationSec, pos, pos + 4.5, null, ForkV2MixOverride.fromJson(c["override"]))
                    linkedMapOf(
                        "style" to p.style, "bars" to p.bars, "durationSec" to p.durationSec, "outRate" to p.outRate, "rate" to p.rate,
                        "gainOut" to p.curves!!.out["gain"]!!.map { listOf(it[0], it[1]) },
                        "gainIn" to p.curves!!.`in`["gain"]!!.map { listOf(it[0], it[1]) },
                    )
                }
                else -> fail("unknown fn ${c["fn"]}")
            }
            val want = c["expect"]
            val gotJson = ForkV2Json.parse(ForkV2Json.write(got)) // ints and doubles alike
            if (!same(want, gotJson)) diffs.add("${c["name"]}:\n  got  ${ForkV2Json.write(got)}\n  want ${ForkV2Json.write(want)}")
        }
        assertTrue(diffs.isEmpty(), "${diffs.size} of ${cases.size} differ:\n" + diffs.joinToString("\n"))
    }

    /** editor_cases.mjs counted: a volume deck's points, an EQ or effect deck's curves. */
    private fun count(v: Any?): Int = when (v) {
        is List<*> -> v.size
        is Map<*, *> -> v.size
        else -> 0
    }

    @Test
    fun aDrawingSurvivesItsJsonAndPlaysInThePlanner() {
        val text = """{"v":1,"preset":"custom","lanes":{"volume":{"unit":"beat","out":[[0,1],[8,1],[8,0],[8.5,0],[8.5,1],[16,0]],"in":[[0,0],[16,1]]},"eq":"centerBassSwap","effect":{"unit":"beat","out":{"hp":[[0,20],[16,1000]]},"in":{}}},"bars":4}"""
        val o = ForkV2MixOverride.fromJson(json(text))!!
        assertEquals(text, ForkV2Json.write(o.toJson()))
        assertEquals(ForkV2MixOverride.DRAW, o.volume)
        assertNotNull(o.drawing("volume"))
        // a copy keeps its drawings; Smooth picked afterwards drops them
        assertEquals(text, ForkV2Json.write(o.copy().toJson()))
        val smooth = ForkV2EditorGeometry.edited(o, true) { preset = "smooth" }!!
        assertTrue(smooth.drawings.isEmpty())
        assertFalse("lanes" in smooth.toJson())
        // the planner: 4 bars at 120 BPM, the gate at beat 8 = 4 s
        val p = plan(v3(), ForkV2PlannerTest.song("a", 120.0, 200.0, 0.9), ForkV2PlannerTest.song("b", 120.0, 180.0, 0.9), o)
        assertEquals("custom", p.preset)
        val g = p.curves!!.out["gain"]!!
        assertEquals(4.0, g[1][0], 1e-6)
        assertEquals(4.008, g[2][0], 1e-6)
        assertEquals(0.0, g[2][1], 1e-9)
        assertNotNull(p.curves!!.out["bassDb"], "the named EQ lane stays")
        assertNotNull(p.curves!!.out["highpassHz"])
        assertNull(p.curves!!.out["lowpassHz"])
    }

    // ── override.schema.json ──

    @Test
    fun thePortsValuesAreTheSchemas() {
        val schema = ForkV2Json.parse(File(specDir(), "automix/override.schema.json").readText()) as Map<String, Any?>
        val props = schema["properties"] as Map<String, Any?>
        // A lane is its named shapes or, since the spec's drawn lanes (editor.md §10), oneOf those and a drawing.
        fun enumOf(m: Any?): List<Any?> = (m as Map<String, Any?>)["enum"] as List<Any?>?
            ?: (m["oneOf"] as List<Any?>).firstNotNullOf { (it as Map<String, Any?>)["enum"] as List<Any?>? }
        assertEquals(enumOf(props["preset"]), ForkV2MixOverride.PRESETS.toList())
        assertEquals(enumOf(props["beatMatch"]), ForkV2MixOverride.BEAT_MATCH.toList())
        assertEquals(enumOf(props["bars"]).map { (it as Number).toInt() }, ForkV2MixOverride.BARS.toList())
        assertEquals(enumOf(props["family"]), listOf("blend", "filter"))
        val lanes = (props["lanes"] as Map<String, Any?>)["properties"] as Map<String, Any?>
        // each lane oneOf: a shape name, or a drawing {unit, out, in} (editor.md §10)
        fun oneOf(m: Any?): List<Map<String, Any?>> = ((m as Map<String, Any?>)["oneOf"] as List<Any?>).map { it as Map<String, Any?> }
        val defs = schema["\$defs"] as Map<String, Any?>
        for ((lane, names) in listOf("volume" to ForkV2MixOverride.VOLUMES, "eq" to ForkV2MixOverride.EQS, "effect" to ForkV2MixOverride.EFFECTS)) {
            val alts = oneOf(lanes[lane])
            assertEquals(2, alts.size, lane)
            assertEquals(enumOf(alts[0]), names.toList())
            assertEquals(listOf("unit", "out", "in"), alts[1]["required"], lane)
            assertFalse(ForkV2MixOverride.DRAW in enumOf(alts[0]), "Draw is never saved as a name")
        }
        assertEquals(listOf("beat", "sec"), enumOf(defs["unit"]))
        assertEquals(ForkV2Drawing.MAX_POINTS, ((defs["points"] as Map<String, Any?>)["maxItems"] as Number).toInt())
        // the stored deck names and ranges are the schema's
        fun props(def: String): Map<String, Any?> = (defs[def] as Map<String, Any?>)["properties"] as Map<String, Any?>
        assertEquals(listOf("bass", "mid", "high"), props("eqDeck").keys.toList())
        assertEquals(listOf("lp", "hp", "noise"), props("effectDeck").keys.toList())
        assertEquals(listOf("echo", "reverb", "delay"), props("fxDeck").keys.toList())
        for ((def, key) in listOf("gainCurve" to "gain", "dbCurve" to "bassDb", "hzCurve" to "lowpassHz")) {
            val range = (((defs[def] as Map<String, Any?>)["items"] as Map<String, Any?>)["prefixItems"] as List<Any?>)[1] as Map<String, Any?>
            val c = ForkV2Drawing.CURVES[key]!!
            assertEquals(c.lo, (range["minimum"] as Number).toDouble(), def)
            assertEquals(c.hi, (range["maximum"] as Number).toDouble(), def)
        }
        assertEquals(ForkV2OverrideStore.ENGINE, enumOf(props["engine"]).map { (it as Number).toInt() }.last(), "Automix v2 is the extension's 3")

        // everything the port writes is a schema field, with a position's own fields
        val o = full()
        val json = ForkV2MixOverride.normalize(o)!!.toJson()
        assertTrue(props.keys.containsAll(json.keys), "not in the schema: ${json.keys - props.keys}")
        val position = ((schema["\$defs"] as Map<String, Any?>)["position"] as Map<String, Any?>)["properties"] as Map<String, Any?>
        val window = json["window"] as Map<String, Any?>
        for (side in window.values) assertTrue(position.keys.containsAll((side as Map<String, Any?>).keys))
        assertEquals(1, json["v"])
        // lanes only with custom (the schema's allOf)
        val smooth = ForkV2MixOverride()
        smooth.preset = "smooth"
        smooth.volume = "overlap"
        assertFalse("lanes" in ForkV2MixOverride.normalize(smooth)!!.toJson())
    }

    // ── the override itself (editor.md §1) ──

    @Test
    fun oldPicksMigrateAsTheyAreRead() {
        val loop = ForkV2MixOverride.fromJson(json("""{"style":"loop","bars":0}"""))!!
        assertEquals("loop", loop.preset)
        val blend = ForkV2MixOverride.fromJson(json("""{"style":"blend","bars":8}"""))!!
        assertNull(blend.preset)
        assertEquals("blend", blend.family)
        assertEquals(8, blend.bars)
        assertEquals("filter", ForkV2MixOverride.fromJson(json("""{"style":"filter","bars":0}"""))!!.family)
        val fade = ForkV2MixOverride.fromJson(json("""{"style":"crossfade","bars":0}"""))!!
        assertEquals("off", fade.beatMatch)
        assertNull(fade.family)
        // Auto and Auto: no override at all
        assertNull(ForkV2MixOverride.fromJson(json("""{"style":null,"bars":0}""")))
        // a loop keeps its own length: the bars of an old loop pick are dropped
        assertEquals(0, ForkV2MixOverride.fromJson(json("""{"style":"loop","bars":8}"""))!!.bars)
    }

    @Test
    fun theStoreMovesTheForksOldLinesInAsTheTableSays() {
        val moved = ForkV2OverrideStore.migrateLegacyPicks("a->b\tloop\t16\nc->d\tblend\t8\ne->f\tfilter\t32\ng->h\tcrossfade\t8\ni->j\t\t4")
        assertEquals(5, moved)
        fun key(k: String) = ForkV2Json.write(ForkV2OverrideStore.overrides.value[k]!!.toJson().filterKeys { it != "savedAt" })
        assertEquals("""{"v":1,"preset":"loop","engine":3}""", key("a>b"))
        assertEquals("""{"v":1,"bars":8,"family":"blend","engine":3}""", key("c>d"))
        assertEquals("""{"v":1,"bars":32,"family":"filter","engine":3}""", key("e>f")) // a picked filter length is kept (editor.md §6, 2026-11-26)
        assertEquals("""{"v":1,"beatMatch":"off","engine":3}""", key("g>h"))
        assertEquals("""{"v":1,"bars":4,"engine":3}""", key("i>j"))
    }

    @Test
    fun nothingButAutoIsNoOverride() {
        assertNull(ForkV2MixOverride.fromJson(json("""{"v":1,"preset":"auto","beatMatch":"auto"}""")))
        // a lock alone keeps nothing: Auto is the reset
        assertNull(ForkV2MixOverride.fromJson(json("""{"v":1,"lock":true}""")))
        var o = ForkV2MixOverride()
        o.preset = "smooth"
        o.family = "blend"
        o.bars = 3 // not a length the editor offers
        val n = ForkV2MixOverride.normalize(o)!!
        assertEquals("smooth", n.preset)
        assertNull(n.family, "picking a preset drops a migrated family")
        assertEquals(0, n.bars)
        // custom needs its lanes
        o = ForkV2MixOverride()
        o.preset = "custom"
        assertNull(ForkV2MixOverride.normalize(o))
        // and the store deletes such a pair rather than keep it
        ForkV2OverrideStore.put("a", "b", full())
        assertNull(ForkV2OverrideStore.put("a", "b", ForkV2MixOverride()))
        assertNull(ForkV2OverrideStore.get("a", "b"))
    }

    @Test
    fun anOverrideSurvivesItsJson() {
        val text = ForkV2Json.write(ForkV2MixOverride.normalize(full())!!.toJson())
        val back = ForkV2MixOverride.fromJson(ForkV2Json.parse(text))!!
        assertEquals(text, ForkV2Json.write(back.toJson()))
        assertEquals(40, back.out!!.nudgeMs) // 10 ms steps
        assertEquals(192.457, back.out!!.sec, 1e-9)
        assertTrue(back.lock)
        assertTrue(
            text.startsWith("""{"v":1,"preset":"custom","lanes":{"volume":"overlap","eq":"endBassSwap","effect":"hpIn"},"bars":16"""),
            text,
        )
    }

    @Test
    fun nudgesAreTenMsStepsWithinTwoSeconds() {
        val o = ForkV2MixOverride.fromJson(json("""{"window":{"out":{"bar":4,"nudgeMs":2034,"sec":9.5},"in":{"bar":1,"nudgeMs":-2600,"sec":2.5}}}"""))!!
        assertEquals(2000, o.out!!.nudgeMs)
        assertEquals(-2000, o.`in`!!.nudgeMs)
        assertEquals(10, ForkV2MixOverride.fromJson(json("""{"window":{"out":{"bar":4,"nudgeMs":14,"sec":9.5}}}"""))!!.out!!.nudgeMs)
    }

    @Test
    fun snappingFindsTheNearestDownbeatOrBeat() {
        var p = ForkV2MixOverride.snap(33.4, 1.25, 0.5, false)
        assertEquals(16, p.bar)
        assertEquals(0, p.beat)
        assertEquals(33.25, p.sec, 1e-9)
        p = ForkV2MixOverride.snap(33.8, 1.25, 0.5, true)
        assertEquals(16, p.bar)
        assertEquals(1, p.beat)
        assertEquals(33.75, p.sec, 1e-9)
        p = ForkV2MixOverride.snap(0.2, 1.25, 0.5, true)
        assertEquals(-1, p.bar)
        assertEquals(2, p.beat)
        assertEquals(0.25, p.sec, 1e-9)
        assertEquals(p.sec, ForkV2MixOverride.resolveWindow(p, 1.25, 0.5)!!.sec, 1e-9)
        // beat match off: nothing snaps, the 10 ms steps carry the rest
        val off = ForkV2MixOverride.positionOf(33.437, 1.25, 0.5, false, false)
        assertEquals(33.437, ForkV2MixOverride.resolveWindow(off, 1.25, 0.5)!!.sec, 0.005)
    }

    // ── the store's cap and lock (editor.md §1) ──

    @Test
    fun threeHundredUnlockedOldestSavedFirstOutAndLocksExempt() {
        var t = 10_000L
        ForkV2OverrideStore.clock = { t++ }
        for (i in 0 until 5) {
            ForkV2OverrideStore.put("L$i", "x", full())
        }
        for (i in 0 until ForkV2MixOverride.MAX_UNLOCKED + 3) ForkV2OverrideStore.put("u$i", "x", bars(8))
        val m = ForkV2OverrideStore.overrides.value
        assertEquals(ForkV2MixOverride.MAX_UNLOCKED + 5, m.size, "locked ones don't count")
        for (i in 0 until 5) assertNotNull(m["L$i>x"], "never evicted")
        for (i in 0 until 3) assertNull(m["u$i>x"], "the oldest saved went")
        assertNotNull(m["u3>x"])
    }

    @Test
    fun aLockPastThreeHundredIsRefusedWithTheMessage() {
        for (i in 0 until ForkV2MixOverride.MAX_LOCKED) assertNull(ForkV2OverrideStore.put("L$i", "x", full()))
        assertEquals("300 pairs are locked already: unlock one first", ForkV2OverrideStore.put("one", "more", full()))
        assertNull(ForkV2OverrideStore.get("one", "more"), "refused: nothing saved")
        ForkV2OverrideStore.put("one", "more", bars(4))
        assertEquals(ForkV2OverrideStore.LOCK_CAP_REFUSAL, ForkV2OverrideStore.setLocked("one", "more", true))
        assertFalse(ForkV2OverrideStore.get("one", "more")!!.lock)
        assertNull(ForkV2OverrideStore.put("L7", "x", full()), "a locked pair saved again is no new lock")
        assertNull(ForkV2OverrideStore.setLocked("L7", "x", false))
        assertNull(ForkV2OverrideStore.setLocked("one", "more", true), "room again")
    }

    // ── the planner with an override (editor.md §4, §6, §9) ──

    @Test
    fun aPresetReplacesTheCurvesAndKeepsTheTempo() {
        val s = v3()
        val a = ForkV2PlannerTest.song("a", 120.0, 200.0, 0.9)
        val b = ForkV2PlannerTest.song("b", 123.0, 180.0, 0.9)
        val auto = plan(s, a, b, null)
        val wave = plan(s, a, b, ov("""{"preset":"wave"}"""))
        assertEquals("blend", wave.style)
        assertEquals("wave", wave.preset)
        assertFalse(wave.fallback)
        val want = ForkV2MixOverride.presetCurves(ov("""{"preset":"wave"}"""), wave.durationSec, wave.beatSec)!!
        assertEquals(want.out["gain"]!!.contentDeepToString(), wave.curves!!.out["gain"]!!.contentDeepToString())
        assertEquals(want.`in`["lowpassHz"]!!.contentDeepToString(), wave.curves!!.`in`["lowpassHz"]!!.contentDeepToString())
        // the planner's tempo stays: the next song's speed-up (or glide) as Auto plays it
        assertEquals(auto.curves!!.`in`["rate"]?.contentDeepToString(), wave.curves!!.`in`["rate"]?.contentDeepToString())
        assertEquals(auto.curves!!.out["rate"]?.contentDeepToString(), wave.curves!!.out["rate"]?.contentDeepToString())
        assertEquals(auto.rate, wave.rate, 0.0)
        assertTrue(wave.toString().contains("[editor: wave"), wave.toString())
    }

    @Test
    fun slamPlaysThePairsOwnLengthWithItsCutOnTheCentre() {
        // editor.md §2 (2026-11-25): no preset sets a length, Slam's 2 bars included
        val p = plan(v3(), ForkV2PlannerTest.song("a", 120.0, 200.0, 0.9), ForkV2PlannerTest.song("b", 120.0, 180.0, 0.9), ov("""{"preset":"slam"}"""))
        assertEquals(plan(v3(), ForkV2PlannerTest.song("a", 120.0, 200.0, 0.9), ForkV2PlannerTest.song("b", 120.0, 180.0, 0.9), null).bars, p.bars)
        val g = p.curves!!.out["gain"]!!
        assertEquals(p.durationSec / 2, g[1][0], 1e-6)
        assertEquals(p.durationSec / 2 + ForkV2MixOverride.CUT_RAMP_SEC, g[2][0], 1e-6)
    }

    @Test
    fun syncIsOneTempoAndGlideMovesIt() {
        val s = v3()
        // 6% apart: Auto glides (over 4%), Sync holds one tempo, Glide glides
        val a = ForkV2PlannerTest.song("a", 120.0, 200.0, 0.9)
        val b = ForkV2PlannerTest.song("b", 127.2, 180.0, 0.9)
        assertNotNull(plan(s, a, b, null).glide)
        val sync = plan(s, a, b, ov("""{"beatMatch":"sync"}"""))
        assertNull(sync.glide)
        assertEquals("blend", sync.style)
        // tempo only moves up: the next song faster, so the playing one is brought up to it
        assertEquals(1.0, sync.rate, 1e-9)
        assertTrue(sync.outRate > 1.05)
        val glide = plan(s, a, ForkV2PlannerTest.song("b", 121.2, 180.0, 0.9), ov("""{"beatMatch":"glide"}"""))
        assertNotNull(glide.glide, "Glide asks for the glide even under 4%")
    }

    /** Sync or Glide picked beat-matches however far apart the tempos (editor.md §4, ruled 2026-11-26 [all]); Auto keeps Automix's limits. */
    @Test
    fun syncOrGlidePickedBeatMatchesHoweverFarApartTheTempos() {
        for (v in 1..3) for (pair in listOf(100.0 to 111.0, 100.0 to 125.0, 128.0 to 95.0)) {
            for (bm in listOf("sync", "glide")) {
                val a = pair.first
                val p = plan(ForkV2Planner.Settings().also { it.version = v }, ForkV2PlannerTest.song("a", a, 200.0, 0.9), ForkV2PlannerTest.song("b", pair.second, 200.0, 0.9),
                    ov("""{"preset":"smooth","beatMatch":"$bm","bars":8}"""))
                assertEquals("blend", p.style, "v$v $pair $bm: ${p.reason}")
                assertFalse((p.warnings ?: emptyList()).joinToString(" ").contains("without beat match"), "$pair $bm")
                assertTrue(p.durationSec.isFinite() && p.durationSec > 0 && p.rate.isFinite() && p.rate > 0, "$pair $bm")
                assertTrue(p.mixOutTrackTime + 8 * 4 * 60 / a <= 200 - 0.3 + 1e-6, "$pair $bm")
                if (bm == "glide") assertTrue((p.glide?.q ?: 0.0) > 0, "$pair glide")
            }
        }
        val auto = plan(v3(), ForkV2PlannerTest.song("a", 100.0, 200.0, 0.9), ForkV2PlannerTest.song("b", 111.0, 200.0, 0.9), ov("""{"preset":"smooth","bars":8}"""))
        assertTrue((auto.warnings ?: emptyList()).joinToString(" ").contains("without beat match"), "${auto.warnings}")
    }

    /** A length picked on a weak grid is kept and plays without beat match; Auto alone still shortens (editor.md §6, ruled 2026-11-26 [all]). */
    @Test
    fun aLengthPickedOnAWeakGridIsKeptWithoutBeatMatch() {
        val b = ForkV2PlannerTest.song("b", 120.0, 200.0, 0.9)
        for (conf in listOf(0.3, 0.5)) {
            val p = plan(v3(), ForkV2PlannerTest.song("a", 120.0, 200.0, conf), b, ov("""{"style":"blend","bars":16}"""))
            assertEquals("crossfade", p.style, "conf $conf")
            assertEquals(32.0, p.durationSec, 1e-6)
            assertTrue((p.warnings ?: emptyList()).joinToString(" ").contains("the beat grid is too weak to beat-match 16 bars: plays without beat match"), "${p.warnings}")
        }
        // a length the grid can hold still beat-matches
        assertEquals("blend", plan(v3(), ForkV2PlannerTest.song("a", 120.0, 200.0, 0.5), b, ov("""{"style":"blend","bars":8}""")).style)
        // with no length picked, Automix's own rule: shortened
        val auto = v3().also { it.bars = 16 }
        assertEquals(4, plan(auto, ForkV2PlannerTest.song("a", 120.0, 200.0, 0.3), b, null).bars)
    }

    /** A filter mix picked with a length plays it; Automix's own keeps its 8-bar cap (editor.md §6, ruled 2026-11-26 [all]). */
    @Test
    fun aFilterMixPickedWithALengthPlaysIt() {
        val a = ForkV2PlannerTest.song("a", 120.0, 200.0, 0.9)
        val b = ForkV2PlannerTest.song("b", 120.0, 200.0, 0.9)
        val p = plan(v3(), a, b, ov("""{"style":"filter","bars":16}"""))
        assertEquals("filter", p.style)
        assertEquals(16, p.bars)
        assertEquals(4, plan(v3(), a, b, ov("""{"style":"filter","bars":4}""")).bars)
    }

    @Test
    fun beatMatchOffWithAPresetStillPlaysOnOurDecks() {
        val p = plan(v3(), ForkV2PlannerTest.song("a", 120.0, 200.0, 0.9), ForkV2PlannerTest.song("b", 120.0, 180.0, 0.9), ov("""{"preset":"melt","beatMatch":"off"}"""))
        assertEquals("crossfade", p.style)
        assertEquals("melt", p.preset)
        assertFalse(p.fallback, "a preset can't play on a plain crossfade")
        // with beat match off the swap is 2% of the window either side of the centre
        assertEquals(0.48 * p.durationSec, p.curves!!.out["bassDb"]!![0][0], 1e-5)
    }

    @Test
    fun aWindowIsClampedWithTheReasonShown() {
        val s = v3()
        val a = ForkV2PlannerTest.song("a", 120.0, 200.0, 0.9)
        val b = ForkV2PlannerTest.song("b", 120.0, 180.0, 0.9)
        // A's mix-out asked at bar 97 (194.5 s): an 8 s window has to start by 191.7 s to end by
        // 199.7 s, so it moves back onto the last downbeat that fits (bar 95, 190.5 s)
        val p = plan(s, a, b, ov("""{"window":{"out":{"bar":97,"sec":194.5},"in":{"bar":4,"sec":8.5}}}"""))
        assertEquals("blend", p.style)
        assertEquals(190.5, p.mixOutTrackTime, 1e-9)
        val w = p.warnings!!
        assertTrue(w[0].startsWith("the mix-out moved to"), w.joinToString("; "))
        assertEquals(8.5, p.mixInTrackTime, 1e-9)
        // a window the grid allows plays where it was put
        val q = plan(s, a, b, ov("""{"window":{"out":{"bar":80,"sec":160.5}}}"""))
        assertEquals(160.5, q.mixOutTrackTime, 1e-9)
        assertNull(q.warnings)
    }

    @Test
    fun theEnginePlaysThePresetsPoints() {
        val s = v3()
        val a = ForkV2PlannerTest.song("a", 120.0, 200.0, 0.9)
        val b = ForkV2PlannerTest.song("b", 120.0, 180.0, 0.9)
        var m = ForkV2Mix.blend(plan(s, a, b, ov("""{"preset":"slam"}""")), "a", "b", 200.0)
        val c = m.t0 + m.D / 2
        // the cut: full until the centre, gone 8 ms later; the next song the other way round
        assertEquals(1.0, m.out.gain.at(c - 0.01), 1e-9)
        assertEquals(0.0, m.out.gain.at(c + 0.01), 1e-9)
        assertEquals(0.0, m.`in`.gain.at(c - 0.01), 1e-9)
        assertEquals(1.0, m.`in`.gain.at(c + 0.01), 1e-9)
        assertEquals(0.5, m.out.gain.at(c + 0.004), 1e-6)
        m = ForkV2Mix.blend(plan(s, a, b, ov("""{"preset":"melt"}""")), "a", "b", 200.0)
        // the next song's high-pass opens from 1 kHz to 20 Hz over the first half, exponentially
        assertEquals(1000.0, m.`in`.hpHz.at(m.t0), 1e-6)
        assertEquals(sqrt(1000.0 * 20), m.`in`.hpHz.at(m.t0 + m.D / 4), 1e-3)
        assertEquals(20.0, m.`in`.hpHz.at(m.t0 + m.D / 2 + 0.1), 1e-6)
        // the playing song's bass goes at the centre, the next song's comes in
        assertEquals(0.0, m.out.lowDb.at(m.t0 + 0.1), 1e-9)
        assertEquals(ForkV2MixOverride.CUT_DB, m.out.lowDb.at(m.t0 + m.D - 0.1), 1e-9)
        assertEquals(ForkV2MixOverride.CUT_DB, m.`in`.lowDb.at(m.t0 + 0.1), 1e-9)
        assertEquals(0.0, m.`in`.lowDb.at(m.t0 + m.D - 0.1), 1e-9)
    }

    // ── helpers ──

    private fun v3(): ForkV2Planner.Settings = ForkV2Planner.Settings().also { it.version = 3 }

    private fun plan(s: ForkV2Planner.Settings, a: ForkV2Analysis, b: ForkV2Analysis, o: ForkV2MixOverride?): ForkV2Planner.Plan =
        ForkV2Planner.plan(s, "a", "b", a, b, a.durationSec, 60.0, 60 + ForkV2PlannerTest.EARLIEST_LEAD, null, o)

    private fun json(s: String): Any? = ForkV2Json.parse(s)

    private fun ov(s: String): ForkV2MixOverride? = ForkV2MixOverride.fromJson(json(s))

    private fun bars(n: Int): ForkV2MixOverride = ForkV2MixOverride().also { it.bars = n }

    /** Every field set: a custom, locked override with a window (the phone's round-trip case). */
    private fun full(): ForkV2MixOverride {
        val o = ForkV2MixOverride()
        o.preset = "custom"
        o.volume = "overlap"
        o.eq = "endBassSwap"
        o.effect = "hpIn"
        o.bars = 16
        o.beatMatch = "glide"
        o.out = ForkV2MixOverride.Position(80, 2, 37, 192.4567)
        o.`in` = ForkV2MixOverride.Position(-1, 0, 0, -0.75)
        o.lock = true
        o.engine = 3
        o.savedAt = 1763400000000L
        return o
    }

    /** A steady song as the plans cases give it ({bpm, sec, downbeatSec}): every beat sure, flat energy (editor_cases.mjs song). */
    private fun steady(id: String, c: Map<String, Any?>): ForkV2Analysis {
        val bpm = (c["bpm"] as Number).toDouble()
        val sec = (c["sec"] as Number).toDouble()
        val a = ForkV2Analysis()
        a.fileKey = id
        a.bpm = bpm
        a.beatIntervalSec = 60 / bpm
        a.firstDownbeatSec = (c["downbeatSec"] as Number).toDouble()
        a.gridConfidence = 1.0
        a.durationSec = sec
        a.energyCurve = DoubleArray(ceil(sec / (a.beatIntervalSec * 16)).toInt()) { 1.0 }
        a.energy = 1.0
        a.introEndSec = 0.0
        a.outroStartSec = sec - 20
        a.tailGridKnown = false // no tailGrid in the case: the song's own grid at its end too
        return a
    }

    /** Curves as editor-cases.json writes them: durationSec, then out and in, each curve by name. */
    private fun asJson(c: ForkV2Curves): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["durationSec"] = c.durationSec
        m["out"] = side(c.out)
        m["in"] = side(c.`in`)
        return m
    }

    private fun side(curves: Map<String, Array<DoubleArray>>): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for ((k, pts) in curves) m[k] = pts.map { listOf(it[0], it[1]) }
        return m
    }

    /** Numbers within [TOLERANCE]; maps by key (order free); lists element by element. */
    private fun same(a: Any?, b: Any?): Boolean {
        if (a is Number && b is Number) return abs(a.toDouble() - b.toDouble()) <= TOLERANCE
        if (a is List<*> && b is List<*>) return a.size == b.size && a.indices.all { same(a[it], b[it]) }
        if (a is Map<*, *> && b is Map<*, *>) return a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        return a == b
    }

    private fun cases(): Map<String, Any?> = ForkV2Json.parse(File(specDir(), "automix/editor-cases.json").readText()) as Map<String, Any?>

    /** The extension's spec/: RULEBOOK_SPEC, else /spec. Missing fails locally and is skipped on CI. */
    private fun specDir(): File {
        val dir = File(System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec")
        if (!File(dir, "automix/editor-cases.json").isFile) {
            org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true")
            fail("the extension's spec is not at $dir (set RULEBOOK_SPEC, or mount it at /spec): no automix/editor-cases.json there")
        }
        return dir
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
