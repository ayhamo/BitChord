package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ForkHandoff]: the note's format, what survives, the size degradation, when it is offered and saved. */
class ForkHandoffTest {

    private val phone = ForkHandoff.Device("phone-1", "Pixel", ForkHandoff.Device.PHONE)

    private fun yt(i: Int, title: String = "Song $i") =
        ForkHandoff.Item(ForkHandoff.Item.Kind.YOUTUBE, "abcdefghi" + i.toString().padStart(2, '0'), title, "Artist", thumb = "https://i.ytimg.com/vi/x$i/hq.jpg", durationSec = 200)

    private fun note(items: List<ForkHandoff.Item>, index: Int = 0, at: Long = 1_000L, device: ForkHandoff.Device = phone) =
        ForkHandoff.Note(savedAt = at, device = device, index = index, positionMs = 134_000L, origin = ForkHandoff.Origin("Album A", "BROWSE", "MPREb_x", null), items = items)

    @Test
    fun `a note reads back as it was written`() {
        val hint = ForkHandoff.Hint(ForkHandoff.SourceRef("ADDON", "0123456789ab", "Lossless"), "track-9")
        val items = listOf(
            ForkHandoff.Item(ForkHandoff.Item.Kind.NAVIDROME, "nd1", "Midnight Sun", "Zara", album = "test", durationSec = 180, coverId = "mf-nd1_abc"),
            yt(1).copy(hint = hint, pinned = true, isVideo = true),
            ForkHandoff.Item(ForkHandoff.Item.Kind.ADDON, "track-9", "Ünïcode 日本", "A \"quoted\" one", hint = hint, sourceQuality = "LOSSLESS"),
        )
        val written = note(items, index = 1)
        val text = ForkHandoff.encode(written)
        assertNotNull(text)
        assertEquals(written, ForkHandoff.decode(text))
    }

    @Test
    fun `not a note is no note`() {
        assertNull(ForkHandoff.decode(null))
        assertNull(ForkHandoff.decode(""))
        assertNull(ForkHandoff.decode("my favourite songs"))
        assertNull(ForkHandoff.decode("""{"v":99,"at":1,"d":{"i":"x"},"q":[]}""")) // a newer build's
        assertNull(ForkHandoff.decode("""{"v":1,"d":{"i":"x"},"q":[]}""")) // no time
    }

    @Test
    fun `a big note drops thumbnails first, then narrows around the playing song`() {
        val items = (0 until 101).map { yt(it, "A fairly long song title number $it") }
        val big = note(items, index = 50)
        val full = ForkHandoff.encode(big, Int.MAX_VALUE)!!
        val fullBytes = full.toByteArray().size
        // Just under the full size: the same songs, without thumbnails.
        val noThumbs = ForkHandoff.decode(ForkHandoff.encode(big, fullBytes - 1))!!
        assertEquals(101, noThumbs.items.size)
        assertTrue(noThumbs.items.all { it.thumb == null })
        // Much smaller: fewer songs, still centred on the playing one.
        val small = ForkHandoff.decode(ForkHandoff.encode(big, 2_000))!!
        assertTrue(small.items.size < 101)
        assertEquals(items[50].id, small.current?.id)
        assertEquals(134_000L, small.positionMs)
        // Tiny: the playing song alone, or nothing at all.
        val alone = ForkHandoff.decode(ForkHandoff.encode(big, 400))
        assertEquals(listOf(items[50].id), alone?.items?.map { it.id })
        assertNull(ForkHandoff.encode(big, 50))
    }

    @Test
    fun `local files and stations are dropped, and a dropped playing song hands over to the next`() {
        val songs = listOf(
            Song("abcdefghij0", "YT", "A", null),
            Song("content://media/1", "Phone file", "A", null, localUri = "content://media/external/audio/1"),
            Song("C:\\Music\\a.flac", "PC file", "A", null, localUri = "C:\\Music\\a.flac"),
            Song("radio:5", "A station", "A", null),
            Song(NavidromeSettings.ID_PREFIX + "nd7", "Server", "A", "https://music.example.org/rest/getCoverArt?id=mf-nd7_1&size=600"),
        )
        val (items, index, position) = ForkHandoff.snapshot(songs, 1, 90_000L) { ForkHandoff.itemFor(it, { null }) }!!
        assertEquals(listOf("abcdefghij0", "nd7"), items.map { it.id })
        assertEquals(1, index) // the server song after the dropped file plays
        assertEquals(0L, position)
        assertEquals("mf-nd7_1", items[1].coverId)
        assertNull(items[1].thumb) // a server cover address never travels
    }

    @Test
    fun `a dropped playing song at the end falls back to the one before it`() {
        val songs = listOf(Song("abcdefghij0", "YT", "A", null), Song("file:///x.mp3", "File", "A", null, localUri = "file:///x.mp3"))
        val (items, index, position) = ForkHandoff.snapshot(songs, 1, 5_000L) { ForkHandoff.itemFor(it, { null }) }!!
        assertEquals(1, items.size)
        assertEquals(0, index)
        assertEquals(0L, position)
        assertNull(ForkHandoff.snapshot(listOf(songs[1]), 0, 0L) { ForkHandoff.itemFor(it, { null }) })
    }

    @Test
    fun `the window keeps fifty songs each side`() {
        val songs = (0 until 300).map { Song("abcdefg" + it.toString().padStart(4, '0'), "S$it", "A", null) }
        val (items, index, _) = ForkHandoff.snapshot(songs, 200, 0L) { ForkHandoff.itemFor(it, { null }) }!!
        assertEquals(101, items.size)
        assertEquals(50, index)
        assertEquals("S200", items[index].title)
    }

    @Test
    fun `an addon row carries its source, not its address`() {
        val ref = ForkHandoff.SourceRef("ADDON", ForkHandoff.hashBase("https://addon.example.org/SECRETKEY/"), "Lossless")
        val song = Song("src:cfg-1::t42", "Song", "A", null, sourceQuality = "LOSSLESS")
        val item = ForkHandoff.itemFor(song, { if (it.videoId.startsWith("src:")) ref to "t42" else null })!!
        assertEquals(ForkHandoff.Item.Kind.ADDON, item.kind)
        assertEquals("t42", item.id)
        val text = ForkHandoff.encode(note(listOf(item)))!!
        assertFalse("SECRETKEY" in text)
        assertFalse("addon.example.org" in text)
    }

    @Test
    fun `sources are matched by kind and address hash, then by label`() {
        val here = listOf(
            ForkHandoff.LocalSource("pc-a", "ADDON", "https://Addon.example.org/key/", "Mine"),
            ForkHandoff.LocalSource("pc-b", "ADDON", "https://other.example.org", "Lossless"),
            ForkHandoff.LocalSource("pc-j", "JIOSAAVN", null, null),
        )
        val sameAddress = ForkHandoff.SourceRef("ADDON", ForkHandoff.hashBase("https://addon.example.org/key"), null)
        assertEquals("pc-a", ForkHandoff.matchSource(sameAddress, here)?.id)
        assertEquals("pc-b", ForkHandoff.matchSource(ForkHandoff.SourceRef("ADDON", "000000000000", "lossless"), here)?.id)
        assertEquals("pc-j", ForkHandoff.matchSource(ForkHandoff.SourceRef("JIOSAAVN", null, null), here)?.id)
        assertNull(ForkHandoff.matchSource(ForkHandoff.SourceRef("MODULE", null, null), here))
        assertNull(ForkHandoff.matchSource(ForkHandoff.SourceRef("ADDON", "000000000000", "unknown"), here))
    }

    @Test
    fun `offered only when newer, from the other device, not dismissed and not stale`() {
        val n = note(listOf(yt(1)), at = 10_000L)
        val now = 20_000L
        assertTrue(ForkHandoff.shouldOffer(n, "pc-1", 5_000L, null, now))
        assertFalse(ForkHandoff.shouldOffer(n, "phone-1", 5_000L, null, now)) // this device's own
        assertFalse(ForkHandoff.shouldOffer(n, "pc-1", 10_000L, null, now)) // not newer than our save
        assertFalse(ForkHandoff.shouldOffer(n, "pc-1", 5_000L, n.key, now)) // dismissed
        assertFalse(ForkHandoff.shouldOffer(n, "pc-1", 5_000L, null, 10_000L + ForkHandoff.OFFER_MAX_AGE_MS + 1))
        assertFalse(ForkHandoff.shouldOffer(note(emptyList(), at = 10_000L), "pc-1", 0L, null, now))
        assertFalse(ForkHandoff.shouldOffer(null, "pc-1", 0L, null, now))
    }

    @Test
    fun `the mailbox playlists are hidden, others are not`() {
        assertTrue(ForkHandoff.isHiddenPlaylist(ForkHandoff.PLAYLIST_NAME))
        assertTrue(ForkHandoff.isHiddenPlaylist(ForkHandoff.PROBE_PLAYLIST_NAME))
        assertFalse(ForkHandoff.isHiddenPlaylist("bitchord-handoff"))
        assertFalse(ForkHandoff.isHiddenPlaylist("Road trip"))
        assertFalse(ForkHandoff.isHiddenPlaylist(null))
    }

    @Test
    fun `addresses this device alone can open`() {
        listOf("content://media/1", "file:///sdcard/a.mp3", "C:\\Music\\a.flac", "d:/x.mp3", "/home/me/a.ogg", "\\\\nas\\share\\a.mp3")
            .forEach { assertTrue(ForkHandoff.isLocalAddress(it), it) }
        listOf(null, "", "https://music.example.org/rest/stream?id=1", "bitchord://watch?v=x")
            .forEach { assertFalse(ForkHandoff.isLocalAddress(it), it.toString()) }
    }

    @Test
    fun `the origin comes from the playing song`() {
        val song = Song("abcdefghij0", "T", "A", null, playbackSource = "Chill mix", playbackSourceType = PlaybackSourceType.BROWSE, playbackSourceId = "VLPL1")
        assertEquals(ForkHandoff.Origin("Chill mix", "BROWSE", "VLPL1", null), ForkHandoff.originOf(song))
        assertEquals(PlaybackSourceType.BROWSE, ForkHandoff.originOf(song)?.sourceType)
        assertNull(ForkHandoff.originOf(Song("abcdefghij0", "T", "A", null)))
    }

    @Test
    fun `nothing is saved before this device has played, and a failure backs off`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = 100_000L
        val writes = mutableListOf<String>()
        var fail = false
        val saver = ForkHandoff.Saver(
            scope = scope,
            enabled = { true },
            snapshot = { note(listOf(yt(1)), at = now) },
            write = { writes += "w"; !fail },
            clock = { now },
        )
        try {
            saver.onSongChanged() // the app reopening its own queue at start
            delay(ForkHandoff.Saver.DEBOUNCE_MS + 400)
            assertTrue(writes.isEmpty())
            assertFalse(saver.armed)

            fail = true
            assertFalse(saver.save("test"))
            assertEquals(1, writes.size)
            now += 1_000 // inside the back-off: not even tried
            assertFalse(saver.save("test"))
            assertEquals(1, writes.size)
            now += ForkHandoff.Saver.BACKOFF_MS
            fail = false
            assertTrue(saver.save("test"))
            assertEquals(2, writes.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the probe's filler is the asked size in bytes`() {
        listOf(4 * 1024, 16 * 1024, 100).forEach { assertEquals(it, ForkHandoffStore.filler(it).toByteArray(Charsets.UTF_8).size) }
    }

    @Test
    fun `a comment the server won't take in a body goes in the address once`() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val error = """{"subsonic-response":{"status":"failed","error":{"code":10,"message":"required parameter 'playlistId' is missing"}}}"""
            val ok = """{"subsonic-response":{"status":"ok"}}"""
            server.enqueue(MockResponse().setBody(error))
            server.enqueue(MockResponse().setBody(ok))
            val client = NavidromeClient(server.url("/").toString(), "me", "not-a-real-password", "abc")
            assertEquals("get", client.setPlaylistComment("pl1", """{"v":1}"""))
            assertEquals("POST", server.takeRequest().method)
            val second = server.takeRequest()
            assertEquals("GET", second.method)
            assertTrue("comment=" in second.path.orEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `playlist lists leave the mailbox out`() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """{"subsonic-response":{"status":"ok","playlists":{"playlist":[
                    {"id":"1","name":"Road trip","owner":"me"},
                    {"id":"2","name":"~bitchord-handoff","owner":"me","comment":"{}"},
                    {"id":"3","name":"~bitchord-handoff","owner":"someone else","public":true}]}}}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"subsonic-response":{"status":"ok","playlists":{"playlist":[
                    {"id":"2","name":"~bitchord-handoff","owner":"me","comment":"{}"},
                    {"id":"3","name":"~bitchord-handoff","owner":"someone else","public":true}]}}}""",
                ),
            )
            val client = NavidromeClient(server.url("/").toString(), "me", "not-a-real-password", "abc")
            assertEquals(listOf("Road trip"), client.playlists().map { it.name })
            assertEquals(listOf("2"), client.handoffPlaylists(ForkHandoff.PLAYLIST_NAME).map { it.row.id })
        } finally {
            server.shutdown()
        }
    }
}
