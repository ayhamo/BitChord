package com.music.bitchord.desktop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork: the PC canvas sources' audit fixes (2026-11-05). */
class ForkCanvasSourcesTest {

    // ── Non-Latin titles ────────────────────────────────────────────────

    @Test
    fun `a Latin artist's Japanese title is not matched by another of their songs`() {
        // Both titles folded to "" with the a-z0-9 rule, so the other song's clip was taken.
        assertFalse(canvasMatches("カタオモイ", listOf("Aimer"), "残響散歌", "Aimer"))
        assertTrue(canvasMatches("残響散歌", listOf("Aimer"), "残響散歌", "Aimer"))
        val clip = DesktopCanvasArtwork(url = "u", title = "カタオモイ", artist = "Aimer")
        assertFalse(clip.matches("残響散歌", "Aimer", null))
    }

    @Test
    fun `Apple's scoring refuses a different Japanese title and a title that folds to nothing`() {
        fun song(name: String) = buildJsonObject {
            putJsonObject("attributes") {
                put("name", name)
                put("artistName", "Aimer")
                put("albumName", "Single")
            }
        }
        val other = DesktopAppleMusicCanvas.score(song("カタオモイ"), "残響散歌", "Aimer", null)
        assertNotNull(other)
        assertTrue(other < 12, "another song by the same artist stays under the floor, got $other")
        assertEquals(25, DesktopAppleMusicCanvas.score(song("残響散歌"), "残響散歌", "Aimer", null))
        assertNull(DesktopAppleMusicCanvas.score(song("?!"), "?!", "Aimer", null))
    }

    // ── Apple token: refused tokens are not picked again ──────────────────

    private val now = 1_000_000L
    private val expiries = mapOf("refused" to now + 100_000, "good" to now + 100_000, "old" to now - 1)

    @Test
    fun `a refused token is never picked again`() {
        val pick = DesktopAppleMusicCanvas.forkPickToken(
            sequenceOf("refused", "good"), setOf("refused"), now, { expiries[it] }, { it == "refused" },
        )
        assertEquals("good" to now + 100_000, pick)
    }

    @Test
    fun `a bundle with only refused or expired tokens counts as a failed scrape`() {
        assertNull(
            DesktopAppleMusicCanvas.forkPickToken(
                sequenceOf("refused", "old", "refused"), setOf("refused"), now, { expiries[it] }, { true },
            ),
        )
    }

    @Test
    fun `the web player's own token is preferred`() {
        val pick = DesktopAppleMusicCanvas.forkPickToken(
            sequenceOf("refused", "good"), emptySet(), now, { expiries[it] }, { it == "good" },
        )
        assertEquals("good", pick?.first)
    }

    /** Rule 3.1 across bundles: a later bundle's web player token beats an earlier bundle's unlabelled one, and stops the read. */
    @Test
    fun `the web player's token in a later bundle wins over an earlier unlabelled one`() {
        var read = 0
        val bundles = sequenceOf(listOf("good"), listOf("labelled"), listOf("never")).map { read++; it }
        val ex = expiries + ("labelled" to now + 50_000) + ("never" to now + 50_000)
        val pick = DesktopAppleMusicCanvas.forkPickToken(bundles.flatMap { it.asSequence() }, emptySet(), now, { ex[it] }, { it == "labelled" })
        assertEquals("labelled" to now + 50_000, pick)
        assertEquals(2, read, "the bundle after the web player's token is never fetched")
        val none = DesktopAppleMusicCanvas.forkPickToken(sequenceOf("old", "good", "refused"), emptySet(), now, { expiries[it] }, { false })
        assertEquals("good", none?.first, "no labelled token anywhere: the first unexpired one")
    }

    @Test
    fun `Spotify first asks the song's sources in the rulebook's order, saved clips first`() {
        // saved by id, Spotify (first backdrop), the video, the covers: what the client must ask in
        val asked = listOf("saved", "spotify", "video", "apple", "tidal", "community")
        assertEquals(asked, ForkSpotifyCanvasFirst.ordered(ForkSpotifyCanvasFirst.forOrdered(asked, true), spotifyFirst = true))
        // Spotify off: no Spotify in the list, and the community index is not moved to the front
        val noSpotify = listOf("saved", "video", "apple", "tidal", "community")
        assertEquals(noSpotify, ForkSpotifyCanvasFirst.ordered(ForkSpotifyCanvasFirst.forOrdered(noSpotify, true), spotifyFirst = true))
        assertEquals(asked, ForkSpotifyCanvasFirst.forOrdered(asked, spotifyFirst = false))
    }

    // ── Apple's clip: https on Apple's hosts only (audit 2026-11-26) ─────

    @Test
    fun `an untrusted Apple clip is no clip and an untrusted fallback is dropped`() {
        val good = "https://mvod.itunes.apple.com/itunes-assets/square/master.m3u8"
        val bad = listOf(
            "http://mvod.itunes.apple.com/itunes-assets/x/master.m3u8",
            "https://apple.com.evil.example/master.m3u8",
            "https://user@mvod.itunes.apple.com/itunes-assets/x/master.m3u8",
            "https://mvod.itunes.apple.com:8443/itunes-assets/x/master.m3u8",
        )
        for (url in bad) {
            var read = false
            assertNull(ForkDesktopCanvasSources.rendition(DesktopCanvasArtwork(url = url)) { read = true; null }, url)
            assertFalse(read, "no list read from $url")
            val got = ForkDesktopCanvasSources.rendition(DesktopCanvasArtwork(url = good, fallbackUrl = url)) { null }
            assertEquals(good, got?.url)
            assertNull(got?.fallbackUrl, url)
            // a saved answer read back is held to the same rule
            assertNull(ForkDesktopCanvasSources.trustedApple(DesktopCanvasArtwork(url = url)), url)
        }
    }
}
