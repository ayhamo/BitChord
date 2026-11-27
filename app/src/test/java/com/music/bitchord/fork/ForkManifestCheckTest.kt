package com.music.bitchord.fork

import com.music.bitchord.data.sources.ForkAddonRefusals
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.fork.ForkManifestCheck.Got
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rulebook 8.1.5 rule 5 (3dce391): an addon's HLS/DASH answer read once, and its refusals. */
class ForkManifestCheckTest {

    private val base = "https://cdn.example/s/"

    private fun check(pages: Map<String, String>, url: String = "${base}master.m3u8", dolbyOk: Boolean = false): Pair<String?, List<String>> {
        val asked = mutableListOf<String>()
        val why = runBlocking {
            ForkManifestCheck.check(url, dolbyOk) { u -> asked += u; pages[u]?.let { Got.Page(u, it) } ?: Got.Unreadable }
        }
        return why to asked
    }

    private val media = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:10,
        seg1.m4s
        #EXTINF:10,
        seg2.m4s
        #EXT-X-ENDLIST
    """.trimIndent()

    private fun master(vararg lines: String) = (listOf("#EXTM3U") + lines).joinToString("\n")

    @Test
    fun aPlainHlsSongPlaysAndOnlyThePickedMediaPlaylistIsRead() {
        val m = master(
            "#EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS=\"avc1.4d401f,mp4a.40.2\"", "video.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"", "low.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=320000,CODECS=\"mp4a.40.2\"", "high.m3u8",
        )
        val (why, asked) = check(mapOf("${base}master.m3u8" to m, "${base}high.m3u8" to media))
        assertNull(why)
        assertEquals("audio-only first, then the best BANDWIDTH", listOf("${base}master.m3u8", "${base}high.m3u8"), asked)
        // A media playlist handed over directly is checked as it is.
        assertNull(check(mapOf("${base}master.m3u8" to media)).first)
    }

    @Test
    fun theBestAudioRenditionIsTheOneRead() {
        val m = master(
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",DEFAULT=YES,URI=\"audio/en.m3u8\"",
            "#EXT-X-STREAM-INF:BANDWIDTH=500000,CODECS=\"mp4a.40.2\",AUDIO=\"a\"", "v.m3u8",
        )
        val (why, asked) = check(mapOf("${base}master.m3u8" to m, "${base}audio/en.m3u8" to media))
        assertNull(why)
        assertEquals("${base}audio/en.m3u8", asked.last())
    }

    @Test
    fun encryptedLiveAndSplitInitsAreRefused() {
        val keyed = media.replace("#EXT-X-MAP", "#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example/k\"\n#EXT-X-MAP")
        assertEquals(ForkManifestCheck.ENCRYPTED, check(mapOf("${base}master.m3u8" to keyed)).first)
        val sampleAes = media.replace("#EXT-X-MAP", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXT-X-MAP")
        assertEquals(ForkManifestCheck.ENCRYPTED, check(mapOf("${base}master.m3u8" to sampleAes)).first)
        val none = media.replace("#EXT-X-MAP", "#EXT-X-KEY:METHOD=NONE\n#EXT-X-MAP")
        assertNull(check(mapOf("${base}master.m3u8" to none)).first)
        val session = master("#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"", "#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"mp4a.40.2\"", "a.m3u8")
        assertEquals(ForkManifestCheck.ENCRYPTED, check(mapOf("${base}master.m3u8" to session, "${base}a.m3u8" to media)).first)

        assertEquals(ForkManifestCheck.LIVE, check(mapOf("${base}master.m3u8" to media.replace("#EXT-X-ENDLIST", ""))).first)

        val twoInits = media.replace("seg2.m4s", "seg2.m4s\n#EXT-X-MAP:URI=\"init2.mp4\"\n#EXTINF:10,\nseg3.m4s")
        assertEquals(ForkManifestCheck.INITS, check(mapOf("${base}master.m3u8" to twoInits)).first)
    }

    @Test
    fun dolbyOnlyIsRefusedUnlessTheAtmosPathWantsIt() {
        val dolby = master(
            "#EXT-X-STREAM-INF:BANDWIDTH=768000,CODECS=\"ec-3\"", "atmos.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=640000,CODECS=\"ac-3\"", "ac3.m3u8",
        )
        val pages = mapOf("${base}master.m3u8" to dolby, "${base}atmos.m3u8" to media)
        assertEquals(ForkManifestCheck.DOLBY_ONLY, check(pages).first)
        assertNull(check(pages, dolbyOk = true).first)
        // A stereo choice beside the Dolby ones: played, and the stereo one is read.
        val mixed = dolby + "\n#EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS=\"mp4a.40.2\"\nstereo.m3u8"
        val (why, asked) = check(mapOf("${base}master.m3u8" to mixed, "${base}stereo.m3u8" to media))
        assertNull(why)
        assertEquals("${base}stereo.m3u8", asked.last())
    }

    @Test
    fun linksOffHttpsOrToThisMachineAreRefused() {
        val off = ForkManifestCheck.OFF_LIMITS
        assertEquals(off, check(mapOf("${base}master.m3u8" to media.replace("seg2.m4s", "http://cdn.example/seg2.m4s"))).first)
        assertEquals(off, check(mapOf("${base}master.m3u8" to media.replace("seg2.m4s", "https://127.0.0.1/seg2.m4s"))).first)
        assertEquals(off, check(mapOf("${base}master.m3u8" to media.replace("init.mp4", "https://localhost:8080/init.mp4"))).first)
        val toPhone = master("#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"mp4a.40.2\"", "https://[::1]/a.m3u8")
        assertEquals(off, check(mapOf("${base}master.m3u8" to toPhone)).first)
        // The playlist's own address, and a redirect on the way (the fetch says so).
        assertEquals(off, check(emptyMap(), url = "http://cdn.example/a.m3u8").first)
        assertEquals(off, runBlocking { ForkManifestCheck.check("${base}a.m3u8", false) { Got.OffLimits } })
        assertTrue(ForkManifestCheck.thisMachine("127.8.0.1"))
        assertTrue(ForkManifestCheck.thisMachine("0.0.0.0"))
        assertTrue(ForkManifestCheck.thisMachine("api.localhost"))
        assertFalse(ForkManifestCheck.thisMachine("cdn.example"))
        assertFalse(ForkManifestCheck.thisMachine("192.168.1.5"))
    }

    @Test
    fun anUnreadableListIsRefused() {
        val u = ForkManifestCheck.UNREADABLE
        assertEquals(u, check(emptyMap()).first) // not fetched
        assertEquals(u, check(mapOf("${base}master.m3u8" to "fLaC....")).first) // not a list
        val master = master("#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"mp4a.40.2\"", "a.m3u8")
        assertEquals(u, check(mapOf("${base}master.m3u8" to master)).first) // its media playlist won't load
        assertEquals(u, check(mapOf("${base}master.m3u8" to "#EXTM3U\n#EXT-X-ENDLIST")).first) // no segment
        // protocol.md 8.1.5 (2026-11-27): an init segment with no link, and a manifest that throws while read, are refusals
        assertEquals(u, check(mapOf("${base}master.m3u8" to media.replace("#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-MAP:BYTERANGE=\"100@0\""))).first)
        val deep = "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\">" + "<a>".repeat(200_000) + "</a>".repeat(200_000) + "</MPD>"
        assertEquals(u, check(mapOf("${base}master.mpd" to deep), url = "${base}master.mpd").first)
    }

    private fun mpd(body: String, type: String = "static") =
        """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type">$body</MPD>"""

    private val audioPeriod = """
        <Period><BaseURL>https://cdn.example/d/</BaseURL>
          <AdaptationSet contentType="audio" mimeType="audio/mp4">
            <Representation id="a" codecs="flac" bandwidth="900000">
              <SegmentTemplate initialization="init-${'$'}RepresentationID${'$'}.mp4" media="seg-${'$'}Number${'$'}.m4s" startNumber="1" duration="10" timescale="1"/>
            </Representation>
          </AdaptationSet>
        </Period>
    """.trimIndent()

    @Test
    fun dashIsReadOnceAndRefusedByTheSameRules() {
        val url = "${base}a.mpd"
        fun dash(text: String, dolbyOk: Boolean = false) = check(mapOf(url to text), url, dolbyOk)
        val (why, asked) = dash(mpd(audioPeriod))
        assertNull(why)
        assertEquals(listOf(url), asked)
        assertEquals(ForkManifestCheck.LIVE, dash(mpd(audioPeriod, type = "dynamic")).first)
        assertEquals(ForkManifestCheck.PERIODS, dash(mpd(audioPeriod + audioPeriod)).first)
        val protectedSet = audioPeriod.replace("<Representation", "<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/><Representation")
        assertEquals(ForkManifestCheck.ENCRYPTED, dash(mpd(protectedSet)).first)
        val ec3 = audioPeriod.replace("codecs=\"flac\"", "codecs=\"ec-3\"")
        assertEquals(ForkManifestCheck.DOLBY_ONLY, dash(mpd(ec3)).first)
        assertNull(dash(mpd(ec3), dolbyOk = true).first)
        val plainBase = audioPeriod.replace("https://cdn.example/d/", "http://cdn.example/d/")
        assertEquals(ForkManifestCheck.OFF_LIMITS, dash(mpd(plainBase)).first)
        val localMedia = audioPeriod.replace("media=\"seg-", "media=\"https://127.0.0.1/seg-")
        assertEquals(ForkManifestCheck.OFF_LIMITS, dash(mpd(localMedia)).first)
        assertEquals(ForkManifestCheck.UNREADABLE, dash(mpd("<Period><AdaptationSet contentType=\"video\"/></Period>")).first)
        assertEquals(ForkManifestCheck.UNREADABLE, dash("<MPD><broken").first)
    }

    @Test
    fun aRefusedAnnouncedStreamIsPassedOverAndTheNextRowOpens() = runBlocking {
        val t = TrackMatcher.Target(title = "Checked", artist = "Z", durationSec = null)
        val url = "https://cdn.example/live.m3u8"
        assertTrue(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream(url), { ForkManifestCheck.LIVE }) { null })
        assertTrue(ForkNextRowPhone.book.wasRefused(ForkAddonRefusals.songKey(t.title, t.artist), url))
        assertFalse(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream("https://cdn.example/ok.m3u8"), { null }) { null })
    }
}
