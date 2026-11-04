package com.music.bitchord.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which file of a GitHub release the update dialog offers on Windows (DesktopUpdateChecker.assetUrl). */
class DesktopUpdateAssetTest {

    private fun release(vararg assets: Pair<String, String>): JsonObject = Json.parseToJsonElement(
        assets.joinToString(prefix = """{"assets":[""", postfix = "]}") { (name, state) ->
            """{"name":"$name","state":"$state","browser_download_url":"https://example.invalid/$name"}"""
        },
    ).jsonObject

    private fun windows(release: JsonObject) = DesktopUpdateChecker.assetUrl(release, DesktopUpdateChecker.WINDOWS_SUFFIXES)

    @Test
    fun thePortableZipAsGitHubNamesItIsFound() {
        // An uploaded "BitChord Portable.zip" is listed with a dot for its space.
        val found = windows(release("SHA256SUMS.txt" to "uploaded", "BitChord.Portable.zip" to "uploaded"))
        assertEquals("https://example.invalid/BitChord.Portable.zip", found)
    }

    @Test
    fun theNameWithItsSpaceAndTheOlderNameAreFoundToo() {
        assertEquals("https://example.invalid/BitChord Portable.zip", windows(release("BitChord Portable.zip" to "uploaded")))
        assertEquals(
            "https://example.invalid/BitChord-1.9-windows-portable.zip",
            windows(release("BitChord-1.9-windows-portable.zip" to "uploaded")),
        )
    }

    @Test
    fun anInstallerComesBeforeTheZip() {
        val found = windows(release("BitChord.Portable.zip" to "uploaded", "BitChord-2.0-windows-x64-setup.exe" to "uploaded"))
        assertEquals("https://example.invalid/BitChord-2.0-windows-x64-setup.exe", found)
    }

    @Test
    fun aFileStillUploadingOrAPhoneOnlyReleaseOffersNothing() {
        assertNull(windows(release("BitChord.Portable.zip" to "starter")))
        assertNull(windows(release("BitChord-1.9-prod-arm64-v8a.apk" to "uploaded", "SHA256SUMS.txt" to "uploaded")))
        assertNull(windows(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals("https://example.invalid/bitchord.portable.ZIP", windows(release("bitchord.portable.ZIP" to "uploaded")))
    }

    /** A release as the list endpoint gives it: its tag, whether it is a draft, and its files. */
    private fun listed(tag: String, vararg assets: String, draft: Boolean = false): JsonObject = Json.parseToJsonElement(
        buildString {
            append("""{"tag_name":"$tag","html_url":"https://example.invalid/releases/$tag","draft":$draft,"assets":[""")
            append(assets.joinToString { name -> """{"name":"$name","state":"uploaded","browser_download_url":"https://example.invalid/$name"}""" })
            append("]}")
        },
    ).jsonObject

    private fun pcUpdate(current: String, vararg releases: JsonObject) =
        DesktopUpdateChecker.forkPcUpdate(releases.toList(), current, DesktopUpdateChecker.WINDOWS_SUFFIXES)

    @Test
    fun thePcBuildsTagIsReadAsItsVersion() {
        // `fork-` alone left "pc-v1.9", read as 0: no PC build was ever newer.
        val update = DesktopUpdateChecker.updateFrom(listed("fork-pc-v1.9", "BitChord.Portable.zip"), "1.8", DesktopUpdateChecker.WINDOWS_SUFFIXES)
        assertEquals("1.9", update?.version)
        assertEquals("https://example.invalid/BitChord.Portable.zip", update?.downloadUrl)
        assertNull(DesktopUpdateChecker.updateFrom(listed("fork-pc-v1.9", "BitChord.Portable.zip"), "1.9", DesktopUpdateChecker.WINDOWS_SUFFIXES))
    }

    @Test
    fun thePcPreReleaseIsFoundPastThePhonesReleases() {
        // The list is newest first: the phone's release (what "latest" is) comes before the PC build.
        val update = pcUpdate(
            "1.8",
            listed("v1.9", "BitChord-1.9-prod-arm64-v8a.apk"),
            listed("fork-pc-v1.9", "SHA256SUMS.txt", "BitChord.Portable.zip"),
            listed("fork-pc-v1.8", "BitChord.Portable.zip"),
        )
        assertEquals("1.9", update?.version)
        assertEquals("https://example.invalid/BitChord.Portable.zip", update?.downloadUrl)
    }

    @Test
    fun aDraftAnOlderBuildOrNoPcBuildOffersNothing() {
        assertNull(pcUpdate("1.9", listed("fork-pc-v1.9", "BitChord.Portable.zip")))
        assertNull(pcUpdate("1.8", listed("fork-pc-v1.9", "BitChord.Portable.zip", draft = true)))
        assertNull(pcUpdate("1.8", listed("v1.9", "BitChord-1.9-prod-arm64-v8a.apk")))
        assertNull(pcUpdate("1.8"))
    }
}
