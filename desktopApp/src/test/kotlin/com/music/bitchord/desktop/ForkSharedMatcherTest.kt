package com.music.bitchord.desktop

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PC's source lookups (addons, modules, JioSaavn, the YouTube fallback, Automix's identity and
 * AutoPlay's seed) on the phone's shared [TrackMatcher], which replaced the PC's own stale copy
 * (`DesktopTrackMatcher`).
 *
 * The first half is that copy's own test (`DesktopMatcherTest`), kept against the shared matcher so
 * nothing the PC relied on was lost in the switch. The second half is what the switch is for: the
 * copy stripped every letter outside a-z, so an Arabic, Cyrillic or CJK title had an empty core,
 * no query and no match — such a song was never even searched for on any source, and AutoPlay had
 * no seed for it. Those cases go through the PC's real call sites ([DesktopAddonSource.matches],
 * [DesktopModuleSource.matches], [DesktopAutoplay.forkSeedAmong]) against local servers.
 */
class ForkSharedMatcherTest {

    private fun target(song: Song) = TrackMatcher.targetOf(song)

    // ── Ported from DesktopMatcherTest ─────────────────────────────────────

    private val youtubeRow = Song(
        videoId = "kb_6Vz-k7Wc",
        title = "Tera Mera Rishta -  New Version (From \"Awarapan 2\")",
        artist = "Mithoon, Saaj Bhatt, Sayeed Quadri & Mustafa Zahid",
        thumbnailUrl = null,
    )

    @Test
    fun `a catalogue is asked for the title without its packaging`() {
        // The film, and the four-way credit, are not part of the question.
        assertEquals(
            listOf("tera mera rishta version mithoon", "tera mera rishta version"),
            TrackMatcher.queries(target(youtubeRow)),
        )
    }

    @Test
    fun `the catalogue listing of the same take matches a decorated YouTube title`() {
        val catalogueRow = Song(
            videoId = "jiosaavn:wfTj8NYC",
            title = "Tera Mera Rishta - New Version",
            artist = "Mithoon",
            thumbnailUrl = null,
            durationText = "6:05",
        )
        assertNotNull(TrackMatcher.best(listOf(catalogueRow), target(youtubeRow)))
    }

    @Test
    fun `a different take of the same song is still refused`() {
        // "New Version" is a take, so the plain listing is a different recording.
        val plainListing = Song("jiosaavn:other", "Tera Mera Rishta", "Mithoon", null)
        assertNull(TrackMatcher.best(listOf(plainListing), target(youtubeRow)))
    }

    @Test
    fun `the artist-first upload convention is understood`() {
        // "Artist - Title" hands the title over to the tail rather than eating it.
        val upload = Song("v", "Mithoon - Tera Mera Rishta (Official Video)", "Mithoon", null)
        assertEquals(listOf("tera mera rishta mithoon", "tera mera rishta"), TrackMatcher.queries(target(upload)))
    }

    @Test
    fun `a take is identity and has to agree both ways`() {
        val wanted = Song("v", "Song Title", "An Artist", null)
        val live = Song("a", "Song Title (Live)", "An Artist", null)
        val album = Song("b", "Song Title", "An Artist", null)

        assertNull(TrackMatcher.best(listOf(live), target(wanted)))
        assertNull(TrackMatcher.best(listOf(album), target(live)))
        assertNotNull(TrackMatcher.best(listOf(album), target(wanted)))
    }

    @Test
    fun `packaging that only looks like a take is ignored`() {
        val wanted = Song("v", "Song Title (Album Version)", "An Artist", null)
        val plain = Song("a", "Song Title", "An Artist", null)
        assertNotNull(TrackMatcher.best(listOf(plain), target(wanted)))
    }

    @Test
    fun `an upload's trailing label is not part of the title, but a title of just Song stays`() {
        assertEquals(listOf("paniyon sa"), TrackMatcher.queries(TrackMatcher.Target("Paniyon Sa Full Song")))
        assertEquals(listOf("song"), TrackMatcher.queries(TrackMatcher.Target("Song")))
    }

    @Test
    fun `only the first credited artist goes into the query`() {
        assertEquals("x mithoon", TrackMatcher.queries(TrackMatcher.Target("X", "Mithoon, Saaj Bhatt & Sayeed Quadri")).first())
        assertEquals("x arijit singh", TrackMatcher.queries(TrackMatcher.Target("X", "Arijit Singh feat. Someone")).first())
    }

    // ── What the PC gains ──────────────────────────────────────────────────

    @Test
    fun `non-Latin titles are asked for in their own script`() {
        // Each of these was an empty list on the PC: nothing was asked of any source.
        assertEquals(
            listOf("أهواك عبد الحليم حافظ", "أهواك"),
            TrackMatcher.queries(TrackMatcher.Target("أهواك", "عبد الحليم حافظ")),
        )
        assertEquals(
            listOf("группа крови кино", "группа крови"),
            TrackMatcher.queries(TrackMatcher.Target("Группа крови", "Кино")),
        )
        assertEquals(
            listOf("紅蓮華 lisa", "紅蓮華"),
            TrackMatcher.queries(TrackMatcher.Target("紅蓮華", "LiSA")),
        )
        // A kana title gets a third query in romaji, for the catalogues that file it that way.
        assertEquals(
            listOf("コイコガレ milet", "コイコガレ", "koikogare"),
            TrackMatcher.queries(TrackMatcher.Target("コイコガレ", "milet")),
        )
    }

    @Test
    fun `a non-Latin title matches its own row and only its own row`() {
        val wanted = Song("yt", "أهواك", "عبد الحليم حافظ", null, durationText = "4:00")
        val same = Song("a", "أهواك", "عبد الحليم حافظ", null, durationText = "4:01")
        val otherSong = Song("b", "قارئة الفنجان", "عبد الحليم حافظ", null, durationText = "4:00")

        assertEquals(listOf("a"), TrackMatcher.ranked(listOf(otherSong, same), target(wanted)).map { it.videoId })
    }

    @Test
    fun `a katakana title meets its romaji-filed row`() {
        val wanted = Song("yt", "コイコガレ", "milet", null, durationText = "4:10")
        val romaji = Song("a", "Koi Kogare", "milet", null, durationText = "4:10")
        assertNotNull(TrackMatcher.best(listOf(romaji), target(wanted)))
    }

    @Test
    fun `an Unknown Artist credit is a credit like any other, as on the phone`() {
        // The PC's copy dropped "Unknown Artist" before comparing credits, so any runtime within
        // 30 s matched. The phone's matcher reads it as a name: a different credit is only
        // accepted when the runtime agrees to the second. A song only the PC matched before now
        // stays on YouTube, which is the phone's answer for the same row.
        val wanted = Song("yt", "Tera Mera Rishta", "Unknown Artist", null, durationText = "6:05")
        val exact = Song("a", "Tera Mera Rishta", "Mithoon", null, durationText = "6:05")
        val drifted = Song("b", "Tera Mera Rishta", "Mithoon", null, durationText = "6:15")

        assertNotNull(TrackMatcher.best(listOf(exact), target(wanted)))
        assertNull(TrackMatcher.best(listOf(drifted), target(wanted)))
    }

    @Test
    fun `a different credit is accepted only on a runtime equal to the second, as on the phone`() {
        // The PC's copy refused any row whose credit named nobody in the target's. The phone's
        // accepts one whose runtime agrees to within 2 s (a film song filed under its composer on
        // one service and its singer on another), scored below any properly credited row; a
        // different credit with a merely close runtime is still a cover.
        val wanted = Song("yt", "Tera Mera Rishta", "Mithoon", null, durationText = "6:05")
        val sameMaster = Song("a", "Tera Mera Rishta", "Different Artist", null, durationText = "6:05")
        val cover = Song("b", "Tera Mera Rishta", "Different Artist", null, durationText = "5:50")
        val credited = Song("c", "Tera Mera Rishta", "Mithoon", null, durationText = "6:07")

        assertNotNull(TrackMatcher.best(listOf(sameMaster), target(wanted)))
        assertNull(TrackMatcher.best(listOf(cover), target(wanted)))
        assertEquals(listOf("c"), TrackMatcher.ranked(listOf(sameMaster, credited), target(wanted)).map { it.videoId })
    }

    @Test
    fun `the AutoPlay seed of a CJK module track is found on YouTube`() {
        val moduleTrack = Song("module:src::m::1", "紅蓮華", "LiSA", null, durationText = "3:58")
        val results = listOf(
            SearchResult.Track(Song("cover", "紅蓮華", "Someone Else", null, durationText = "4:30")),
            SearchResult.TopTrack(Song("right", "紅蓮華", "LiSA", null, durationText = "3:58")),
        )
        assertEquals("right", DesktopAutoplay.forkSeedAmong(results, target(moduleTrack))?.videoId)
        // Nothing that is the song: no seed, rather than a station built on the cover.
        assertNull(DesktopAutoplay.forkSeedAmong(results.take(1), target(moduleTrack)))
    }

    @Test
    fun `an addon is searched for an Arabic title and its row is matched`() = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            server.respond("/manifest.json", """{"id":"demo","name":"Demo","resources":["search","stream"]}""")
            server.createContext("/search") { exchange ->
                exchange.requestURI.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("q=") }
                    ?.let { asked += URLDecoder.decode(it.removePrefix("q="), Charsets.UTF_8) }
                exchange.send(
                    """{"tracks":[
                        {"id":"other","title":"قارئة الفنجان","artist":"عبد الحليم حافظ","duration":240.0},
                        {"id":"same","title":"أهواك","artist":"عبد الحليم حافظ","duration":241.0}]}""",
                )
            }
            val config = DesktopSourceConfig(
                "fork-matcher-addon",
                DesktopSourceKind.ADDON,
                baseUrl = "http://127.0.0.1:${server.address.port}",
            )
            val wanted = Song("yt", "أهواك", "عبد الحليم حافظ", null, durationText = "4:00")

            val matches = DesktopAddonSource.matches(config, wanted)

            assertEquals("أهواك عبد الحليم حافظ", asked.firstOrNull())
            assertEquals(listOf("same"), matches.map { DesktopAddonSource.parseTrack(it.videoId)?.trackId })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a module is searched for a Cyrillic title and its row is matched`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            val port = server.address.port
            // The module answers only a query that starts with the Cyrillic title, so a match proves
            // the title reached it (escaped, so the module's text encoding can't decide the test).
            val wantedQuery = "группа крови".escapedForJs()
            server.respond("/index.json", """{"category:music":[{"id":"fake","name":"Fake","download":"module.js"}]}""")
            server.respond(
                "/module.js",
                """
                module.exports = {
                  searchTracks: function(query) {
                    if (String(query).indexOf("$wantedQuery") !== 0) return Promise.resolve({tracks: [], total: 0});
                    return Promise.resolve({tracks: [
                      {id: "same", title: "${"Группа крови".escapedForJs()}", artist: "${"Кино".escapedForJs()}", duration: 285},
                      {id: "other", title: "${"Звезда по имени Солнце".escapedForJs()}", artist: "${"Кино".escapedForJs()}", duration: 285}
                    ], total: 2});
                  },
                  getTrackStreamUrl: function(id, quality) { return {streamUrl: "http://127.0.0.1:$port/a.mp4"}; }
                };
                """.trimIndent(),
            )
            val config = DesktopSourceConfig(
                id = "fork-matcher-module",
                kind = DesktopSourceKind.CUSTOM_MODULE,
                label = "Test source",
                baseUrl = "http://127.0.0.1:$port/index.json",
            )
            val wanted = Song("yt", "Группа крови", "Кино", null, durationText = "4:45")

            val matches = DesktopModuleSource.matches(config, wanted)

            assertEquals(1, matches.size, "matched: ${matches.map { it.title }}")
            assertTrue(matches.single().videoId.endsWith("::same"), matches.single().videoId)
        } finally {
            DesktopModuleSource.close()
            server.stop(0)
        }
    }

    private fun String.escapedForJs() = asIterable().joinToString("") { "\\u%04x".format(it.code) }

    private fun HttpServer.respond(path: String, body: String) {
        createContext(path) { it.send(body) }
    }

    private fun com.sun.net.httpserver.HttpExchange.send(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
