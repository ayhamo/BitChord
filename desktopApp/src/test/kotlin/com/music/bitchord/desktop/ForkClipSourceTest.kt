package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork: the cover clip saved from HLS as one file, and the minute a failed clip waits (ForkClipSource). */
class ForkClipSourceTest {

    private val master = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-STREAM-INF:BANDWIDTH=9000000,CODECS="hvc1.2.4.L123.B0",RESOLUTION=2160x2160
        hevc_2160/prog.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="avc1.640028",RESOLUTION=1080x1080
        avc_1080/prog.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1200000,CODECS="avc1.64001f",RESOLUTION=720x720
        avc_720/prog.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=500000,CODECS="avc1.64001e",RESOLUTION=480x480
        avc_480/prog.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=300000,CODECS="hvc1.2.4.L90.B0",RESOLUTION=600x600
        hevc_600/prog.m3u8
    """.trimIndent()

    @Test
    fun `a master playlist's renditions are read with their size and codecs`() {
        val variants = ForkClipHls.variants(master)
        assertEquals(5, variants.size)
        assertEquals(ForkClipHls.Variant("avc_1080/prog.m3u8", 1080, 1080, "avc1.640028", 4_000_000), variants[1])
    }

    @Test
    fun `H264 is preferred, then the smallest rendition reaching 540 px`() {
        assertEquals("avc_720/prog.m3u8", ForkClipHls.choose(ForkClipHls.variants(master))?.uri)
    }

    @Test
    fun `when no rendition reaches 540 px the largest is taken`() {
        val small = listOf(
            ForkClipHls.Variant("a", 320, 320, "avc1", 1),
            ForkClipHls.Variant("b", 480, 480, "avc1", 2),
        )
        assertEquals("b", ForkClipHls.choose(small)?.uri)
    }

    @Test
    fun `HEVC is taken when it is all there is, and a list without sizes goes by bandwidth`() {
        val hevc = listOf(ForkClipHls.Variant("h", 1080, 1080, "hvc1", 1), ForkClipHls.Variant("d", 1080, 1080, "dvh1", 1))
        assertEquals("h", ForkClipHls.choose(hevc)?.uri)
        val unsized = listOf(ForkClipHls.Variant("x", 0, 0, "", 900), ForkClipHls.Variant("y", 0, 0, "", 300))
        assertEquals("y", ForkClipHls.choose(unsized)?.uri)
        assertNull(ForkClipHls.choose(emptyList()))
    }

    @Test
    fun `a media playlist is not a master`() {
        assertTrue(ForkClipHls.variants(byteRanged).isEmpty())
    }

    private val byteRanged = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:6
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-MAP:URI="clip.mp4",BYTERANGE="1200@0"
        #EXTINF:6.0,
        #EXT-X-BYTERANGE:50000@1200
        clip.mp4
        #EXTINF:6.0,
        #EXT-X-BYTERANGE:40000
        clip.mp4
        #EXTINF:2.5,
        #EXT-X-BYTERANGE:10000
        clip.mp4
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `byte ranges resolve against the playlist, an offset left out follows the last piece`() {
        val plan = ForkClipHls.media(byteRanged, "https://cdn.example/v/avc_720/prog.m3u8?x=1")
        val uri = "https://cdn.example/v/avc_720/clip.mp4"
        assertEquals(ForkClipHls.Piece(uri, 0, 1200), plan.init)
        assertEquals(
            listOf(ForkClipHls.Piece(uri, 1200, 50_000), ForkClipHls.Piece(uri, 51_200, 40_000), ForkClipHls.Piece(uri, 91_200, 10_000)),
            plan.segments,
        )
    }

    @Test
    fun `neighbouring ranges of one file become one request`() {
        val plan = ForkClipHls.media(byteRanged, "https://cdn.example/v/prog.m3u8")
        assertEquals(listOf(ForkClipHls.Piece("https://cdn.example/v/clip.mp4", 0, 101_200)), ForkClipHls.merged(plan))
    }

    @Test
    fun `separate segment files stay separate pieces, init first`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg1.m4s
            #EXTINF:4.0,
            seg2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val plan = ForkClipHls.media(playlist, "https://cdn.example/a/prog.m3u8")
        assertEquals(
            listOf("init.mp4", "seg1.m4s", "seg2.m4s").map { ForkClipHls.Piece("https://cdn.example/a/$it") },
            ForkClipHls.merged(plan),
        )
    }

    @Test
    fun `encrypted and live playlists are left to the stream`() {
        val encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST"
        assertFailsWith<ForkClipHls.Unsupported> { ForkClipHls.media(encrypted, "https://x/p.m3u8") }
        val live = "#EXTM3U\n#EXTINF:4,\na.ts\n"
        assertFailsWith<ForkClipHls.Unsupported> { ForkClipHls.media(live, "https://x/p.m3u8") }
        val clear = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST"
        assertEquals(1, ForkClipHls.media(clear, "https://x/p.m3u8").segments.size)
    }

    @Test
    fun `a failed clip waits a minute, then is tried once more`() {
        var now = 1_000_000L
        val retry = ForkClipRetry(60_000L) { now }
        assertTrue(retry.allows("u"))
        retry.failed("u")
        assertFalse(retry.allows("u"))
        now += 59_999
        assertFalse(retry.allows("u"))
        assertTrue(retry.allows("other"), "only that address waits")
        now += 1
        assertTrue(retry.allows("u"))
        retry.failed("u")
        retry.succeeded("u")
        assertTrue(retry.allows("u"), "a success clears it")
    }
}
