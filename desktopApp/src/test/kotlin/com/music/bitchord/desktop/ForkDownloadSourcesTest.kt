package com.music.bitchord.desktop

import com.music.bitchord.data.download.ForkAudioSniff
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The PC download lookup's decision (the phone's `SourceResolver.forDownload`, be8c5d66) on made-up
 * sources: who is asked, in what order an answer counts, the budget, and the file named from bytes.
 */
class ForkDownloadSourcesTest {

    private fun flac(id: String, depth: Int = 16) =
        DesktopStream(url = "https://$id/f", format = DesktopStreamFormat(codec = "flac", bitDepth = depth, sampleRateHz = 44_100), sourceId = id)

    private fun lossy(id: String, kbps: Int?, codec: String = "mp4") =
        DesktopStream(url = "https://$id/l", format = DesktopStreamFormat(codec = codec, kbps = kbps), sourceId = id)

    private fun leg(id: String, bitExact: Boolean, open: suspend () -> DesktopStream?) =
        ForkDownloadSources.Leg(id, id, bitExact, open)

    /** Every answer kept as it is, as a probe that found the label true would. */
    private val keepAll: suspend (DesktopStream) -> ForkDownloadSources.Kept? = { ForkDownloadSources.Kept(it, null) }

    private fun pick(legs: List<ForkDownloadSources.Leg>, lossless: Boolean = true, budgetMs: Long = 5_000, keep: suspend (DesktopStream) -> ForkDownloadSources.Kept? = keepAll) =
        runBlocking { ForkDownloadSources.pick(legs, lossless, budgetMs, "Song", keep)?.stream }

    @Test
    fun `on Lossless the listener's first bit-exact source wins even when a lower one answers sooner`() {
        val first = flac("first")
        val chosen = pick(
            listOf(
                leg("first", bitExact = true) { delay(300); first },
                leg("second", bitExact = true) { flac("second", depth = 24) },
            ),
        )
        assertSame(first, chosen)
    }

    @Test
    fun `a bit-exact source that settled for lossy competes with the rest on rendition`() {
        val saavn = lossy("saavn", 320)
        val chosen = pick(
            listOf(
                leg("addon", bitExact = true) { lossy("addon", 128, "mp3") },
                leg("saavn", bitExact = false) { saavn },
            ),
        )
        assertSame(saavn, chosen)
    }

    @Test
    fun `a slow source runs out of time and what answered is still decided on`() {
        // The phone's measured case: a JioSaavn 320 in the first second went to YouTube's Opus
        // because an addon was still thinking, and the bound threw both answers away.
        val saavn = lossy("saavn", 320)
        val started = System.nanoTime()
        val chosen = pick(
            listOf(
                leg("addon", bitExact = true) { awaitCancellation() },
                leg("saavn", bitExact = false) { saavn },
            ),
            budgetMs = 300,
        )
        assertSame(saavn, chosen)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "held past its budget")
    }

    @Test
    fun `a source stuck in a call that ignores cancellation does not hold the download`() {
        val saavn = lossy("saavn", 320)
        val started = System.nanoTime()
        val chosen = pick(
            listOf(
                leg("addon", bitExact = true) { Thread.sleep(4_000); flac("addon") },
                leg("saavn", bitExact = false) { saavn },
            ),
            budgetMs = 300,
        )
        assertSame(saavn, chosen)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_500, "waited for a blocked source")
    }

    @Test
    fun `nothing lossy above YouTube's AAC sends the download to YouTube`() {
        assertNull(pick(listOf(leg("saavn", bitExact = false) { lossy("saavn", 160) })))
        // An unstated bitrate is nothing to weigh.
        assertNull(pick(listOf(leg("module", bitExact = true) { lossy("module", null, "mp3") })))
        assertNull(pick(emptyList()))
    }

    @Test
    fun `a candidate that cannot be kept never wins, and the runner-up does`() {
        val saavn = lossy("saavn", 320)
        val chosen = pick(
            listOf(
                leg("addon", bitExact = true) { flac("addon") },
                leg("saavn", bitExact = false) { saavn },
            ),
            keep = { if (it.sourceId == "addon") null else ForkDownloadSources.Kept(it, null) },
        )
        assertSame(saavn, chosen)
    }

    @Test
    fun `a source that throws is a source with nothing`() {
        val saavn = lossy("saavn", 320)
        assertSame(saavn, pick(listOf(leg("addon", bitExact = true) { error("HTTP 502") }, leg("saavn", bitExact = false) { saavn })))
    }

    @Test
    fun `on High a lossless answer waits behind any lossy copy that beats YouTube`() {
        val addonFlac = flac("addon")
        val saavn = lossy("saavn", 320)
        // Nobody is bit-exact on High: every source races on rendition.
        assertSame(saavn, pick(listOf(leg("addon", bitExact = true) { addonFlac }, leg("saavn", bitExact = false) { saavn }), lossless = false))
        // With nothing lossy above YouTube's, the FLAC beats YouTube's Opus.
        assertSame(addonFlac, pick(listOf(leg("addon", bitExact = true) { addonFlac }, leg("saavn", bitExact = false) { lossy("saavn", 160) }), lossless = false))
    }

    @Test
    fun `two equal answers go to the higher-ranked source`() {
        val first = lossy("first", 320)
        val chosen = pick(
            listOf(
                leg("first", bitExact = false) { delay(200); first },
                leg("second", bitExact = false) { lossy("second", 320) },
            ),
            lossless = false,
        )
        assertSame(first, chosen)
    }

    @Test
    fun `renditions rank as on the phone`() {
        val atmos = DesktopStream(url = "a", format = DesktopStreamFormat(codec = "eac3-joc", kbps = 768))
        assertTrue(ForkDownloadSources.isBetter(atmos, flac("x", 24)))
        assertTrue(ForkDownloadSources.isBetter(flac("x"), lossy("y", 320)))
        assertTrue(ForkDownloadSources.isBetter(flac("x", 24), flac("y", 16)))
        assertTrue(ForkDownloadSources.isBetter(lossy("x", 320), lossy("y", 256)))
        assertFalse(ForkDownloadSources.isBetter(lossy("x", 256), lossy("y", 256)))
        assertTrue(ForkDownloadSources.isBetter(lossy("x", 1), null))
        assertTrue(ForkDownloadSources.beatsYouTubeAac(DesktopStreamFormat(kbps = 320)))
        assertFalse(ForkDownloadSources.beatsYouTubeAac(DesktopStreamFormat(kbps = 256)))
        assertFalse(ForkDownloadSources.beatsYouTubeAac(DesktopStreamFormat()))
    }

    @Test
    fun `the file is described by its bytes, not its label`() {
        // An addon's "FLAC" whose bytes are an MP3, with no bitrate stated: it competes as the MP3,
        // measured from its length (8 MB over 200 s).
        val labelled = DesktopStream(url = "https://addon/x", format = DesktopStreamFormat(codec = "flac"))
        val kept = ForkDownloadSources.described(labelled, ForkAudioSniff.Probe.File(ForkAudioSniff.Container.MP3, 8_000_000), 200)
        assertEquals("mp3", kept.stream.format.codec)
        assertFalse(kept.stream.format.isLossless)
        assertEquals(320, kept.stream.format.kbps)
        assertEquals("mp3", kept.extension { "flac" })
        assertFalse(kept.manifest)

        // A container that settles no codec keeps the source's word; a stated bitrate is kept.
        val saavn = ForkDownloadSources.described(lossy("saavn", 320), ForkAudioSniff.Probe.File(ForkAudioSniff.Container.MP4, 1_000), 200)
        assertEquals("mp4", saavn.stream.format.codec)
        assertEquals(320, saavn.stream.format.kbps)
        assertEquals("m4a", saavn.extension { "ogg" })

        // A lossless file is not given a bitrate from its size.
        val real = ForkDownloadSources.described(labelled, ForkAudioSniff.Probe.File(ForkAudioSniff.Container.FLAC, 30_000_000), 200)
        assertNull(real.stream.format.kbps)
        assertEquals("flac", real.extension { "m4a" })
    }

    @Test
    fun `an extensionless index is saved as the manifest its bytes say it is`() {
        // The reference addon's HLS at /dash/{id}, labelled FLAC.
        val labelled = DesktopStream(url = "https://addon/dash/42", format = DesktopStreamFormat(codec = "flac"))
        assertFalse(DesktopManifestDownload.isManifest(labelled))
        val hls = ForkDownloadSources.described(labelled, ForkAudioSniff.Probe.Manifest(dash = false), null)
        assertTrue(hls.manifest)
        assertEquals(DesktopAddonStream.HLS, hls.stream.transport)
        assertTrue(DesktopManifestDownload.isManifest(hls.stream))
        assertEquals("flac", hls.extension { it.format.codec ?: "m4a" })

        val dash = ForkDownloadSources.described(lossy("tidal", 320, "aac"), ForkAudioSniff.Probe.Manifest(dash = true), null)
        assertEquals(DesktopAddonStream.DASH, dash.stream.transport)
        // Its segments are fMP4: anything but FLAC is assembled into M4A.
        assertEquals("m4a", dash.extension { "aac" })
    }

    @Test
    fun `with nothing read the label still names the file, as before`() {
        val unread = ForkDownloadSources.Kept(lossy("youtube", 160, "opus"), null)
        assertEquals("ogg", unread.extension { "ogg" })
        assertFalse(unread.manifest)
        val mpd = ForkDownloadSources.Kept(DesktopStream(url = "https://cdn/x.mpd"), null)
        assertTrue(mpd.manifest)
        assertEquals("m4a", mpd.extension { "aac" })
    }
}
