package com.music.bitchord.playback.smart.automixv2

/**
 * Fork: mix files (spec/automix/mix-file.md): the edits of one or more song pairs in one JSON file, so a mix made in
 * one app plays in another and ready-made mixes can be imported and listened to without opening the editor. The
 * owner imports these rather than designing mixes ("i import them, so i do not design, just use then, create a
 * playlist with them and listen").
 *
 * A port of automix.js readMixFile, writeMixFile, mixChanges, importPlan and foldOverride (the extension's mix-file
 * work); the messages are the spec's, word for word, so the three apps say the same thing about the same file.
 */
object ForkV2MixFile {

    const val FORMAT = "vivi-mix"
    const val VERSION = 1

    /** The most mixes in one file (rule 1). */
    const val MAX = 300

    /** The largest file read, in characters (rule 1: 2 MB). */
    const val MAX_BYTES = 2 * 1024 * 1024

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** A song as the file names it: the YouTube videoId is the key; the title and artist are for people only. */
    class Song(val videoId: String, val title: String? = null, val artist: String? = null) {
        /** The title, else the id: how a summary or a file name names the song. */
        val label: String get() = title ?: videoId
    }

    class Mix(val from: Song, val to: Song, val override: ForkV2MixOverride) {
        /** The pair's key, as the editor's ([ForkV2OverrideStore.pairKey]). */
        val key: String get() = from.videoId + ">" + to.videoId
    }

    /** A mix left out, by its place in the file (1 first), and why. */
    class Skip(val n: Int, val why: String)

    /**
     * A file read: its mixes and the ones skipped, or [refused] (nothing imported) with why. A Spotify mix export
     * ([ForkV2SpotifyMix]) also gives its playlist's songs in order ([queue], for Play these mixes), what didn't carry
     * over ([notes]) and the playlist's [name].
     */
    class Read(
        val mixes: List<Mix>,
        val skipped: List<Skip>,
        val refused: String?,
        val queue: List<Song>? = null,
        val notes: List<String> = emptyList(),
        val name: String? = null,
    )

    /** A mix as read, before collecting: its place, songs and override JSON, or [why] it is skipped as it is. */
    internal class Item(val n: Int, val from: Any?, val to: Any?, val override: Any?, val why: String?)

    class Replace(val mix: Mix, val changes: List<String>)

    /** The file's mixes against the saved overrides: new pairs, ones replacing a different saved edit, and ones saved as they are. */
    class Plan(val add: List<Mix>, val replace: List<Replace>, val same: List<Mix>)

    /** An override on this app's grid: [k] the factor its beats were counted by (null: the tempos don't fold), and a note for the user. */
    class Fold(val ov: ForkV2MixOverride?, val k: Double?, val note: String?)

    // ---------------------------------------------------------------------------------------------
    // reading (rules 3, 4)

    @Suppress("UNCHECKED_CAST")
    fun read(text: String): Read {
        fun refuse(why: String) = Read(emptyList(), emptyList(), why)
        if (text.length > MAX_BYTES) return refuse("the file is over 2 MB: not a Vivimusic mix file")
        val j = runCatching { ForkV2Json.parse(text.removePrefix("﻿")) }.getOrNull()
            ?: return refuse("not a Vivimusic mix file: it is not JSON")
        if (j is Map<*, *> && j["format"] == ForkV2SpotifyMix.FORMAT) return ForkV2SpotifyMix.read(j) // fork: spotify-mixes.md
        if (j !is Map<*, *> || j["format"] != FORMAT) return refuse("not a Vivimusic mix file")
        val v = (j["version"] as? Number)?.toDouble()
        if (v == null || v != kotlin.math.floor(v) || v < 1) return refuse("the mix file has no valid version")
        if (v > VERSION) return refuse("this mix file is version ${v.toLong()}, newer than this app reads ($VERSION): update the app to import it")
        val list = j["mixes"] as? List<Any?>
        if (list.isNullOrEmpty()) return refuse("the mix file has no mixes")
        if (list.size > MAX) return refuse("the mix file has ${list.size} mixes: $MAX is the most")
        return collect(list.mapIndexed { i, e -> (e as? Map<String, Any?>).let { m -> Item(i + 1, m?.get("from"), m?.get("to"), m?.get("override"), null) } })
    }

    /** [items] as a file's mixes and the ones skipped (rule 4); an item with a why is skipped as it is. */
    @Suppress("UNCHECKED_CAST")
    internal fun collect(items: List<Item>): Read {
        val mixes = ArrayList<Mix>()
        val skipped = ArrayList<Skip>()
        // key -> (index in mixes, n of the mix there)
        val at = HashMap<String, Pair<Int, Int>>()
        items.forEach { item ->
            val n = item.n
            if (item.why != null) { skipped += Skip(n, item.why); return@forEach }
            val from = song(item.from)
            val to = song(item.to)
            if (from == null || to == null) { skipped += Skip(n, "a song has no valid videoId"); return@forEach }
            if (from.videoId == to.videoId) { skipped += Skip(n, "the same song on both sides"); return@forEach }
            val o = item.override
            val ov = mixOverride(runCatching { ForkV2MixOverride.fromJson(o) }.getOrNull())
            // a preset or a lane this app doesn't know is not dropped quietly
            val raw = (o as? Map<String, Any?>)?.get("preset") as? String
            if (!raw.isNullOrEmpty() && raw != "auto" && ov?.preset != raw) {
                skipped += Skip(n, if (raw == "custom") "its lanes are not valid" else "the preset \"$raw\" is unknown here")
                return@forEach
            }
            if (ov == null) { skipped += Skip(n, "nothing but Auto in it"); return@forEach }
            val mix = Mix(from, to, ov)
            val was = at[mix.key]
            if (was != null) {
                skipped += Skip(was.second, "the same pair again in mix $n: the later one is used")
                mixes[was.first] = mix
                at[mix.key] = was.first to n
                return@forEach
            }
            at[mix.key] = mixes.size to n
            mixes += mix
        }
        return Read(mixes, skipped, null)
    }

    // ---------------------------------------------------------------------------------------------
    // writing (rule 8)

    /** The file for [entries] (an invalid one left out), as pretty JSON (two-space indent, a newline at the end). */
    fun write(entries: List<Mix>): String = pretty(writeJson(entries)) + "\n"

    /** The file's object: songs as given, each override as a mix file carries it. */
    fun writeJson(entries: List<Mix>): Map<String, Any?> {
        val mixes = ArrayList<Any?>()
        for (e in entries) {
            val from = song(e.from) ?: continue
            val to = song(e.to) ?: continue
            val ov = mixOverride(e.override) ?: continue
            mixes += linkedMapOf("from" to songJson(from), "to" to songJson(to), "override" to ov.toJson())
        }
        return linkedMapOf("format" to FORMAT, "version" to VERSION, "mixes" to mixes)
    }

    /**
     * A saved mix as it goes into a file (mix-file.md rules 6, 8; extension cb897a0 exportOverride): with the tempo its
     * beats count on ([gridBpm]: as saved, else [bpm], this app's for A) and its beat match as it plays (beatMatchOf). A
     * file's mix carries gridBpm, so an own mix's Auto is written as "auto" (unwritten it would come back as Sync) and an
     * imported one with none as "sync". A loop mix has no beat match.
     */
    fun forExport(o: ForkV2MixOverride, bpm: Double?): ForkV2MixOverride {
        val ov = o.copy()
        val bm = if (ov.loop()) null else ov.beatMatchOf()
        if (!(ov.gridBpm > 0) && bpm != null && bpm > 0) ov.gridBpm = bpm
        if (bm != null && ov.gridBpm > 0) ov.beatMatch = bm
        return ov
    }

    /** How many mixes [write] keeps of [entries]. */
    fun writable(entries: List<Mix>): Int = entries.count { song(it.from) != null && song(it.to) != null && mixOverride(it.override) != null }

    // ---------------------------------------------------------------------------------------------
    // Play these mixes (rule 7)

    /** The file's songs in its order: each mix's from, then its to, a song already next in line not twice (A→B, B→C: A, B, C). */
    fun mixQueue(mixes: List<Mix>): List<String> {
        val ids = ArrayList<String>()
        for (m in mixes) for (id in listOf(m.from.videoId, m.to.videoId)) if (VIDEO_ID.matches(id) && ids.lastOrNull() != id) ids += id
        return ids
    }

    // ---------------------------------------------------------------------------------------------
    // importing (rules 5, 6)

    /** The file's mixes against the saved overrides ([saved]: a pair key's saved override, or null). */
    fun importPlan(mixes: List<Mix>, saved: (String) -> ForkV2MixOverride?): Plan {
        val add = ArrayList<Mix>()
        val replace = ArrayList<Replace>()
        val same = ArrayList<Mix>()
        for (m in mixes) {
            val was = saved(m.key)
            if (was == null || ForkV2MixOverride.normalize(was) == null) add += m
            else if (sameMix(was, m.override)) same += m
            else replace += Replace(m, mixChanges(was, m.override))
        }
        return Plan(add, replace, same)
    }

    /** What replacing [was] with [now] changes, one line per field ("length: auto → 16 bars"). */
    fun mixChanges(was: ForkV2MixOverride?, now: ForkV2MixOverride?): List<String> {
        val a = mixOverride(was) ?: ForkV2MixOverride()
        val b = mixOverride(now) ?: ForkV2MixOverride()
        fun lane(o: ForkV2MixOverride, k: String): String =
            if (o.preset != "custom") {
                if (o.preset == "loop") "loop" else if (o.preset != null) o.preset + "'s" else "auto"
            } else {
                val name = o.laneName(k)
                if (ForkV2MixOverride.optional(k) && name == null) "none" else if (name != null && name != ForkV2MixOverride.DRAW) name else "drawn"
            }
        fun speed(o: ForkV2MixOverride): String = if (o.hasSpeed()) "A x${forkV2Js(o.speedOut)} B x${forkV2Js(o.speedIn)}" else "x1"
        fun pos(p: ForkV2MixOverride.Position?): String? = p?.let {
            (it.bar?.let { b -> "bar $b" + (if (it.beat != 0) "." + (it.beat + 1) else "") } ?: "${forkV2Js(it.sec)} s") + // a position in seconds only: its time (rule 5)
                (if (it.nudgeMs != 0) " ${if (it.nudgeMs > 0) "+" else ""}${it.nudgeMs} ms" else "") +
                (if (it.delaySec > 0) ", ${forkV2Js(it.delaySec)} s into the mix" else "") // B's late entry (mix-file.md rule 2)
        }
        val rows = ArrayList<Triple<String, String, String>>()
        rows += Triple("preset", a.preset ?: "auto", b.preset ?: "auto")
        for (k in ForkV2MixOverride.LANES) {
            // (the Effects lane only when a side has one: most mixes have none)
            if (ForkV2MixOverride.optional(k) && (a.preset != "custom" || a.laneName(k) == null) && (b.preset != "custom" || b.laneName(k) == null)) continue
            rows += Triple(if (k == "fx") "effects" else k, lane(a, k), lane(b, k))
        }
        rows += Triple("length", if (a.bars > 0) "${a.bars} bars" else "auto", if (b.bars > 0) "${b.bars} bars" else "auto")
        rows += Triple("beat match", a.beatMatchOf(), b.beatMatchOf()) // as it plays (rule 5, 2026-11-27); a loop: auto
        rows += Triple("speed", speed(a), speed(b))
        rows += Triple("mix-out", pos(a.out) ?: "auto", pos(b.out) ?: "auto")
        rows += Triple("mix-in", pos(a.`in`) ?: "auto", pos(b.`in`) ?: "auto")
        rows += Triple("lock", if (a.lock) "on" else "off", if (b.lock) "on" else "off")
        val out = ArrayList<String>()
        for ((k, x, y) in rows) {
            val lk = if (k == "effects") "fx" else k
            val redrawn = x == "drawn" && lk in ForkV2MixOverride.LANES && drawn(a, lk) != drawn(b, lk)
            if (x != y || redrawn) out += "$k: $x → ${if (x == "drawn" && y == "drawn") "another drawing" else y}"
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // half and double time (rule 4)

    /** An app that reads A within 4% of half or double the file's gridBpm counts its beats x2 or x1/2. */
    private const val FOLD_TOL = 0.04

    private fun foldFactor(gridBpm: Double, bpm: Double): Double? {
        if (!(gridBpm > 0) || !(bpm > 0)) return null
        val r = bpm / gridBpm
        for (k in doubleArrayOf(1.0, 2.0, 0.5)) if (kotlin.math.abs(r / k - 1) <= FOLD_TOL) return k
        return null
    }

    private fun countsBeats(o: ForkV2MixOverride?): Boolean =
        o != null && (o.bars > 0 || (o.preset == "custom" && ForkV2MixOverride.LANES.any { o.drawing(it)?.unit == "beat" }))

    /**
     * [o] on this app's grid, A read at [bpm]: an override's beats (bars, drawings in beats) count on A's grid at its
     * gridBpm, so at half or double that tempo here they are counted x2 or x1/2 and the mix plays the same seconds.
     */
    fun fold(o: ForkV2MixOverride?, bpm: Double?): Fold {
        if (o == null || !countsBeats(o) || !(o.gridBpm > 0) || bpm == null || !(bpm > 0)) return Fold(o, 1.0, null)
        val k = foldFactor(o.gridBpm, bpm)
            ?: return Fold(o, null, "drawn at ${jround(o.gridBpm)} BPM, this song reads ${jround(bpm)} BPM: its beats are taken as they are")
        if (k == 1.0) return Fold(o, k, null)
        val ov = o.copy()
        fun r3(v: Double) = jround(v * 1e3) / 1e3
        fun scale(side: Map<String, Array<DoubleArray>>) = side.mapValues { (_, pts) -> Array(pts.size) { doubleArrayOf(r3(pts[it][0] * k), pts[it][1]) } }
        for (lane in ForkV2MixOverride.LANES) {
            val d = ov.drawing(lane) ?: continue
            if (d.unit != "beat") continue
            ov.setLane(lane, null, ForkV2Drawing(d.lane, d.unit, scale(d.out), scale(d.`in`), d.echoBeats, d.delayBeats))
        }
        var note: String? = null
        if (ov.bars > 0) {
            val want = (ov.bars * k).toInt()
            ov.bars = if (want in ForkV2MixOverride.BARS) want else if (want > 32) 32 else 2
            if (ov.bars != want) note = "$want bars on this song's grid: ${ov.bars} play"
        }
        ov.gridBpm = jround(o.gridBpm * k * 100) / 100.0
        return Fold(ov, k, note)
    }

    /** The import summary's note for one mix: no tempo, or one that doesn't fold. */
    fun foldNote(o: ForkV2MixOverride?, bpm: Double?): String? {
        if (!countsBeats(o)) return null
        if (!(o!!.gridBpm > 0)) return "no gridBpm: its beats are taken as they are"
        return fold(o, bpm).note
    }

    // ---------------------------------------------------------------------------------------------

    /** The override as a mix file carries it: normalized, without this app's engine and savedAt; null: nothing but Auto. */
    fun mixOverride(o: ForkV2MixOverride?): ForkV2MixOverride? {
        val n = ForkV2MixOverride.normalize(o) ?: return null
        n.engine = 0
        n.savedAt = 0
        return n
    }

    private fun sameMix(a: ForkV2MixOverride?, b: ForkV2MixOverride?): Boolean = stable(a) == stable(b)

    /**
     * The override's saved JSON without gridBpm (a tempo is not an edit), for comparing; the beat match as it plays
     * (beatMatchOf, rule 5, extension d9e6f90): an own export's beatMatch "auto" is the saved mix's unwritten Auto.
     */
    private fun stable(o: ForkV2MixOverride?): String? =
        mixOverride(o)?.let { n -> n.beatMatch = if (n.loop()) null else n.beatMatchOf(); n.gridBpm = 0.0; ForkV2Json.write(n.toJson()) }

    private fun drawn(o: ForkV2MixOverride, lane: String): String? = o.drawing(lane)?.let { ForkV2Json.write(it.toJson()) }

    private fun text(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(200)

    @Suppress("UNCHECKED_CAST")
    private fun song(v: Any?): Song? {
        val m = v as? Map<String, Any?> ?: return null
        val id = m["videoId"] as? String ?: return null
        if (!VIDEO_ID.matches(id)) return null
        return Song(id, text(m["title"]), text(m["artist"]))
    }

    private fun song(s: Song): Song? = if (VIDEO_ID.matches(s.videoId)) Song(s.videoId, text(s.title), text(s.artist)) else null

    private fun songJson(s: Song): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("videoId", s.videoId)
        s.title?.let { put("title", it) }
        s.artist?.let { put("artist", it) }
    }

    /** JSON.stringify(v, null, 2). */
    fun pretty(v: Any?): String {
        val b = StringBuilder()
        pretty(b, v, "")
        return b.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun pretty(b: StringBuilder, v: Any?, ind: String) {
        val list: List<Any?>? = when (v) {
            is List<*> -> v
            is DoubleArray -> v.toList()
            is Array<*> -> v.toList()
            else -> null
        }
        when {
            v is Map<*, *> -> {
                if (v.isEmpty()) { b.append("{}"); return }
                b.append("{\n")
                val inner = "$ind  "
                var first = true
                for ((k, value) in v as Map<String, Any?>) {
                    if (!first) b.append(",\n")
                    first = false
                    b.append(inner).append(ForkV2Json.write(k)).append(": ")
                    pretty(b, value, inner)
                }
                b.append('\n').append(ind).append('}')
            }
            list != null -> {
                if (list.isEmpty()) { b.append("[]"); return }
                b.append("[\n")
                val inner = "$ind  "
                list.forEachIndexed { i, x ->
                    if (i > 0) b.append(",\n")
                    b.append(inner)
                    pretty(b, x, inner)
                }
                b.append('\n').append(ind).append(']')
            }
            else -> b.append(ForkV2Json.write(v))
        }
    }
}
