package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fork: the PC's reading of an Apple Music artist page and the iTunes search (ForkAppleArtistArt.kt). */
class ForkAppleArtistArtTest {

    private fun page(header: String) = """
        <html><head></head><body>
        <script type="application/json" id="serialized-server-data">[{"data":{"sections":[{"items":[$header]}]}}]</script>
        </body></html>
    """.trimIndent()

    private val heroOnly = """
        {"artistLogo":null,
         "artwork":{"dictionary":{"url":"https://is1-ssl.mzstatic.com/a/{w}x{h}{c}.{f}","width":2400,"height":1200,"bgColor":"1a2b3c","textColor1":"f0e0d0"}},
         "keyColor":"ff3355"}
    """.trimIndent()

    @Test
    fun readsTheHeroAndColours() {
        val art = ForkAppleArtistArtRepository.parseArtistPage(page(heroOnly))!!
        assertEquals("https://is1-ssl.mzstatic.com/a/1200x600bb.jpg", art.heroUrl)
        assertEquals(2f, art.heroAspect)
        assertNull(art.logoUrl)
        assertEquals(1f, art.logoAspect)
        assertEquals(0xFF1A2B3C.toInt(), art.background)
        assertEquals(0xFFF0E0D0.toInt(), art.accent)
        assertEquals(0xFFFF3355.toInt(), art.keyColor)
    }

    @Test
    fun readsTheLogoAtItsOwnAspect() {
        val header = """
            {"artistLogo":{"dictionary":{"url":"https://x/logo/{w}x{h}{c}.{f}","width":800,"height":200}},
             "artwork":{"dictionary":{"url":"https://x/hero/{w}x{h}{c}.{f}","width":1200,"height":600,"bgColor":"000000"}}}
        """.trimIndent()
        val art = ForkAppleArtistArtRepository.parseArtistPage(page(header))!!
        assertEquals("https://x/logo/1000x250bb.png", art.logoUrl)
        assertEquals(4f, art.logoAspect)
        // No textColor1: the accent is the background; no keyColor: none.
        assertEquals(art.background, art.accent)
        assertNull(art.keyColor)
    }

    @Test
    fun fallsBackToTheColourBackdrop() {
        val header = """
            {"artistLogo":null,"colorBackdropArtwork":{"dictionary":{"url":"https://x/{w}x{h}{c}.{f}","width":100,"height":100,"bgColor":"ABCDEF"}}}
        """.trimIndent()
        assertEquals("https://x/1200x1200bb.jpg", ForkAppleArtistArtRepository.parseArtistPage(page(header))?.heroUrl)
    }

    @Test
    fun anythingMissingIsNoArt() {
        assertNull(ForkAppleArtistArtRepository.parseArtistPage("<html>nothing</html>"))
        assertNull(ForkAppleArtistArtRepository.parseArtistPage(page("""{"name":"no header here"}""")))
        // No colour, no size, no template: each is a miss rather than a half-drawn header.
        assertNull(ForkAppleArtistArtRepository.parseArtistPage(page(heroOnly.replace("\"1a2b3c\"", "\"red\""))))
        assertNull(ForkAppleArtistArtRepository.parseArtistPage(page(heroOnly.replace("\"width\":2400", "\"width\":0"))))
        assertNull(ForkAppleArtistArtRepository.parseArtistPage(page(heroOnly.replace("{w}x{h}{c}.{f}", "fixed.jpg"))))
        assertNull(ForkAppleArtistArtRepository.parseArtistPage(page("{broken json")))
    }

    @Test
    fun candidatesAreTheExactNameOnAppleMusicOnly() {
        val search = """
            {"results":[
              {"artistName":"Krsna Das","artistId":1,"artistLinkUrl":"https://music.apple.com/us/artist/krsna-das/1?uo=4"},
              {"artistName":"KR${'$'}NA","artistId":2,"artistLinkUrl":"https://music.apple.com/us/artist/kr-na/2?uo=4"},
              {"artistName":"Krsna","artistId":3},
              {"artistName":"krsna","artistId":4,"artistLinkUrl":"https://example.com/elsewhere"},
              {"artistName":"Krsna","artistId":0}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(
                2L to "https://music.apple.com/us/artist/kr-na/2",
                3L to "https://music.apple.com/us/artist/3",
            ),
            ForkAppleArtistArtRepository.candidates(search, AppleCatalogue.matchKey("Krsna")),
        )
        assertEquals(emptyList(), ForkAppleArtistArtRepository.candidates("not json", "krsna"))
    }
}
