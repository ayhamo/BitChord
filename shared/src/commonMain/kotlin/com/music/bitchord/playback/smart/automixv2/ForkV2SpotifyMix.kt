package com.music.bitchord.playback.smart.automixv2

import kotlin.math.max
import kotlin.math.pow

/**
 * Fork: a Spotify mix export imported as it is (the extension's spec/automix/spotify-mixes.md): `"spotify-mix-export"`
 * version 3, read from Spotify's mixed playlists with each song's YouTube id in `youtubeVideoId`. Each Spotify mix
 * becomes a `custom` override, its window in seconds only (editor.md §6), and Play these mixes queues the playlist's
 * tracks in order. The owner asked for Spotify's standard export as it is rather than a converted mix file (2026-11-25).
 *
 * A port of automix.js readSpotifyMixes and spotifyOverride; the messages are the spec's, word for word.
 */
internal object ForkV2SpotifyMix {

    const val FORMAT = "spotify-mix-export"
    const val VERSION = 3

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    // (rule 4: the names as Spotify's editor shows them, research/spotify-mixes/tools/style_names.json)
    private val VOLUME = mapOf(
        1 to "centerCut", 2 to "crossfade", 5 to "fadeInFadeOut", 6 to "smooth", 7 to "overlap", 9 to "fadeInCutOut", 10 to "cutInFadeOut", 11 to "fadeInFastOut",
        12 to "fadeIn1BarCutOut", 13 to "overlap1BarCutOut",
    )
    private val EQ = mapOf(0 to "none", 1 to "threeBandFade", 4 to "centerBassSwap", 10 to "endBassSwap", 11 to "startBassSwap", 12 to "quickBassCut", 13 to "longBassCut", 15 to "bassFadeOut")
    private val FILTER = mapOf(
        0 to "none", 5 to "hpIn", 6 to "hpOut", 7 to "hpBoth", 9 to "lpIn", 10 to "lpOut", 11 to "lpBoth", 12 to "hpInLpOut", 13 to "lpInHpOut",
        14 to "hpHalfOut", 15 to "noiseOutEnd",
    )

    /** (fx 3, 4, 5, 10, 11 have no name yet: each comes with a drawing, which carries it) */
    private val FX = mapOf(
        0 to "none", 1 to "reverbOutCenter", 2 to "reverbCutEnd", 3 to "reverbOutEnd", 4 to "echoHalfCutEnd", 5 to "echoHalfOutEnd",
        6 to "echo34CutEnd", 7 to "echo34OutEnd", 8 to "delayHalfCutEnd", 9 to "delay34CutEnd", 10 to "echo1CutEnd", 11 to "echo1OutEnd",
    )
    private val LOOP = mapOf(0 to "none", 1 to "loop2", 2 to "loop4", 3 to "loop8", 4 to "loop16", 8 to "loop1")
    private val DEFAULT = mapOf("volume" to "overlap", "eq" to "centerBassSwap", "effect" to "none", "fx" to "none", "looping" to "none")

    /** Spotify's base presets whose lanes are this app's presets (rule 4, editor.md §2). */
    private val BASE_PRESETS = mapOf(
        1 to "smooth", 2 to "blend", 3 to "rise", 4 to "melt", 11 to "slam", 17 to "wave", 20 to "dissolve", 21 to "surge", 22 to "afterglow",
    )

    /** What a mix's unset lanes play, by its `basePresetId` (rule 4, Spotify 9.2.2's ids): that preset's lanes, 5, 10 and 19's (older presets), else the default. */
    private fun baseLanes(id: Any?): Map<String, String> {
        val d = (id as? Number)?.toDouble()
        val n = if (d != null && d == kotlin.math.floor(d)) d.toInt() else return DEFAULT
        BASE_PRESETS[n]?.let { p -> ForkV2MixOverride.lanesOf(p)?.let { l -> return ForkV2MixOverride.LANES.indices.associate { ForkV2MixOverride.LANES[it] to (l[it] ?: "none") } } }
        return when (n) {
            5 -> DEFAULT + ("volume" to "fadeInCutOut")
            10 -> DEFAULT + mapOf("eq" to "centerBassSwap", "effect" to "hpBoth")
            19 -> DEFAULT + mapOf("volume" to "overlap", "eq" to "centerBassSwap", "effect" to "lpBoth")
            else -> DEFAULT
        }
    }

    /** 2^53: an older export wrote a negative time unsigned (2^64 − x); anything from here up is negative. */
    private const val UNSIGNED = 9007199254740992.0

    private const val CUT = ForkV2MixOverride.CUT_DB
    private const val LP_CLOSED = ForkV2MixOverride.LP_CLOSED
    private const val HP_CLOSED = ForkV2MixOverride.HP_CLOSED

    /** What a file lost on the way in, for the summary (rule 9). */
    private class Lost {
        var unnamed = 0
        val notCarried = LinkedHashSet<String>()
        var late = 0
    }

    /** The export [j] (its format checked) read as a mix file is (rules 1, 2, 7 to 9). */
    @Suppress("UNCHECKED_CAST")
    fun read(j: Map<*, *>): ForkV2MixFile.Read {
        fun refuse(why: String) = ForkV2MixFile.Read(emptyList(), emptyList(), why)
        val v = (j["version"] as? Number)?.toDouble()
        if (v == null || v != kotlin.math.floor(v) || v < 1) return refuse("the Spotify mix file has no valid version")
        if (v > VERSION) return refuse("this Spotify mix file is version ${v.toLong()}, newer than this app reads ($VERSION): update the app to import it")
        if (v < VERSION) return refuse("this Spotify mix file is version ${v.toLong()}: it has no YouTube ids yet (version $VERSION has them)")
        val list = j["mixes"] as? List<Any?>
        if (list.isNullOrEmpty()) return refuse("the mix file has no mixes")
        if (list.size > ForkV2MixFile.MAX) return refuse("the mix file has ${list.size} mixes: ${ForkV2MixFile.MAX} is the most")
        val lost = Lost()
        val items = list.mapIndexed { i, e ->
            val m = e as? Map<String, Any?>
            val index = (m?.get("index") as? Number)?.toDouble()
            val n = if (index != null && index == kotlin.math.floor(index)) index.toInt() else i + 1
            val from = song(m?.get("from"))
            val to = song(m?.get("to"))
            if (from == null || to == null) {
                val t = (if (from == null) m?.get("from") else m?.get("to")) as? Map<String, Any?>
                return@mapIndexed ForkV2MixFile.Item(n, null, null, null, "no YouTube id for ${(t?.get("title") as? String)?.takeIf { it.isNotEmpty() } ?: "a song"}")
            }
            m!!
            // a mix of a millisecond with no start: Spotify's straight cut, its No transition (rule 3, editor.md §2). Only a
            // durationMs that is there and under 100 is one: `!(d >= 100)` was also true for a missing (NaN) durationMs, so a
            // mix with no timing at all imported as a straight cut, where rule 7 skips it ("its timing is not valid"; the
            // extension's readSpotifyMixes checks Number.isFinite first, audit 2026-11-26)
            val dur = num(m["durationMs"])
            if (dur.isFinite() && dur >= 0 && dur < 100 && !num(m["fromStartMs"]).isFinite()) {
                return@mapIndexed ForkV2MixFile.Item(n, from, to, linkedMapOf<String, Any?>("v" to 1, "preset" to "none"), null)
            }
            val ov = override(m, lost) ?: return@mapIndexed ForkV2MixFile.Item(n, null, null, null, "its timing is not valid")
            ForkV2MixFile.Item(n, from, to, ov, null)
        }
        val r = ForkV2MixFile.collect(items)
        val queue = ArrayList<ForkV2MixFile.Song>()
        val tracks = ((j["playlist"] as? Map<String, Any?>)?.get("tracks") as? List<Any?>).orEmpty()
        for (t in tracks) {
            val s = song(t) ?: continue
            if (queue.lastOrNull()?.videoId != s["videoId"]) queue += ForkV2MixFile.Song(s["videoId"] as String, s["title"] as? String, s["artist"] as? String)
        }
        val notes = ArrayList<String>()
        if (lost.unnamed > 0) notes += "${lost.unnamed} lane${if (lost.unnamed == 1) "" else "s"} in a Spotify style with no match here play the default shape"
        if (lost.notCarried.isNotEmpty()) notes += "not carried: ${lost.notCarried.joinToString(", ")}"
        // (rule 10: saved with the mix; neither app plays a song's speed yet)
        if (lost.late > 0) {
            notes += "${lost.late} mix${if (lost.late == 1) "" else "es"} bring${if (lost.late == 1) "s" else ""} B in late in an older " +
                "export's unsigned time: here B starts with the mix"
        }
        val name = ((j["playlist"] as? Map<String, Any?>)?.get("name") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        return ForkV2MixFile.Read(r.mixes, r.skipped, null, queue, notes, name)
    }

    /** A track as a mix file's song (a map, as the file would give it), or null with no YouTube id. */
    @Suppress("UNCHECKED_CAST")
    private fun song(v: Any?): Map<String, Any?>? {
        val t = v as? Map<String, Any?> ?: return null
        val id = t["youtubeVideoId"] as? String ?: return null
        if (!VIDEO_ID.matches(id)) return null
        val artists = t["artists"] as? List<Any?>
        return linkedMapOf("videoId" to id, "title" to t["title"], "artist" to artists?.joinToString(", ") { it.toString() })
    }

    private fun num(v: Any?): Double = (v as? Number)?.toDouble() ?: Double.NaN

    private fun r3(v: Double): Double = jround(v * 1000) / 1000.0

    private fun clamp(x: Double, lo: Double, hi: Double) = max(lo, kotlin.math.min(hi, x))

    /** A curve's y to the drawing's value; [linear]: a two-point line is kept as its two points, else sampled at 5. */
    private class F(val linear: Boolean, val f: (Double) -> Double)

    /** One Spotify mix as a `custom` override in its stored JSON form (rules 3 to 6), or null when its timing is not valid. */
    @Suppress("UNCHECKED_CAST")
    private fun override(m: Map<String, Any?>, lost: Lost): Map<String, Any?>? {
        val D = num(m["durationMs"]) / 1000
        val toStart = num(m["toStartMs"])
        fun ms(v: Double): Double? = if (v.isFinite() && v >= 0 && v < UNSIGNED) v else null
        // a negative B start: B's 0:00 comes in that long after the mix starts (rule 3; mix-file.md rule 2, B's late entry)
        // (an older export's unsigned one is too far off to read back: B starts with the mix, and the summary says so)
        if (toStart >= UNSIGNED) lost.late++
        val late = if (toStart < 0 && toStart.isFinite()) r3(-toStart / 1000) else 0.0
        val fromStart = ms(num(m["fromStartMs"]))
        if (!(D > 0) || fromStart == null) return null
        val barsRaw = num(m["bars"])
        val bars = if (barsRaw in listOf(2.0, 4.0, 8.0, 16.0, 32.0)) barsRaw.toInt() else 0
        val unit = if (bars > 0) "beat" else "sec"
        val N = if (bars > 0) bars * 4.0 else r3(D)
        val st = m["style"] as? Map<String, Any?> ?: emptyMap()
        val c = m["curves"] as? Map<String, Any?> ?: emptyMap()
        // (rules 6 and 9: no lane here plays a jogwheel, so the summary names it; the extension's spotifyOverride does the
        // same, and the phone's B3-08. It was left out, so a file with one imported with no word that it was dropped.)
        if (truthy(st["jogwheel"]) || truthy(c["jogwheel"])) lost.notCarried += "jogwheel"
        // an unset lane plays the mix's base preset's (rule 4); a number with no name here plays as unset, counted only on
        // a lane with no drawing (a drawing carries it)
        val base = baseLanes(st["basePresetId"])
        fun pick(map: Map<Int, String>, k: String, lane: String): String {
            val x = st[k] ?: return base[lane]!!
            val d = (x as? Number)?.toDouble()
            if (d != null && d == kotlin.math.floor(d)) map[d.toInt()]?.let { return it }
            if (!truthy(c[k])) lost.unnamed++
            return base[lane]!!
        }
        val lanes = linkedMapOf<String, Any?>(
            "volume" to pick(VOLUME, "volume", "volume"),
            "eq" to pick(EQ, "eq", "eq"),
            "effect" to pick(FILTER, "filter", "effect"),
            "fx" to pick(FX, "fx", "fx"),
            "looping" to pick(LOOP, "looping", "looping"), // (the style alone sets it, rule 6)
        )

        // a Spotify curve (segments of fractions, lines or cubic Béziers) as [x, v] points in N units
        fun points(segs: Any?, f: F): List<List<Double>> {
            val pts = ArrayList<DoubleArray>()
            for (s in (segs as? List<Any?>).orEmpty()) {
                val seg = s as? Map<String, Any?>
                val P = (seg?.get("points") as? List<Any?>).orEmpty().map { p -> (p as? List<Any?>).orEmpty().map { num(it) } }
                // a point is [x, y]: one short of that (a hand-edited or damaged file) skips its segment, as one of under two
                // points does. It was indexed as it stood, and the IndexOutOfBoundsException failed the whole import
                // (audit 2026-11-26 part 1a #5). Dropping just that point would turn a Bézier's four into a line's three.
                // A coordinate or a segment start/end that isn't a number (null, a string) reads as NaN, which r3's jround
                // turned into 0: the bad point was drawn at 0 instead of its segment being passed over. As the extension's
                // spotifyOverride, a segment with any of them not finite is skipped whole (audit 2026-11-26).
                val start = num(seg!!["start"])
                val end = num(seg["end"])
                if (P.size < 2 || P.any { it.size < 2 || !it[0].isFinite() || !it[1].isFinite() } || !start.isFinite() || !end.isFinite()) continue
                fun at(px: Double) = (start + px * (end - start)) * N
                if (P.size == 4) {
                    for (k in 0..8) {
                        val t = k / 8.0
                        val u = 1 - t
                        val w = doubleArrayOf(u * u * u, 3 * u * u * t, 3 * u * t * t, t * t * t)
                        var x = 0.0
                        var y = 0.0
                        for (i in 0..3) { x += w[i] * P[i][0]; y += w[i] * P[i][1] }
                        pts += doubleArrayOf(at(x), f.f(y))
                    }
                } else if (f.linear) {
                    for (p in P) pts += doubleArrayOf(at(p[0]), f.f(p[1]))
                } else {
                    val a = P[0]
                    val b = P[P.size - 1]
                    for (k in 0..4) {
                        val t = k / 4.0
                        pts += doubleArrayOf(at(a[0] + t * (b[0] - a[0])), f.f(a[1] + t * (b[1] - a[1])))
                    }
                }
            }
            val out = ArrayList<List<Double>>()
            for (p in pts) {
                val q = listOf(r3(clamp(p[0], 0.0, N)), r3(p[1]))
                if (out.isEmpty() || out.last() != q) out += q
            }
            return out
        }
        val gain = F(true) { y -> clamp(y, 0.0, 1.0) }
        val amount = gain
        val amountAbove = F(true) { y -> clamp((y - 0.5) / 0.5, 0.0, 1.0) }
        val db = F(true) { y -> if (y >= 0.5) 0.0 else CUT * (0.5 - y) / 0.5 }

        // a lane's shape as a drawing (the deck a curve leaves out)
        fun shape(lane: String, v: String): Map<String, Any?> {
            val d = ForkV2Drawing.shapeDrawing(lane, v, D, if (unit == "beat") D / N else 0.5, null, unit)
            return if (d is ForkV2Drawing) d.toJson() else linkedMapOf("unit" to unit, "out" to emptyMap<String, Any?>(), "in" to emptyMap<String, Any?>())
        }

        fun deckOf(lane: String, dk: String): Map<String, Any?>? = (c[lane] as? Map<String, Any?>)?.get(dk) as? Map<String, Any?>
        if (truthy(c["volume"])) {
            val base = shape("volume", lanes["volume"] as String)
            fun deck(dk: String, b: Any?): Any? = deckOf("volume", dk)?.get("curve")?.takeIf { truthy(it) }?.let { points(it, gain) } ?: b
            lanes["volume"] = linkedMapOf("unit" to unit, "out" to deck("from", base["out"]), "in" to deck("to", base["in"]))
        }
        if (truthy(c["eq"])) {
            val base = shape("eq", lanes["eq"] as String)
            fun deck(dk: String, b: Any?): Any? {
                val e = deckOf("eq", dk)
                if (!truthy(e?.get("low")) && !truthy(e?.get("mid")) && !truthy(e?.get("high"))) return b
                val out = LinkedHashMap<String, Any?>()
                for ((k, ours) in listOf("low" to "bass", "mid" to "mid", "high" to "high")) {
                    val pts = e!![k]?.takeIf { truthy(it) }?.let { points(it, db) }
                    if (pts != null && pts.any { it[1] < 0 }) out[ours] = pts
                }
                return out
            }
            lanes["eq"] = linkedMapOf("unit" to unit, "out" to deck("from", base["out"]), "in" to deck("to", base["in"]))
        }
        if (truthy(c["filter"])) {
            val base = shape("effect", lanes["effect"] as String)
            fun deck(dk: String, b: Any?): Any? {
                val segs = deckOf("filter", dk)?.get("cutoff")?.takeIf { truthy(it) }
                val ns = deckOf("filter", dk)?.get("noiseColor")?.takeIf { truthy(it) }
                if (segs == null && ns == null) return b
                val out = LinkedHashMap<String, Any?>()
                if (segs != null) {
                    val lp = ArrayList<List<Double>>()
                    val hp = ArrayList<List<Double>>()
                    for ((x, y) in points(segs, F(false) { it })) {
                        // y 0.5 open: above, a high-pass up to 1 kHz; below, a low-pass down to 400 Hz
                        if (y >= 0.5) {
                            lp += listOf(x, 20000.0)
                            hp += listOf(x, r3(20 * (HP_CLOSED / 20).pow((y - 0.5) / 0.5)))
                        } else {
                            lp += listOf(x, r3(20000 * (LP_CLOSED / 20000).pow((0.5 - y) / 0.5)))
                            hp += listOf(x, 20.0)
                        }
                    }
                    if (lp.any { it[1] < 20000 }) out["lp"] = lp
                    if (hp.any { it[1] > 20 }) out["hp"] = hp
                } else {
                    (b as? Map<*, *>)?.get("lp")?.let { out["lp"] = it }
                    (b as? Map<*, *>)?.get("hp")?.let { out["hp"] = it }
                }
                // noiseColor 0.5 is no noise; above it, the noise's level up to 1 (rule 5)
                val noise = ns?.let { points(it, amountAbove) }
                if (noise != null && noise.any { it[1] > 0 }) out["noise"] = noise
                return out
            }
            lanes["effect"] = linkedMapOf("unit" to unit, "out" to deck("from", base["out"]), "in" to deck("to", base["in"]))
            if (truthy(deckOf("filter", "from")?.get("resonance")) || truthy(deckOf("filter", "to")?.get("resonance"))) lost.notCarried += "filter resonance"
        }
        if (truthy(c["fx"])) {
            val named = lanes["fx"] as String
            val base = shape("fx", named)
            fun deck(dk: String, b: Any?): Any? {
                val e = deckOf("fx", dk)
                if (!truthy(e?.get("echo")) && !truthy(e?.get("reverb")) && !truthy(e?.get("delay"))) return b
                val out = LinkedHashMap<String, Any?>()
                for (k in listOf("echo", "reverb", "delay")) {
                    val pts = e!![k]?.takeIf { truthy(it) }?.let { points(it, amount) }
                    if (pts != null && pts.any { it[1] > 0 }) out[k] = pts
                }
                return out
            }
            val d = linkedMapOf<String, Any?>("unit" to unit, "out" to deck("from", base["out"]), "in" to deck("to", base["in"]))
            // a named fx style sets the drawing's echo or delay time (rule 5)
            val t = ForkV2MixOverride.FX_SHAPES[named]
            if (t?.send == "echoSend") d["echoBeats"] = t.beats
            if (t?.send == "delaySend") d["delayBeats"] = t.beats
            lanes["fx"] = d
        }
        // an Effects lane that is none is not written (editor.md §12 rule 1)
        if (lanes["fx"] == "none") lanes.remove("fx")
        if (lanes["looping"] == "none") lanes.remove("looping")
        val fromBpm = num(m["fromBpm"])
        val gridBpm = if (fromBpm > 0) fromBpm else if (bars > 0) bars * 4 * 60 / D else Double.NaN
        val o = linkedMapOf<String, Any?>("v" to 1, "preset" to "custom", "lanes" to lanes)
        if (bars > 0) o["bars"] = bars else o["beatMatch"] = "off"
        // B's start: none or a negative one → from 0, with the mix (rule 3)
        // B's start: none -> from 0; a negative one -> from 0, that long after the mix starts (rule 3)
        val inPos = linkedMapOf<String, Any?>("sec" to r3((ms(toStart) ?: 0.0) / 1000))
        if (late >= 0.001) inPos["delaySec"] = late
        o["window"] = linkedMapOf("out" to mapOf("sec" to r3(fromStart / 1000)), "in" to inPos)
        if (gridBpm.isFinite() && gridBpm > 0) o["gridBpm"] = gridBpm
        // each song's speed in the playlist (rule 10: f18 A's, f19 B's; missing is 1)
        val so = ForkV2MixOverride.speedOf(m["f18"])
        val si = ForkV2MixOverride.speedOf(m["f19"])
        if (so != 1.0 || si != 1.0) {
            o["speed"] = linkedMapOf<String, Any?>().apply { if (so != 1.0) put("out", so); if (si != 1.0) put("in", si) }
        }
        return o
    }

    /** JavaScript's truthiness, for the export's optional fields (0, "", false, null: not set). */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> v.isNotEmpty()
        else -> true
    }
}
