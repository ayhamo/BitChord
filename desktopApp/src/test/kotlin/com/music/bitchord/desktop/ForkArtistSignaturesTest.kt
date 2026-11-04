package com.music.bitchord.desktop

import com.music.bitchord.data.ForkAppleArtistArt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork: the phone's artist-signature rules on the PC (ForkArtistSignatures.kt). */
class ForkArtistSignaturesTest {

    @Test
    fun aKnownLicenceSvgWinsOverAVerifiedJpeg() {
        val picked = ForkArtistSignatures.parse(
            """{"signatures":[
              {"asset":{"url":"https://upload.wikimedia.org/a/X.png","format":"png","width":900,"height":300},"license":{"status":"known"},"verification":"unverified"},
              {"asset":{"url":"https://upload.wikimedia.org/b/X.svg","format":"svg","width":1000,"height":250},"license":{"status":"known"},"verification":"unverified"},
              {"asset":{"url":"https://upload.wikimedia.org/c/X.jpg","format":"jpeg","width":2000,"height":600},"license":{"status":"requires_review"},"verification":"artist_verified"}
            ]}""",
        )
        assertEquals(ForkArtistSignature("https://upload.wikimedia.org/b/X.svg", 4f, "svg"), picked)
    }

    @Test
    fun jpegsAreKeptAsThePhoneKeepsThem() {
        val picked = ForkArtistSignatures.parse("""{"signatures":[{"original_url":"https://x/s.JPG?w=1"}]}""")
        assertEquals("jpg", picked?.format)
        // No size: the phone's 2.5 default aspect.
        assertEquals(2.5f, picked?.aspect)
    }

    @Test
    fun aStringAssetAndLegacyUrlsAreRead() {
        assertEquals("https://x/a.png", ForkArtistSignatures.parse("""{"signatures":[{"asset":"https://x/a.png"}]}""")?.imageUrl)
        assertEquals("https://x/b.png", ForkArtistSignatures.parse("""{"signatures":[{"url":"https://x/b.png"}]}""")?.imageUrl)
    }

    @Test
    fun resolutionOnlyBreaksTies() {
        val picked = ForkArtistSignatures.parse(
            """{"signatures":[
              {"asset":{"url":"https://x/small.png","format":"png","width":300,"height":100}},
              {"asset":{"url":"https://x/large.png","format":"png","width":1500,"height":500}}
            ]}""",
        )
        assertEquals("https://x/large.png", picked?.imageUrl)
    }

    @Test
    fun anAmbiguousNameOrNonsenseIsNothing() {
        assertNull(ForkArtistSignatures.parse("""{"matches":[{"name":"A"},{"name":"B"}]}"""))
        assertNull(ForkArtistSignatures.parse("not json"))
        assertNull(ForkArtistSignatures.parse("""{"signatures":[{"asset":{"url":"ftp://x/a.png"}}]}"""))
    }

    @Test
    fun theAddressIsThePhones() {
        assertEquals("https://sig.example.com", ForkArtistSignatures.normalizeBaseUrl("  https://sig.example.com/// "))
        assertTrue(ForkArtistSignatures.isValidBaseUrl("http://192.168.0.5:8080"))
        assertFalse(ForkArtistSignatures.isValidBaseUrl("sig.example.com"))
        assertFalse(ForkArtistSignatures.isValidBaseUrl(""))
        assertNull(ForkArtistSignatures.effectiveBaseUrl(false, "https://sig.example.com"))
        assertNull(ForkArtistSignatures.effectiveBaseUrl(true, ""))
        assertEquals("https://sig.example.com", ForkArtistSignatures.effectiveBaseUrl(true, "https://sig.example.com/"))
        assertEquals(500, ForkArtistSignatures.normalizeBaseUrl("https://" + "a".repeat(600)).length)
    }

    @Test
    fun namesFoldCaseAccentsAndPunctuation() {
        with(ForkArtistSignatures) {
            assertEquals("beyonce", "Beyoncé".normalizedName())
            assertEquals("acdc", "AC/DC".normalizedName())
        }
    }

    @Test
    fun aScanTurnsIntoWhiteInkOnTransparency() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val pixels = intArrayOf(white, white, white, black)
        assertTrue(ForkArtistSignatures.inkPixels(pixels))
        assertEquals(0, pixels[0])
        assertEquals(0xFFFFFFFF.toInt(), pixels[3])
    }

    @Test
    fun aPhotographIsLeftAlone() {
        val grey = 0xFF404040.toInt()
        val pixels = intArrayOf(grey, grey, 0xFFFFFFFF.toInt(), grey)
        assertFalse(ForkArtistSignatures.inkPixels(pixels))
        assertEquals(grey, pixels[0])
    }

    @Test
    fun theSignatureTakesTheHeaderOnlyWithoutAnAppleLogo() {
        fun art(logo: String?) = ForkAppleArtistArt(
            heroUrl = "https://x/hero.jpg", heroAspect = 2f, logoUrl = logo, logoAspect = 3f,
            background = 0, accent = 0, keyColor = null,
        )
        assertTrue(ForkArtistSignatures.inHeader(null))
        assertTrue(ForkArtistSignatures.inHeader(art(null)))
        assertFalse(ForkArtistSignatures.inHeader(art("https://x/logo.png")))
    }
}
