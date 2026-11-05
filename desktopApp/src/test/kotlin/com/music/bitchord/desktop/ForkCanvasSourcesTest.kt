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
}
