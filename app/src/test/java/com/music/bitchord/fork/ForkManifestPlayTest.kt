package com.music.bitchord.fork

import com.music.bitchord.fork.ForkManifestCheck.Got
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An accepted addon manifest as the player reads it: the check's pick, one DASH Representation, redirects judged. */
class ForkManifestPlayTest {

    @After
    fun tearDown() = ForkManifestPlay.clear()

    private val base = "https://cdn.example/s/"

    private val media = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXTINF:10,
        https://seg.example/a/seg1.aac
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun anAcceptedMasterIsServedTheMediaPlaylistTheCheckChose() {
        val m = listOf(
            "#EXTM3U",
            "#EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS=\"ec-3\"", "dolby.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=320000,CODECS=\"mp4a.40.2\"", "aac.m3u8",
        ).joinToString("\n")
        val pages = mapOf("${base}master.m3u8" to m, "${base}aac.m3u8" to media, "${base}dolby.m3u8" to media)
        val why = runBlocking {
            ForkManifestCheck.check("${base}master.m3u8", false, ForkManifestPlay::accepted) { u -> pages[u]?.let { Got.Page(u, it) } ?: Got.Unreadable }
        }
        assertNull(why)
        assertEquals("the AAC variant, never Media3's choice", "${base}aac.m3u8", ForkManifestPlay.pickFor("${base}master.m3u8"))
        assertTrue("the segments' host is read through the judged client", ForkManifestPlay.covers("https://seg.example/a/seg9.aac"))
        assertTrue(ForkManifestPlay.covers("${base}aac.m3u8"))
        assertFalse("any other host goes on as before", ForkManifestPlay.covers("https://rr1.googlevideo.com/videoplayback"))
        assertFalse(ForkManifestPlay.isMpd("${base}master.m3u8"))
    }

    @Test
    fun aRefusedManifestIsNotRemembered() {
        val live = "#EXTM3U\n#EXTINF:10,\nseg1.aac\n"
        runBlocking { ForkManifestCheck.check("${base}live.m3u8", false, ForkManifestPlay::accepted) { u -> Got.Page(u, live) } }
        assertFalse(ForkManifestPlay.covers("${base}seg1.aac"))
        assertNull(ForkManifestPlay.pickFor("${base}live.m3u8"))
    }

    private val mixedMpd = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT3M">
          <Period>
            <AdaptationSet contentType="video" mimeType="video/mp4">
              <Representation id="v1" bandwidth="2000000" codecs="avc1.4d401f"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4">
              <Representation id="ec3" bandwidth="768000" codecs="ec-3"/>
              <Representation id="aac-hi" bandwidth="256000" codecs="mp4a.40.2"/>
              <Representation id="aac-lo" bandwidth="128000" codecs="mp4a.40.2"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    private fun reps(mpd: String) = Regex("""<(?:\w+:)?Representation[^>]*id="([^"]+)"""").findAll(mpd).map { it.groupValues[1] }.toList()

    @Test
    fun anMpdKeepsOnlyThePcsPick() {
        val plain = ForkManifestPlay.keepOne(mixedMpd, dolbyOk = false)
        assertNotNull(plain)
        assertEquals("the best that isn't Dolby, and nothing else", listOf("aac-hi"), reps(plain!!))
        assertTrue("its namespace kept", plain.contains("urn:mpeg:dash:schema:mpd:2011"))
        assertEquals("with Atmos wanted, the best of all", listOf("ec3"), reps(ForkManifestPlay.keepOne(mixedMpd, dolbyOk = true)!!))
    }

    @Test
    fun aDolbyOnlyMpdWithoutAtmosIsLeftAsItIs() {
        val dolbyOnly = mixedMpd.replace("""<Representation id="aac-hi" bandwidth="256000" codecs="mp4a.40.2"/>""", "")
            .replace("""<Representation id="aac-lo" bandwidth="128000" codecs="mp4a.40.2"/>""", "")
        assertNull(ForkManifestPlay.keepOne(dolbyOnly, dolbyOk = false))
        assertNull(ForkManifestPlay.keepOne("not xml", dolbyOk = false))
    }

    @Test
    fun aRedirectIsJudgedBeforeItIsFollowed() {
        val from = "https://seg.example/a/seg1.aac".toHttpUrl()
        assertTrue(ForkManifestPlay.hopAllowed(from, "https://cdn2.example/seg1.aac"))
        assertTrue("relative, still https", ForkManifestPlay.hopAllowed(from, "/b/seg1.aac"))
        assertFalse("plain http", ForkManifestPlay.hopAllowed(from, "http://cdn2.example/seg1.aac"))
        assertFalse("this phone", ForkManifestPlay.hopAllowed(from, "https://localhost/seg1.aac"))
        assertFalse("all of 127/8", ForkManifestPlay.hopAllowed(from, "https://127.0.0.2/seg1.aac"))
        assertFalse("IPv4-mapped loopback", ForkManifestPlay.hopAllowed(from, "https://[::ffff:127.0.0.1]/seg1.aac"))
        assertFalse("no Location", ForkManifestPlay.hopAllowed(from, null))
    }
}
