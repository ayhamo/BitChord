package com.music.bitchord.data.canvas

import java.util.Locale

/**
 * Fork: which search result is the song's own music video — the canvas rulebook's §5, Prism's
 * `MusicVideoMatch` as the owner's phone extension ports it (`VideoCanvas.pick`).
 *
 * A wrong video is worse than none: it loops behind the song for as long as it plays. So the bar
 * is the extension's: the title must be the song's once brackets, credits, video words and the
 * artists' and channel's names are taken out; never a lyric video, a lesson, a re-tuned or slowed
 * copy and the like; posted by the artist (their channel, its VEVO or "Official" form) or their
 * record label, within 150 s of the song and not another take. Someone else's upload counts only
 * for a song naming a session or show in its brackets, and then only that very recording (within
 * 4 s, naming the artist).
 */
object ForkMusicVideoMatch {

    /** A search result: its video id, title, channel and length (s; 0 unknown). */
    data class Video(val id: String, val title: String, val channel: String, val lengthSec: Long)

    private val NEVER = listOf(
        "lyric", "lyrics", "letra", "letras", "paroles", "testo", "traducida", "traducción",
        "sub español", "subtitulad", "перевод", "текст",
        "karaoke", "instrumental", "reaction", "tutorial", "lesson", "guitar", "piano", "drum", "bass", "slowed", "sped up",
        "nightcore", "8d", "fan made", "fanmade", "remix", "highlights", "edit audio", "official audio", "audio oficial",
        "audio only", "rebassed", "bass boosted", "bassboosted", "boosted", "reverb", "daycore", "speed up", "1 hour",
        "10 hours", "loop", "mashup", "parody", "ai cover",
    )
    private val HZ = Regex("""(?<![\p{L}])\d+\s?hz(?![\p{L}])""")
    private val OTHER_TAKE = listOf("cover", "acoustic", "acústic", "unplugged", "live", "en vivo", "ao vivo", "concert", "session")
    private val GENERIC = setOf(
        "feat", "with", "from", "the", "and", "version", "session", "sessions",
        "live", "remaster", "remastered", "edit", "radio", "mix", "original", "deluxe", "explicit", "clean", "cover", "acoustic",
        "mono", "stereo", "single",
    )
    private val LABELS = setOf(
        "fueledbyramen", "atlanticrecords", "warnerrecords", "warnermusic",
        "elektrarecords", "roadrunnerrecords", "interscope", "interscoperecords", "republicrecords", "islandrecords", "defjam",
        "defjamrecordings", "capitolrecords", "columbiarecords", "epicrecords", "rcarecords", "sonymusic", "sonymusicentertainment",
        "universalmusicgroup", "emimusic", "parlophone", "parlophonerecords", "epitaphrecords", "hopelessrecords", "riserecords",
        "sumerianrecords", "nuclearblastrecords", "spinninrecords", "monstercat", "ultramusic", "armadamusic", "xlrecordings",
        "subpop", "dominorecordco", "4ad", "matadorrecords", "bigmachinerecords", "rhinorecords", "motownrecords", "topdawgent",
        "xorecords", "glassnoterecords",
    )
    private val LABEL_WORDS = Regex("""(?<![\p{L}])(records|recordings|music group|entertainment|label)(?![\p{L}])""")
    private val OFFICIAL = Regex("""(?<![\p{L}])official(?![\p{L}])""")
    private val BRACKETS = Regex("""\((.*?)\)|\[(.*?)]""")
    private const val LOOSE_S = 150
    private const val TIGHT_S = 4

    /** The artists named in YouTube Music's artist line ("A & B", "A, B", "A x B", "A feat. B"). */
    fun artists(line: String?): List<String> =
        line.orEmpty().split(Regex("""(?i)\s*(,|&|\band\b|\bx\b|\bfeat\.?|\bft\.?|\bwith\b)\s*"""))
            .filter { norm(it).isNotEmpty() }
            .map { it.trim() }

    /** "3:45" or "1:02:03" in seconds; 0 when it is not a length. */
    fun lengthOf(text: String?): Long {
        val t = text.orEmpty().trim()
        if (!Regex("""\d{1,2}(:\d{2}){1,2}""").matches(t)) return 0
        return t.split(':').fold(0L) { s, p -> s * 60 + p.toLong() }
    }

    /**
     * The first of [videos] (in search order) that is the song's music video, or null. [durationSec]
     * 0: the song's length is unknown, and lengths aren't checked.
     */
    fun pick(songTitle: String, artistNames: List<String>, durationSec: Long, videos: List<Video>): Video? {
        val artists = artistNames.map(::norm).filter { it.isNotEmpty() }
        if (norm(songTitle).isEmpty() || artists.isEmpty()) return null
        val song = songTitle.lowercase(Locale.ROOT)
        // the source the song names in its brackets ("SiriusXM Session" -> "siriusxm"), without the generic words
        val source = LinkedHashSet<String>()
        for (m in BRACKETS.findAll(song)) {
            for (w in m.value.split(Regex("""[^\p{L}\p{N}]+"""))) if (w.length > 2 && w !in GENERIC) source += w
        }
        val candidates = videos.filter { v ->
            sameTitle(songTitle, artistNames, v.title, v.channel) && !never(v.title.lowercase(Locale.ROOT), song) &&
                (ownChannel(v.channel, artists) || !never(v.channel.lowercase(Locale.ROOT), song))
        }
        // the artist's own upload first: their channel (VEVO and "Official" ones too), or their record label's
        candidates.firstOrNull { v ->
            (ownChannel(v.channel, artists) || byLabel(v.title, v.channel, artists)) && within(durationSec, v, LOOSE_S) &&
                !has(OTHER_TAKE, v.title.lowercase(Locale.ROOT), song)
        }?.let { return it }
        if (source.isEmpty()) return null
        // a song from a named session or show: that very recording, posted by whoever made it
        return candidates.firstOrNull { v ->
            val words = v.title.lowercase(Locale.ROOT)
            val where = words + " " + v.channel.lowercase(Locale.ROOT)
            within(durationSec, v, TIGHT_S) && durationSec > 0 && v.lengthSec > 0 &&
                artists.any { norm(words).contains(it) } && source.any { where.contains(it) }
        }
    }

    private fun within(durationSec: Long, v: Video, slack: Int): Boolean =
        durationSec == 0L || v.lengthSec == 0L || kotlin.math.abs(v.lengthSec - durationSec) <= slack

    /** The channel is the artist's own: its name, or its VEVO / "Official" form. */
    private fun ownChannel(channel: String, artists: List<String>): Boolean {
        val ch = officialChannel(norm(channel))
        return ch.isNotEmpty() && ch in artists
    }

    /** Posted by the artist's record label, naming the artist: a label we know, or a "... Records" channel saying official. */
    private fun byLabel(videoTitle: String, channel: String, artists: List<String>): Boolean {
        if (artists.none { norm(videoTitle).contains(it) }) return false
        return norm(channel) in LABELS ||
            LABEL_WORDS.containsMatchIn(channel.lowercase(Locale.ROOT)) && OFFICIAL.containsMatchIn(videoTitle.lowercase(Locale.ROOT))
    }

    /** A lyric video, a lesson, a re-tuned copy and the like (unless the song's own title says so). */
    private fun never(text: String, song: String): Boolean =
        has(NEVER, text, song) || HZ.containsMatchIn(text) && !HZ.containsMatchIn(song)

    private fun sameTitle(songTitle: String, artistNames: List<String>, videoTitle: String, channel: String): Boolean {
        val want = core(songTitle, emptyList())
        if (want.isEmpty()) return false
        val names = ArrayList<String>()
        for (a in artistNames + channel) {
            val n = norm(a)
            if (n.length < 2) continue
            for (x in listOf(n, officialChannel(n))) if (x !in names) names += x
        }
        names.sortByDescending { it.length }
        return want == core(videoTitle, names)
    }

    private fun has(words: List<String>, text: String, song: String): Boolean = words.any { w ->
        val p = Regex("""(?<![\p{L}\p{N}])""" + Regex.escape(w) + """(?![\p{L}\p{N}])""")
        p.containsMatchIn(text) && !p.containsMatchIn(song)
    }

    /** The title without brackets, "| ..." and "// ..." tails, feat. credits, video words and [names]. */
    private fun core(title: String, names: List<String>): String {
        var t = title.lowercase(Locale.ROOT).replace(Regex("""\(.*?\)|\[.*?]|【.*?】"""), " ")
        t.indexOf(" | ").takeIf { it >= 0 }?.let { t = t.substring(0, it) }
        t.indexOf(" // ").takeIf { it >= 0 }?.let { t = t.substring(0, it) }
        t = t.replace(Regex("""\s(ft\.?|feat\.?|featuring)\s.*$"""), " ")
            .replace(Regex("""(?<![\p{L}])(official|music|video|videoclip|clip|hd|4k|hq|remastered|remaster|visualizer|mv|m/v)(?![\p{L}])"""), " ")
        t = norm(t)
        for (n in names) if (t != n && n.isNotEmpty()) t = t.replace(n, "")
        return t
    }

    /** Lower case, bracketed parts and everything but letters and digits out. */
    fun norm(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("""\(.*?\)|\[.*?]"""), "").replace(Regex("""[^\p{L}\p{N}]"""), "")

    /** "NirvanaVEVO", "Official Billie Eilish" -> the artist's own name, normalized. */
    fun officialChannel(normalized: String): String {
        var n = normalized
        for (s in listOf("vevo", "official", "oficial", "officiel", "offiziell", "ufficiale", "music", "tv", "channel")) {
            if (n.endsWith(s)) n = n.substring(0, n.length - s.length)
        }
        if (n.startsWith("official")) n = n.substring(8)
        return n.ifEmpty { normalized }
    }
}
