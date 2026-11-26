package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.ForkAddonStreamAssemble.Fetch
import com.music.bitchord.desktop.ForkAddonStreamAssemble.Kind
import com.music.bitchord.desktop.ForkAddonStreamAssemble.Limits
import com.music.bitchord.desktop.ForkAddonStreamAssemble.Refused
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: an addon row announced as HLS or DASH put together into one file on the PC (YT Music
 * Extension `spec/addons/protocol.md` 8.1.5 rule 5 and rule 6.1): the parser, the picks, the refusals
 * and the limits.
 */
class ForkAddonStreamAssembleTest {

    private val dir = Files.createTempDirectory("fork-stream-assemble").toFile()
    private val out = File(dir, "out.part")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun refusal(block: () -> Unit): String = assertFailsWith<Refused> { block() }.message.orEmpty()

    // ── HLS picks ────────────────────────────────────────────────────────

    @Test
    fun `a master playlist picks the best audio rendition, passing over Dolby`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="lo",NAME="aac",DEFAULT=YES,URI="lo/aac.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="hi",NAME="aac",DEFAULT=NO,URI="hi/aac.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="atmos",NAME="atmos",CODECS="ec-3",URI="atmos/ec3.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2",AUDIO="lo"
            lo/v.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS="mp4a.40.2",AUDIO="hi"
            hi/v.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=768000,CODECS="ec-3",AUDIO="atmos"
            atmos/v.m3u8
        """.trimIndent()
        assertEquals("hi/aac.m3u8", ForkAddonStreamAssemble.pickHls(master))
    }

    @Test
    fun `with no renditions the best variant is picked, audio-only first, then by bandwidth`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1280x720
            video.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=96000,CODECS="mp4a.40.5"
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=320000,CODECS="mp4a.40.2"
            high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS="ec-3"
            dolby.m3u8
        """.trimIndent()
        assertEquals("high.m3u8", ForkAddonStreamAssemble.pickHls(master))
    }

    @Test
    fun `a master playlist with only Dolby, or a session key, is refused`() {
        assertTrue("Dolby" in refusal {
            ForkAddonStreamAssemble.pickHls("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"ec-3\"\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2,CODECS=\"ac-4\"\nb.m3u8")
        })
        assertEquals("encrypted", refusal {
            ForkAddonStreamAssemble.pickHls("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8")
        })
    }

    // ── HLS media playlists ──────────────────────────────────────────────

    @Test
    fun `a media playlist reads its init, its segments and their byte ranges`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-KEY:METHOD=NONE
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="700@0"
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@700
            all.mp4
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            all.mp4
            #EXTINF:2,
            seg/3.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val plan = ForkAddonStreamAssemble.hlsPlan(playlist, "https://cdn.example/a/b/media.m3u8")
        assertEquals(Kind.MP4, plan.kind)
        assertEquals(ForkAddonStreamAssemble.Ref("https://cdn.example/a/b/init.mp4", 0L..699L), plan.init)
        assertEquals(
            listOf(
                ForkAddonStreamAssemble.Ref("https://cdn.example/a/b/all.mp4", 700L..1699L),
                ForkAddonStreamAssemble.Ref("https://cdn.example/a/b/all.mp4", 1700L..2199L),
                ForkAddonStreamAssemble.Ref("https://cdn.example/a/b/seg/3.m4s", null),
            ),
            plan.media,
        )
        // no init: the kind is told from the first segment's bytes
        assertNull(ForkAddonStreamAssemble.hlsPlan("#EXTM3U\n#EXTINF:4,\n1.ts\n#EXT-X-ENDLIST", "https://c.example/m.m3u8").kind)
    }

    @Test
    fun `an encrypted, live or twice-initialised media playlist is refused`() {
        assertEquals("encrypted", refusal {
            ForkAddonStreamAssemble.hlsPlan("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\n1.ts\n#EXT-X-ENDLIST", "https://c.example/m.m3u8")
        })
        assertEquals("encrypted", refusal {
            ForkAddonStreamAssemble.hlsPlan("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:4,\n1.ts\n#EXT-X-ENDLIST", "https://c.example/m.m3u8")
        })
        assertEquals("live", refusal { ForkAddonStreamAssemble.hlsPlan("#EXTM3U\n#EXTINF:4,\n1.ts\n#EXTINF:4,\n2.ts", "https://c.example/m.m3u8") })
        assertEquals("more than one init segment", refusal {
            ForkAddonStreamAssemble.hlsPlan(
                "#EXTM3U\n#EXT-X-MAP:URI=\"a.mp4\"\n#EXTINF:4,\n1.m4s\n#EXT-X-MAP:URI=\"b.mp4\"\n#EXTINF:4,\n2.m4s\n#EXT-X-ENDLIST",
                "https://c.example/m.m3u8",
            )
        })
    }

    // ── DASH ─────────────────────────────────────────────────────────────

    private fun mpd(body: String, attrs: String = "type=\"static\" mediaPresentationDuration=\"PT10S\"") =
        "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" $attrs>$body</MPD>"

    @Test
    fun `DASH picks the best audio Representation, passing over Dolby, and fills a numbered template`() {
        val manifest = mpd(
            """
            <BaseURL>https://cdn.example/track/</BaseURL>
            <Period>
              <AdaptationSet contentType="video"><Representation id="v" bandwidth="9000000" codecs="avc1"/></AdaptationSet>
              <AdaptationSet mimeType="audio/mp4">
                <SegmentTemplate initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/${'$'}Number%05d${'$'}.m4s" duration="4" timescale="1" startNumber="1"/>
                <Representation id="aac128" bandwidth="128000" codecs="mp4a.40.2"/>
                <Representation id="flac" bandwidth="900000" codecs="flac"/>
                <Representation id="atmos" bandwidth="768000" codecs="ec-3"/>
              </AdaptationSet>
              <AdaptationSet mimeType="audio/mp4"><Representation id="dolby" bandwidth="2000000" codecs="ac-4"/></AdaptationSet>
            </Period>
            """,
        )
        val plan = ForkAddonStreamAssemble.dashPlan(manifest, "https://api.example/m.mpd")
        assertEquals("https://cdn.example/track/flac/init.mp4", plan.init?.url)
        assertEquals(listOf("00001", "00002", "00003").map { "https://cdn.example/track/flac/$it.m4s" }, plan.media.map { it.url })
        assertEquals(Kind.MP4, plan.kind)
    }

    @Test
    fun `DASH reads a timeline with Time and repeats, a segment list, and a lone BaseURL`() {
        val timeline = mpd(
            """
            <Period><AdaptationSet mimeType="audio/mp4">
              <Representation id="a" bandwidth="256000" codecs="mp4a.40.2">
                <SegmentTemplate timescale="1000" initialization="i-${'$'}Bandwidth${'$'}.mp4" media="s-${'$'}Time${'$'}.m4s">
                  <SegmentTimeline><S t="0" d="4000" r="1"/><S d="2000"/></SegmentTimeline>
                </SegmentTemplate>
              </Representation>
            </AdaptationSet></Period>
            """,
        )
        val plan = ForkAddonStreamAssemble.dashPlan(timeline, "https://c.example/x/m.mpd")
        assertEquals("https://c.example/x/i-256000.mp4", plan.init?.url)
        assertEquals(listOf("s-0", "s-4000", "s-8000").map { "https://c.example/x/$it.m4s" }, plan.media.map { it.url })

        val list = mpd(
            """
            <Period><AdaptationSet mimeType="audio/mp4"><Representation id="a" bandwidth="1">
              <BaseURL>file.mp4</BaseURL>
              <SegmentList><Initialization range="0-99"/><SegmentURL mediaRange="100-199"/><SegmentURL mediaRange="200-299"/></SegmentList>
            </Representation></AdaptationSet></Period>
            """,
        )
        val listed = ForkAddonStreamAssemble.dashPlan(list, "https://c.example/m.mpd")
        assertEquals(ForkAddonStreamAssemble.Ref("https://c.example/file.mp4", 0L..99L), listed.init)
        assertEquals(listOf(100L..199L, 200L..299L), listed.media.map { it.range })

        val lone = mpd("<Period><AdaptationSet mimeType=\"audio/mp4\"><Representation id=\"a\" bandwidth=\"1\"><BaseURL>https://c.example/whole.m4a</BaseURL><SegmentBase indexRange=\"0-10\"/></Representation></AdaptationSet></Period>")
        val whole = ForkAddonStreamAssemble.dashPlan(lone, "https://c.example/m.mpd")
        assertEquals(Kind.WHOLE, whole.kind)
        assertEquals(listOf("https://c.example/whole.m4a"), whole.media.map { it.url })
    }

    @Test
    fun `a live, encrypted, multi-Period or Dolby-only DASH manifest is refused`() {
        val audio = "<AdaptationSet mimeType=\"audio/mp4\"><Representation id=\"a\" bandwidth=\"1\"><BaseURL>https://c.example/a.m4a</BaseURL></Representation></AdaptationSet>"
        assertEquals("live", refusal { ForkAddonStreamAssemble.dashPlan(mpd("<Period>$audio</Period>", "type=\"dynamic\""), "https://c.example/m.mpd") })
        assertEquals("encrypted", refusal {
            ForkAddonStreamAssemble.dashPlan(mpd("<Period><AdaptationSet mimeType=\"audio/mp4\"><ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\"/><Representation id=\"a\" bandwidth=\"1\"/></AdaptationSet></Period>"), "https://c.example/m.mpd")
        })
        assertEquals("more than one Period", refusal { ForkAddonStreamAssemble.dashPlan(mpd("<Period>$audio</Period><Period>$audio</Period>"), "https://c.example/m.mpd") })
        assertEquals("Dolby only", refusal {
            ForkAddonStreamAssemble.dashPlan(mpd("<Period><AdaptationSet mimeType=\"audio/mp4\"><Representation id=\"a\" bandwidth=\"1\" codecs=\"ec-3\"/></AdaptationSet></Period>"), "https://c.example/m.mpd")
        })
        // a DOCTYPE (entities) is never read
        assertEquals("not a DASH manifest", refusal {
            ForkAddonStreamAssemble.dashPlan("<?xml version=\"1.0\"?><!DOCTYPE MPD [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><MPD>&x;</MPD>", "https://c.example/m.mpd")
        })
    }

    // ── Putting it together ──────────────────────────────────────────────

    private fun box(type: String, payload: ByteArray = ByteArray(8) { 7 }): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    /** A fake network: [files] by URL, a [Fetch] that serves ranges and counts what runs at once. */
    private class Net(val files: Map<String, ByteArray>, val wait: Long = 0) {
        val running = AtomicInteger()
        var most = 0
        val asked = ArrayList<String>()
        val fetch = Fetch { url, range, limit ->
            synchronized(this) { asked += url }
            val now = running.incrementAndGet()
            synchronized(this) { most = maxOf(most, now) }
            try {
                if (wait > 0) delay(wait)
                val bytes = files[url] ?: throw Refused("a segment it can't read (HTTP 404)")
                val part = range?.let { bytes.copyOfRange(it.first.toInt(), it.last.toInt() + 1) } ?: bytes
                if (part.size > limit) throw Refused("over the size limit")
                part
            } finally {
                running.decrementAndGet()
            }
        }
    }

    @Test
    fun `an fMP4 HLS stream is put together from its master playlist, init first, each segment's styp dropped`() = runBlocking {
        val init = box("ftyp") + box("moov")
        val segments = (1..6).map { box("moof", ByteArray(8) { i -> (it + i).toByte() }) + box("mdat", ByteArray(16) { _ -> it.toByte() }) }
        val files = mutableMapOf(
            "https://c.example/master.m3u8" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS=\"mp4a.40.2\"\naudio/media.m3u8".toByteArray(),
            "https://c.example/audio/media.m3u8" to (
                "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n" + (1..6).joinToString("") { "#EXTINF:4,\n$it.m4s\n" } + "#EXT-X-ENDLIST"
                ).toByteArray(),
            "https://c.example/audio/init.mp4" to init,
        )
        segments.forEachIndexed { i, seg -> files["https://c.example/audio/${i + 1}.m4s"] = box("styp") + seg }
        val net = Net(files, wait = 20)
        val extension = ForkAddonStreamAssemble.assemble("https://c.example/master.m3u8", "hls", out, net.fetch)
        assertEquals("m4a", extension)
        assertContentEquals(segments.fold(init) { acc, s -> acc + s }, out.readBytes())
        assertTrue(net.most in 2..4, "segments are fetched at most 4 at a time (${net.most})")
    }

    // MPEG-TS: one PAT, one PMT, the audio PES over two packets

    private fun packet(pid: Int, start: Boolean, payload: ByteArray): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((if (start) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x10
        payload.copyInto(p, 4, 0, minOf(payload.size, 184))
        return p
    }

    private fun ts(streamType: Int, es: ByteArray): ByteArray {
        val pat = byteArrayOf(0, 0x00, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0x00, 0, 0, 0, 0)
        val sectionLength = 9 + 5 + 4
        val pmt = byteArrayOf(
            0, 0x02, (0xB0 or (sectionLength shr 8)).toByte(), sectionLength.toByte(), 0, 1, 0xC1.toByte(), 0, 0,
            0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
            streamType.toByte(), 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00,
            0, 0, 0, 0,
        )
        val first = es.copyOfRange(0, 170)
        val pes = byteArrayOf(0, 0, 1, 0xC0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5, 0x21, 0, 1, 0, 1) + first
        val rest = es.copyOfRange(170, 354)
        return packet(0, true, pat) + packet(0x1000, true, pmt) + packet(0x101, true, pes) + packet(0x101, false, rest)
    }

    private fun adts(seed: Int) = ByteArray(354) { (seed + it).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xF1.toByte() }

    @Test
    fun `MPEG-TS segments give their ADTS audio, and a codec it can't take out is refused`() = runBlocking {
        val a = adts(1)
        val b = adts(2)
        val playlist = "#EXTM3U\n#EXTINF:4,\n1.ts\n#EXTINF:4,\n2.ts\n#EXT-X-ENDLIST"
        val net = Net(mapOf("https://c.example/m.m3u8" to playlist.toByteArray(), "https://c.example/1.ts" to ts(0x0F, a), "https://c.example/2.ts" to ts(0x0F, b)))
        assertEquals("aac", ForkAddonStreamAssemble.assemble("https://c.example/m.m3u8", "hls", out, net.fetch))
        assertContentEquals(a + b, out.readBytes())

        val ac3 = Net(mapOf("https://c.example/m.m3u8" to playlist.toByteArray(), "https://c.example/1.ts" to ts(0x81, a), "https://c.example/2.ts" to ts(0x81, b)))
        assertTrue("codec" in assertFailsWith<Refused> { ForkAddonStreamAssemble.assemble("https://c.example/m.m3u8", "hls", out, ac3.fetch) }.message.orEmpty())
        assertFalse(out.exists(), "a refused stream leaves no file")
    }

    @Test
    fun `packed MP3 segments are joined without their ID3 tags`() = runBlocking {
        val frame1 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 1, 2, 3)
        val frame2 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 4, 5, 6)
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 3, 9, 9, 9)
        val net = Net(
            mapOf(
                "https://c.example/m.m3u8" to "#EXTM3U\n#EXTINF:4,\n1.mp3\n#EXTINF:4,\n2.mp3\n#EXT-X-ENDLIST".toByteArray(),
                "https://c.example/1.mp3" to id3 + frame1,
                "https://c.example/2.mp3" to id3 + frame2,
            ),
        )
        assertEquals("mp3", ForkAddonStreamAssemble.assemble("https://c.example/m.m3u8", "hls", out, net.fetch))
        assertContentEquals(frame1 + frame2, out.readBytes())
    }

    @Test
    fun `a link off https or to this machine is refused before it is fetched`() = runBlocking {
        assertFalse(ForkAddonStreamAssemble.linkAllowed("http://c.example/a.m3u8"))
        assertFalse(ForkAddonStreamAssemble.linkAllowed("https://localhost/a.m3u8"))
        assertFalse(ForkAddonStreamAssemble.linkAllowed("https://127.0.0.2/a.m3u8"))
        assertFalse(ForkAddonStreamAssemble.linkAllowed("https://[::1]/a.m3u8"))
        assertFalse(ForkAddonStreamAssemble.linkAllowed("https://user:pw@c.example/a.m3u8"))
        assertTrue(ForkAddonStreamAssemble.linkAllowed("https://c.example/a.m3u8"))
        val net = Net(
            mapOf(
                "https://c.example/m.m3u8" to "#EXTM3U\n#EXTINF:4,\nhttp://c.example/1.ts\n#EXT-X-ENDLIST".toByteArray(),
                "https://c.example/n.m3u8" to "#EXTM3U\n#EXTINF:4,\nhttps://localhost:8080/1.ts\n#EXT-X-ENDLIST".toByteArray(),
            ),
        )
        assertFailsWith<Refused> { ForkAddonStreamAssemble.assemble("https://c.example/m.m3u8", "hls", out, net.fetch) }
        assertFailsWith<Refused> { ForkAddonStreamAssemble.assemble("https://c.example/n.m3u8", "hls", out, net.fetch) }
        assertEquals(listOf("https://c.example/m.m3u8", "https://c.example/n.m3u8"), net.asked, "no segment was asked for")
    }

    @Test
    fun `over the byte limit or the time limit the stream is refused and its file deleted`() = runBlocking {
        val files = mutableMapOf("https://c.example/m.m3u8" to ("#EXTM3U\n" + (1..8).joinToString("") { "#EXTINF:4,\n$it.mp3\n" } + "#EXT-X-ENDLIST").toByteArray())
        (1..8).forEach { files["https://c.example/$it.mp3"] = byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(998) }
        assertFailsWith<Refused> { ForkAddonStreamAssemble.assemble("https://c.example/m.m3u8", "hls", out, Net(files).fetch, Limits(maxBytes = 4_000)) }
        assertFalse(out.exists())
        val slow = Net(files, wait = 200)
        val why = assertFailsWith<Refused> { ForkAddonStreamAssemble.assemble("https://c.example/m.m3u8", "hls", out, slow.fetch, Limits(totalMs = 300)) }.message.orEmpty()
        assertTrue("within" in why, why)
        assertFalse(out.exists())
        // within both, it is put together
        assertEquals("mp3", ForkAddonStreamAssemble.assemble("https://c.example/m.m3u8", "hls", out, Net(files).fetch, Limits(maxBytes = 9_000)))
        assertEquals(8_000L, out.length())
    }

    // ── openBest's hook ──────────────────────────────────────────────────

    private val song = Song(videoId = "assembledPc01", title = "Assembled", artist = "X", thumbnailUrl = null, durationText = "3:20")
    private val addon = DesktopSourceConfig("addon-hls", DesktopSourceKind.ADDON)

    @Test
    fun `an announced row is put together for the decks, a refused one is refused like a bad fit, fetched again every time`() = runBlocking {
        val book = ForkNextRowPc.book
        book.forget(song.videoId)
        val url = "https://a.example/s/hls-row.m3u8"
        val stream = ForkNextRowPc.asAnnounced(DesktopStream(url = url))
        ForkNextRowPc.opened(url, addon.id, "row1")
        var puts = 0
        assertTrue(ForkNextRowPc.assembles(song, addon, stream) { transport, file -> puts++; assertEquals("hls", transport); file.writeBytes(ByteArray(4)); "m4a" })
        val first = assertNotNull(ForkAssembledStreams.local(url))
        assertTrue(ForkNextRowPc.assembles(song, addon, stream) { _, file -> puts++; file.writeBytes(ByteArray(8)); "m4a" })
        val second = assertNotNull(ForkAssembledStreams.local(url))
        assertEquals(2, puts, "never cached: each resolve puts it together again")
        assertTrue(first != second && !File(first).exists(), "the older file is dropped")

        val refusedUrl = "https://a.example/s/live.m3u8"
        ForkNextRowPc.opened(refusedUrl, addon.id, "row2")
        assertFalse(ForkNextRowPc.assembles(song, addon, ForkNextRowPc.asAnnounced(DesktopStream(url = refusedUrl))) { _, _ -> throw Refused("live") })
        assertTrue(book.wasRefused(song.videoId, refusedUrl))
        assertNull(ForkAssembledStreams.local(refusedUrl))

        // a progressive row, or not an addon's: nothing done
        assertTrue(ForkNextRowPc.assembles(song, addon, DesktopStream(url = "https://a.example/f.flac")) { _, _ -> error("not asked") })
        assertTrue(ForkNextRowPc.assembles(song, DesktopSourceConfig("yt", DesktopSourceKind.YOUTUBE), stream) { _, _ -> error("not asked") })
        book.forget(song.videoId)
        ForkAssembledStreams.clear()
    }

    @Test
    fun `every spelling of this machine is refused before any connection`() = runBlocking {
        val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val accepted = AtomicInteger()
        val acceptor = Thread {
            runCatching { while (true) socket.accept().use { accepted.incrementAndGet() } }
        }.apply { isDaemon = true; start() }
        try {
            val port = socket.localPort
            val fetch = ForkAddonStreamAssemble.http(emptyMap(), Limits(requestMs = 2_000))
            for (url in listOf("https://2130706433:$port/a.ts", "https://127.1:$port/a.ts", "https://[::ffff:127.0.0.1]:$port/a.ts", "https://0x7f000001:$port/a.ts")) {
                assertFalse(ForkAddonStreamAssemble.linkAllowed(url), url)
                assertFailsWith<Refused>(url) { fetch.get(url, null, 1024) }
            }
            assertEquals(0, accepted.get(), "no connection was made")
        } finally {
            socket.close()
            acceptor.join(1_000)
        }
    }

    /** [read] of one answer from a local server: [status], [contentRange] and [body]. */
    private fun answer(status: Int, contentRange: String?, body: ByteArray, range: LongRange?): ForkAddonStreamAssemble.Answer {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            contentRange?.let { exchange.responseHeaders.add("Content-Range", it) }
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url("http://127.0.0.1:${server.address.port}/s.ts").build()
            return client.newCall(request).execute().use { ForkAddonStreamAssemble.read(it, range, 1_000_000) }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a byte range answered from the wrong offset or short is refused`() {
        val whole = ByteArray(100) { it.toByte() }
        // 206 from another offset than asked.
        assertFailsWith<Refused> { answer(206, "bytes 0-9/100", whole.copyOfRange(0, 10), 10L..19L) }
        // 206 that stops before the range's last byte while the resource goes on.
        assertFailsWith<Refused> { answer(206, "bytes 10-14/100", whole.copyOfRange(10, 15), 10L..19L) }
        // 206 whose body is shorter than its own Content-Range.
        assertFailsWith<Refused> { answer(206, "bytes 10-19/100", whole.copyOfRange(10, 15), 10L..19L) }
        // 206 with no Content-Range.
        assertFailsWith<Refused> { answer(206, null, whole.copyOfRange(10, 20), 10L..19L) }
        // 200 shorter than the range's first byte.
        assertFailsWith<Refused> { answer(200, null, whole.copyOfRange(0, 5), 10L..19L) }
        // The right answers still read.
        val ok206 = answer(206, "bytes 10-19/100", whole.copyOfRange(10, 20), 10L..19L) as ForkAddonStreamAssemble.Answer.Body
        assertContentEquals(whole.copyOfRange(10, 20), ok206.bytes)
        val ok200 = answer(200, null, whole, 10L..19L) as ForkAddonStreamAssemble.Answer.Body
        assertContentEquals(whole.copyOfRange(10, 20), ok200.bytes)
        val end206 = answer(206, "bytes 95-99/100", whole.copyOfRange(95, 100), 95L..120L) as ForkAddonStreamAssemble.Answer.Body
        assertContentEquals(whole.copyOfRange(95, 100), end206.bytes)
    }
}
