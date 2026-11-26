package com.music.bitchord.fork

import androidx.media3.common.ParserException
import com.music.bitchord.data.sources.ForkAddonRefusals
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Addon rulebook 8.1.5 rules 5 and 6 on the phone: an answer announced as HLS/DASH plays (from its own
 * URL, not through the cache); one announced as a file that is a manifest on the wire is refused and
 * the next row opens.
 */
class ForkManifestSniffTest {

    private val hlsHead = ForkManifestSniff.Head("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray(), "application/octet-stream")
    private val flacHead = ForkManifestSniff.Head("fLaC\u0000\u0000\u0000\"".toByteArray(), "audio/flac")

    private fun target(title: String) = TrackMatcher.Target(title, "Artist", 200).also {
        ForkNextRowPhone.book.forget(ForkAddonRefusals.songKey(it.title, it.artist))
    }

    @Test
    fun anAnnouncedManifestIsPlayedNotRefused() = runBlocking {
        val t = target("Announced")
        val declared = "https://addon.example/dash/123"
        StreamContainer.declare(declared, "hls")
        var asked = false
        assertFalse(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream(declared)) { asked = true; hlsHead })
        assertFalse(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream("https://cdn.example/a.m3u8")) { asked = true; hlsHead })
        assertFalse(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream("https://cdn.example/a.mpd?x=1")) { asked = true; hlsHead })
        assertFalse("an announced manifest costs no head request", asked)
    }

    @Test
    fun aFileThatIsAManifestOnTheWireIsRefusedAndTheNextRowOpens() = runBlocking {
        val t = target("Unannounced")
        assertTrue(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream("https://cdn.example/x.flac")) { hlsHead })
        assertTrue(ForkNextRowPhone.book.wasRefused(ForkAddonRefusals.songKey(t.title, t.artist), "https://cdn.example/x.flac"))
        // By Content-Type alone, too.
        assertTrue(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream("https://cdn.example/y")) { ForkManifestSniff.Head(ByteArray(0), "application/dash+xml") })
        // Audio, or no answer to the head request: not refused (the player's guards stand behind).
        assertFalse(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream("https://cdn.example/z.flac")) { flacHead })
        assertFalse(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream("https://cdn.example/w.flac")) { null })
    }

    /** A link the head probe stopped at a redirect off https or to this phone is refused, never left to the player (8.1.5 rule 5). */
    @Test
    fun aLinkThatRedirectsToABlockedHopIsRefused() = runBlocking {
        val t = target("Blocked hop")
        assertTrue(ForkNextRowPhone.refusesManifest(t, "Addon", true, SourceStream("https://cdn.example/r.flac")) { ForkManifestSniff.Head(null, null, blocked = true) })
        assertTrue(ForkNextRowPhone.book.wasRefused(ForkAddonRefusals.songKey(t.title, t.artist), "https://cdn.example/r.flac"))
    }

    @Test
    fun aModulesManifestIsNotTheAddonRulebooks() = runBlocking {
        val t = target("Module")
        assertFalse(ForkNextRowPhone.refusesManifest(t, "Tidal", false, SourceStream("https://cdn.example/x")) { hlsHead })
    }

    @Test
    fun anAnnouncedManifestOpenedAsAFileFailsBeforeAnyByteIsCached() {
        try {
            ForkManifestSniff.refuseAnnouncedAsFile("bitchord://watch?v=abcdefghijk", "https://cdn.example/a.m3u8")
            fail("a progressive open of a manifest must fail")
        } catch (e: ParserException) {
            assertFalse(e.contentIsMalformed) // the progressive extractor's "unsupported container", which replayAsManifest answers
        }
        // The declared reopen and a plain audio stream pass.
        ForkManifestSniff.refuseAnnouncedAsFile("bitchord://watch?v=abcdefghijk&manifest_reopen=hls", "https://cdn.example/a.m3u8")
        ForkManifestSniff.refuseAnnouncedAsFile("bitchord://watch?v=abcdefghijk", "https://cdn.example/a.flac")
        // Not a player item (a segment, a variant playlist): not this check's.
        ForkManifestSniff.refuseAnnouncedAsFile("https://cdn.example/v.m3u8", "https://cdn.example/v.m3u8")
    }

    @Test
    fun theHeadIsReadThenServedBackWhole() {
        val body = ByteArray(2_000) { (it % 251).toByte() }
        var at = 0
        val source = { b: ByteArray, o: Int, l: Int ->
            if (at >= body.size) -1 else minOf(l, 100, body.size - at).also { n -> System.arraycopy(body, at, b, o, n); at += n }
        }
        val head = ForkManifestSniff.readHead(source)
        assertEquals(ForkManifestSniff.HEAD_BYTES, head.size)
        val held = ForkManifestSniff.Held().apply { hold(head) }
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(333)
        while (true) {
            val n = held.read(buf, 0, buf.size, source)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        assertArrayEquals(body, out.toByteArray())
        assertEquals(3, ForkManifestSniff.readHead { b, o, _ -> if (at == -5) -1 else { b[o] = 1; b[o + 1] = 2; b[o + 2] = 3; at = -5; 3 } }.size)
    }

    @Test
    fun onlyAnUnannouncedManifestIsCaseTwo() {
        assertTrue(ForkManifestSniff.isUnannouncedManifest(false, hlsHead))
        assertFalse(ForkManifestSniff.isUnannouncedManifest(true, hlsHead))
        assertFalse(ForkManifestSniff.isUnannouncedManifest(false, flacHead))
        assertFalse(ForkManifestSniff.isUnannouncedManifest(false, null))
    }
}
