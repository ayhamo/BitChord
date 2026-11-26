package com.music.bitchord.data.sources

/**
 * Fork: two rules of the addon rulebook (YT Music Extension `spec/addons/protocol.md`, baa5e1d),
 * shared by the phone and the PC.
 *
 * **8.1.6, never a manifest in the audio cache** ([isManifest]). An HLS playlist or a DASH manifest
 * is opened from its URL, never through the audio cache, and is never kept in it; one already kept is
 * dropped instead of served. Laya 33a710d1 is why: a DASH manifest read through the song's cache key
 * came back as the Opus copy Automix had cached, and the DASH parse failed. Told by its content type
 * (`mpegurl`, `dash+xml`) or by its first bytes (`#EXTM3U`, or `<MPD` after an optional BOM and XML
 * header), as `src/addons/file-cache.js` `isManifest` tells it.
 *
 * **8.1.4, a refusal doesn't end the race** ([Book]). A copy refused at a later check (the window
 * check before a swap, on both apps) is passed over for that song: its row isn't opened again for the
 * song, the same addon's other rows and the other addons still count, and other songs aren't affected.
 * The next row or lane is asked for while what plays (YouTube's audio) keeps playing, at most
 * [NEXT_ROWS] more copies per song (`crossfade.js` `NEXT_ROWS`: each one is a download); then what
 * plays stays.
 */
object ForkAddonRefusals {

    /** Copies tried after a refused one, per song (protocol.md 8.1.4, the extension's `NEXT_ROWS`). */
    const val NEXT_ROWS = 4

    private const val HEAD_BYTES = 512
    private val MANIFEST_MIME = Regex("mpegurl|dash\\+xml", RegexOption.IGNORE_CASE)
    // the root in any case, after an XML prolog, comments or a doctype (the extension's audit, 2026-11-24)
    private val MPD_HEAD = Regex(
        """^(?:<\?xml[^>]*>\s*)?(?:(?:<!--.*?-->|<!DOCTYPE[^>]*>)\s*)*<mpd[\s>]""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Whether [head] (a file's first bytes, any length) with content type [mime] is an HLS playlist
     * or a DASH manifest rather than audio.
     */
    fun isManifest(head: ByteArray?, mime: String? = null): Boolean {
        if (mime != null && MANIFEST_MIME.containsMatchIn(mime)) return true
        if (head == null || head.isEmpty()) return false
        val text = String(head, 0, minOf(head.size, HEAD_BYTES), Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimStart()
        return text.startsWith("#EXTM3U", ignoreCase = true) || MPD_HEAD.containsMatchIn(text)
    }

    /** A row of one source: the source's id and the row's track id. */
    data class Row(val source: String, val row: String)

    /** What a refusal left: the row passed over (null when its URL wasn't seen), and whether another copy may be asked for. */
    data class Verdict(val row: Row?, val refusals: Int, val next: Boolean)

    /**
     * The refusals of one app, per song, bounded to the last [cap] songs, and the rows behind the last
     * `cap * 8` stream URLs. A song is whatever key the app gives it (the PC: the video id; the phone:
     * the title and artist the resolver matches on, see [songKey]).
     *
     * A song's refused rows, URLs and count live in one entry ([Song]), so they are evicted together
     * (they were four LRU maps that drifted apart: a song whose count was evicted had its refused rows
     * ignored by [left]). The URL-to-row map is larger than the song map because every stream opened,
     * by every search, writes to it: shared at [cap], a row was often gone by the time its copy was
     * refused, so the refusal named no row and the same row was opened and refused again, up to five
     * times. The same URL refused twice for a song counts once.
     */
    class Book(private val cap: Int = 200) {
        private class Song {
            val rows = mutableSetOf<Row>()
            val urls = mutableSetOf<String>()
            var count = 0
        }

        private val rows = lru<String, Row>(cap * 8)
        private val songs = lru<String, Song>(cap)

        private fun <K, V> lru(max: Int) = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        }

        /** [url] is the stream [source]'s row [row] answered with. */
        @Synchronized
        fun opened(url: String, source: String, row: String) {
            if (url.isNotBlank()) rows[url] = Row(source, row)
        }

        /** The copy at [url] was refused for [song]: its row is passed over for the song from now on. */
        @Synchronized
        fun refuse(song: String, url: String): Verdict {
            val row = rows[url]
            val entry = songs.getOrPut(song) { Song() }
            row?.let { entry.rows += it }
            if (entry.urls.add(url)) entry.count++
            return Verdict(row, entry.count, next = entry.count <= NEXT_ROWS)
        }

        /** Whether the copy at [url] was refused for [song]. */
        @Synchronized
        fun wasRefused(song: String, url: String): Boolean = songs[song]?.urls?.contains(url) == true

        /** How many copies were refused for [song]. */
        @Synchronized
        fun refusals(song: String): Int = songs[song]?.count ?: 0

        /** Whether [song] used up its [NEXT_ROWS]: no copy is opened for it any more, what plays stays. */
        @Synchronized
        fun exhausted(song: String): Boolean = refusals(song) > NEXT_ROWS

        /** Whether [source]'s row [row] is not to be opened for [song]. */
        @Synchronized
        fun skips(song: String, source: String, row: String): Boolean =
            exhausted(song) || songs[song]?.rows?.contains(Row(source, row)) == true

        /** [rows] less the ones passed over for [song], order kept; [idOf] reads a row's track id. */
        @Synchronized
        fun <T> left(song: String, source: String, rows: List<T>, idOf: (T) -> String): List<T> =
            if (refusals(song) == 0) rows else rows.filterNot { skips(song, source, idOf(it)) }

        /**
         * An addon's `/stream` answer at [url] for [song]: when it [isManifest] (an HLS playlist or a
         * DASH manifest, by its declared transport or its URL) it is refused like a copy the fit
         * refused, so the race's next row opens (protocol.md 8.1.4 and 8.1.6: the PC's `refused` on the
         * FETCH answer). True when refused; the caller goes on to the next row. Not for other sources
         * ([isAddon] false): a module or catalogue may serve HLS/DASH as its normal answer.
         */
        @Synchronized
        fun refuseManifest(song: String, url: String, isAddon: Boolean, isManifest: Boolean): Verdict? =
            if (isAddon && isManifest) refuse(song, url) else null

        /** Forgets [song]'s refusals. */
        @Synchronized
        fun forget(song: String) {
            songs.remove(song)
        }
    }

    /**
     * The copy's window for the window check, read once more when the first read gives nothing:
     * a refusal passes the row over for the song for good, and a read cut by a network hiccup (a
     * timeout, a reset mid-window, both read as "no window") is not the copy's fault. Only a window
     * that fails twice is booked as undecodable.
     */
    inline fun <T : Any> copyWindow(read: () -> T?): T? = read() ?: read()

    /** The phone's song key: the title and artist its resolver matches on, case and spacing aside. */
    fun songKey(title: String, artist: String): String =
        title.trim().lowercase() + "\u0000" + artist.trim().lowercase()
}
