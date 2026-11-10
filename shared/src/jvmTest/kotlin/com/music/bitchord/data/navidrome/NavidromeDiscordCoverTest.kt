package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Discord may be given as a presence's cover ([NavidromeDiscordCover]). */
class NavidromeDiscordCoverTest {

    private val original = NavidromeDiscordCover.sources

    @BeforeTest
    fun clean() = NavidromeDiscordCover.forget()

    @AfterTest
    fun restore() {
        NavidromeDiscordCover.sources = original
        NavidromeDiscordCover.forget()
    }

    private fun public(url: String?) = NavidromeDiscordCover.isPublic(url, serverHost = "music.example.org")

    private fun song(id: String, cover: String?, title: String = "Midnight Sun", artist: String = "Zara Larsson", duration: String? = "3:14") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = cover, durationText = duration)

    private val serverCover = "http://192.168.0.214:4533/rest/getCoverArt?id=mf-2kTq&size=600&c=BitChord&f=json"
    private val ytThumb = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
    private val apple = "https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/aa/bb/cc/source/480x480bb.jpg"

    // ---- The guard ----

    @Test
    fun `public CDN covers pass`() {
        listOf(
            ytThumb,
            "https://lh3.googleusercontent.com/abc=w480-h480-l90-rj",
            apple,
            "https://c.saavncdn.com/123/Song-500x500.jpg",
            "https://i.scdn.co/image/ab67616d0000b273abc",
            "http://example.com/cover.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=-oaymwE&rs=AOn4",
        ).forEach { assertTrue(public(it), it) }
    }

    @Test
    fun `a server cover or anything carrying a login never passes`() {
        listOf(
            serverCover,
            "https://covers.example.com/rest/getCoverArt?id=al-1", // a public host, still a Subsonic cover
            "https://covers.example.com/REST/getCoverArt.view?id=al-1",
            "https://covers.example.com/navidrome/rest/x-clip/1.mp4",
            "https://cdn.example.com/%67etCoverArt?id=1",
            "https://cdn.example.com/a.jpg?u=ayham&t=abc&s=salt",
            "https://cdn.example.com/a.jpg?t=abc",
            "https://cdn.example.com/a.jpg?s=salt",
            "https://cdn.example.com/a.jpg?p=enc:6869",
            "https://cdn.example.com/a.jpg?%75=ayham", // `u`, percent-encoded
            "https://cdn.example.com/a.jpg?U=ayham",
            "https://cdn.example.com/a.jpg?apiKey=k",
            "https://cdn.example.com/a.jpg?jwt=x",
            "https://user:pass@cdn.example.com/a.jpg",
            "https://user@cdn.example.com/a.jpg",
            "https://music.example.org/cover.jpg", // the configured server's own host
            "https://MUSIC.example.org./cover.jpg",
        ).forEach { assertFalse(public(it), it) }
    }

    @Test
    fun `private hosts, addresses and odd ports never pass`() {
        listOf(
            "http://192.168.0.214/cover.jpg",
            "http://10.0.0.5/cover.jpg",
            "http://172.16.1.1/cover.jpg",
            "http://100.88.139.51/cover.jpg", // Tailscale
            "http://127.0.0.1/cover.jpg",
            "http://8.8.8.8/cover.jpg", // any address: a public one is most likely the owner's own line
            "http://[::1]/cover.jpg",
            "http://[fd7a:115c:a1e0::1]/cover.jpg",
            "http://[2001:4860:4860::8888]/cover.jpg",
            "https://home.tail1234.ts.net/cover.jpg",
            "http://nas.local/cover.jpg",
            "http://router.lan/cover.jpg",
            "http://nas/cover.jpg",
            "http://localhost/cover.jpg",
            "https://covers.example.com:4533/cover.jpg",
            "content://media/external/audio/albumart/1",
            "file:///sdcard/cover.jpg",
            "ftp://example.com/cover.jpg",
            "not a url",
            "",
            null,
        ).forEach { assertFalse(public(it), it.toString()) }
    }

    // ---- What is sent without waiting ----

    @Test
    fun `a song outside the server keeps its own cover only when it is public`() {
        assertEquals(NavidromeDiscordCover.Answer.Ready(apple), NavidromeDiscordCover.now(song("abc123", apple), 480))
        assertEquals(NavidromeDiscordCover.Answer.Pending, NavidromeDiscordCover.now(song("webdav:1", "http://192.168.0.2/dav/cover.jpg"), 480)) // a share's own cover is never sent: looked up instead
        assertEquals(NavidromeDiscordCover.Answer.Ready(null), NavidromeDiscordCover.now(song("abc123", null), 480))
    }

    @Test
    fun `a server song with a public YouTube thumbnail needs no lookup`() {
        assertEquals(NavidromeDiscordCover.Answer.Ready(ytThumb), NavidromeDiscordCover.now(song("navidrome:1", ytThumb), 480))
    }

    @Test
    fun `a server song with the server's cover is looked up`() {
        assertEquals(NavidromeDiscordCover.Answer.Pending, NavidromeDiscordCover.now(song("navidrome:1", serverCover), 480))
        assertEquals(NavidromeDiscordCover.Answer.Pending, NavidromeDiscordCover.now(song("navidrome:1", null), 480))
    }

    // ---- The lookup ----

    @Test
    fun `the first public answer wins, a private one is skipped, and it is kept`() = runBlocking {
        val calls = AtomicInteger()
        NavidromeDiscordCover.sources = listOf(
            NavidromeDiscordCover.Source { _, _ -> calls.incrementAndGet(); "http://192.168.0.214:4533/rest/getCoverArt?id=x" },
            NavidromeDiscordCover.Source { _, _ -> calls.incrementAndGet(); apple },
            NavidromeDiscordCover.Source { _, _ -> error("not reached") },
        )
        val s = song("navidrome:7", serverCover)
        assertEquals(apple, NavidromeDiscordCover.coverFor(s, 480))
        assertEquals(apple, NavidromeDiscordCover.coverFor(s, 480))
        assertEquals(2, calls.get())
        assertEquals(NavidromeDiscordCover.Answer.Ready(apple), NavidromeDiscordCover.now(s, 480))
    }

    @Test
    fun `nothing found is kept as none`() = runBlocking {
        val calls = AtomicInteger()
        NavidromeDiscordCover.sources = listOf(NavidromeDiscordCover.Source { _, _ -> calls.incrementAndGet(); null })
        val s = song("navidrome:8", serverCover)
        assertNull(NavidromeDiscordCover.coverFor(s, 480))
        assertNull(NavidromeDiscordCover.coverFor(s, 480))
        assertEquals(1, calls.get())
        assertEquals(NavidromeDiscordCover.Answer.Ready(null), NavidromeDiscordCover.now(s, 480))
    }

    @Test
    fun `a failed lookup is not kept, so the song is asked about again`() = runBlocking {
        val calls = AtomicInteger()
        NavidromeDiscordCover.sources = listOf(NavidromeDiscordCover.Source { _, _ -> calls.incrementAndGet(); throw java.io.IOException("offline") })
        val s = song("navidrome:9", serverCover)
        assertNull(NavidromeDiscordCover.coverFor(s, 480))
        assertEquals(NavidromeDiscordCover.Answer.Pending, NavidromeDiscordCover.now(s, 480))
        assertNull(NavidromeDiscordCover.coverFor(s, 480))
        assertEquals(2, calls.get())
    }

    @Test
    fun `a slow lookup doesn't hold the presence, and its answer is there next time`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        NavidromeDiscordCover.sources = listOf(NavidromeDiscordCover.Source { _, _ -> gate.await(); apple })
        val s = song("navidrome:10", serverCover)
        val started = System.nanoTime()
        assertNull(NavidromeDiscordCover.coverFor(s, 480, waitMs = 100))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "waited too long")
        gate.complete(Unit)
        withTimeout(5_000) {
            while (NavidromeDiscordCover.now(s, 480) != NavidromeDiscordCover.Answer.Ready(apple)) delay(10)
        }
    }

    @Test
    fun `two pushes of one song share one lookup`() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        NavidromeDiscordCover.sources = listOf(NavidromeDiscordCover.Source { _, _ -> calls.incrementAndGet(); gate.await(); apple })
        val s = song("navidrome:11", serverCover)
        assertNull(NavidromeDiscordCover.coverFor(s, 480, waitMs = 50))
        gate.complete(Unit)
        assertEquals(apple, NavidromeDiscordCover.coverFor(s, 480, waitMs = 5_000))
        assertEquals(1, calls.get())
    }

    // ---- iTunes ----

    private fun row(title: String, artist: String, ms: Long?, art: String) =
        """{"trackName":"$title","artistName":"$artist",${ms?.let { "\"trackTimeMillis\":$it," } ?: ""}"artworkUrl100":"$art"}"""

    private fun body(vararg rows: String) = """{"resultCount":${rows.size},"results":[${rows.joinToString(",")}]}"""

    private val art100 = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/1/source/100x100bb.jpg"

    @Test
    fun `iTunes gives the same song's cover at the asked size`() {
        val json = body(
            row("Midnight Sun (Acoustic)", "Someone Else", 200_000, "https://is1-ssl.mzstatic.com/x/100x100bb.jpg"),
            row("Midnight Sun", "Zara Larsson", 194_000, art100),
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/1/source/480x480bb.jpg",
            NavidromeDiscordCover.pickItunes(json, song("navidrome:1", serverCover), 480),
        )
    }

    @Test
    fun `iTunes prefers the take of the same length`() {
        val json = body(
            row("Midnight Sun - Extended Mix", "Zara Larsson", 400_000, "https://is1-ssl.mzstatic.com/long/100x100bb.jpg"),
            row("Midnight Sun", "Zara Larsson & Someone", 195_000, art100),
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/1/source/600x600bb.jpg",
            NavidromeDiscordCover.pickItunes(json, song("navidrome:1", serverCover), 600),
        )
    }

    @Test
    fun `iTunes without the same title and artist gives nothing`() {
        val s = song("navidrome:1", serverCover)
        assertNull(NavidromeDiscordCover.pickItunes(body(row("Midnight Sun", "Someone Else", 194_000, art100)), s, 480))
        assertNull(NavidromeDiscordCover.pickItunes(body(row("Lush Life", "Zara Larsson", 194_000, art100)), s, 480))
        assertNull(NavidromeDiscordCover.pickItunes(body(), s, 480))
        assertNull(NavidromeDiscordCover.pickItunes("<html>rate limited</html>", s, 480))
    }

    @Test
    fun `a WebDAV or SMB song never sends its own cover, even a public-looking one`() = runBlocking {
        NavidromeDiscordCover.sources = listOf(NavidromeDiscordCover.Source { _, _ -> apple })
        val nextcloud = "https://cloud.example.com/remote.php/dav/files/ayham/Music/Album/cover.jpg"
        assertTrue(public(nextcloud)) // the guard alone can't tell: it looks like any public host
        assertEquals(apple, NavidromeDiscordCover.coverFor(song("webdav:abc", nextcloud), 480))
        assertEquals(apple, NavidromeDiscordCover.coverFor(song("smb:abc", nextcloud), 480))
        NavidromeDiscordCover.sources = listOf(NavidromeDiscordCover.Source { _, _ -> null })
        assertNull(NavidromeDiscordCover.coverFor(song("webdav:def", nextcloud), 480))
    }

    @Test
    fun `other services' login parameters never pass`() {
        listOf(
            "https://media.example.com/cover.jpg?X-Plex-Token=abc",
            "https://media.example.com/cover.jpg?x-emby-token=abc",
            "https://media.example.com/cover.jpg?key=abc",
            "https://media.example.com/cover.jpg?sig=abc",
        ).forEach { assertFalse(public(it), it) }
    }

    @Test
    fun listenButtonOpensWatchForYouTubeAndSearchOtherwise() {
        assertEquals(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            NavidromeDiscordCover.listenUrl(song("dQw4w9WgXcQ", null, title = "T", artist = "A")),
        )
        val server = NavidromeDiscordCover.listenUrl(song("navidrome:4CBCMTGnIySxbf0uZ0NuHG", null, title = "Lush Life", artist = "Zara Larsson"))
        assertEquals("https://music.youtube.com/search?q=Lush+Life+Zara+Larsson", server)
        assertFalse("navidrome" in server)
        assertEquals(
            "https://music.youtube.com/search?q=%D8%B3%D9%84%D8%A7%D9%85",
            NavidromeDiscordCover.listenUrl(song("smb:x", null, title = "سلام", artist = "")),
        )
    }
}
