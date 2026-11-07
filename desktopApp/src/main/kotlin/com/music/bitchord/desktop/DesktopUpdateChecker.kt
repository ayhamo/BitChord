package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Looks at the repo's "latest release" on GitHub once per launch and says whether it is newer
 * than the running build. The installer is not run from here: the dialog opens the asset in the
 * browser, so the user installs it the way they installed the first one.
 *
 * GitHub's "latest" skips pre-releases and drafts, so a `-beta` desktop build is only ever
 * nudged toward a published release.
 */
internal object DesktopUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        /** The installer for this platform, or null when the release has none. */
        val downloadUrl: String?,
        val notes: String?,
        val build: Int = 0, // fork: the release's build number (ForkUpdater.buildOf), 0 when its notes name none
        val sumsUrl: String? = null, // fork: the release's SHA256SUMS.txt, which the in-app update checks the zip against
    )

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/ayhamo/BitChord/releases/latest" // fork: upstream no longer builds this app

    val currentVersion: String = System.getProperty("bitchord.version") ?: "1.8-beta1"

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun check(): UpdateInfo? = forkCheck().getOrNull() // fork: forkCheck tells "up to date" from "couldn't check"

    /** fork: [check]'s body, with a failure kept, for Settings > About's "Check for updates". */
    internal suspend fun forkCheck(): Result<UpdateInfo?> = runCatching {
        val release = (if (ForkUpdater.testReleasesUrl != null) null else fetch(LATEST_RELEASE_URL)) // fork: a test list replaces GitHub
            ?.let { json.parseToJsonElement(it) as? JsonObject }
        release?.let { updateFrom(it, currentVersion, platformSuffixes()) }?.let { return@runCatching it }
        // fork: the PC's own builds are the pre-releases `fork-pc-v<version>`
        // (.github/workflows/fork-pc-release.yml), which GitHub's "latest" skips: asked by "latest"
        // alone, the PC only ever saw the fork's phone release, with no file for it, and said nothing.
        // The list is newest first; the first PC build that is newer and has the zip is the one.
        val releases = fetch(ForkUpdater.testReleasesUrl ?: RELEASES_URL)?.let { json.parseToJsonElement(it) as? JsonArray } ?: error("the release list couldn't be read") // fork: test URL; a failure, not "up to date"
        forkPcUpdate(releases.mapNotNull { it as? JsonObject }, currentVersion, platformSuffixes())
    }

    private suspend fun fetch(url: String): String? {
        val response = http.get(url) {
            header("Accept", "application/vnd.github+json")
        }
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }

    /** fork: the PC build's pre-releases, see [check]. */
    private const val RELEASES_URL = "https://api.github.com/repos/ayhamo/BitChord/releases?per_page=20"
    internal const val FORK_PC_TAG_PREFIX = "fork-pc-v"

    /** fork: the newest PC build among [releases] (GitHub's order, newest first) that is newer than [current]; drafts and the phone's releases skipped. */
    internal fun forkPcUpdate(releases: List<JsonObject>, current: String, suffixes: List<String>, currentBuild: Int = ForkUpdater.currentBuild): UpdateInfo? =
        releases.firstNotNullOfOrNull { release ->
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@firstNotNullOfOrNull null
            if (!tag.startsWith(FORK_PC_TAG_PREFIX) || release["draft"]?.jsonPrimitive?.contentOrNull == "true") return@firstNotNullOfOrNull null
            updateFrom(release, current, suffixes, currentBuild)
        }

    /** The update [release] is over [current], with a file for this platform ([suffixes]); null when it is none. */
    internal fun updateFrom(release: JsonObject, current: String, suffixes: List<String>, currentBuild: Int = ForkUpdater.currentBuild): UpdateInfo? { // fork: + currentBuild
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return null
        // fork: fork-pc-v<version> (the PC build) and fork-v<version>. `fork-` alone left "pc-v1.9",
        // whose "pc" read as 0, so a PC build was never newer than anything.
        val latest = tag.removePrefix(FORK_PC_TAG_PREFIX).removePrefix("fork-").removePrefix("v")
        val forkBuild = ForkUpdater.buildOf(release) // fork: a rebuild under the same version is offered by its build number
        if (!ForkUpdater.offers(latest, forkBuild, current, currentBuild)) return null // fork: was !isNewer(latest, current)
        // fork: a phone-only release says nothing to the PC
        val installer = assetUrl(release, suffixes) ?: return null
        return UpdateInfo(
            version = latest,
            releaseUrl = url,
            downloadUrl = installer,
            notes = release["body"]?.jsonPrimitive?.contentOrNull,
            build = forkBuild, // fork
            sumsUrl = ForkUpdater.sumsUrlOf(release), // fork
        )
    }

    /**
     * The release names its files `BitChord-<version>-windows-x64-setup.exe` and
     * `BitChord-<version>-linux-x86_64.AppImage` (see .github/workflows/release.yml); match on the
     * platform part so a version change does not matter.
     */
    private fun platformSuffixes(): List<String> = when { // fork: was installerUrl(release); the suffixes alone, for updateFrom
        DesktopPlatform.isWindows -> WINDOWS_SUFFIXES // fork: was inline, named for DesktopUpdateAssetTest
        DesktopPlatform.isLinux -> listOf(".AppImage", "-linux-amd64.deb")
        else -> emptyList()
    }

    /** fork: the portable zip (GitHub uploads its space as a dot), then the older zip's name. */
    internal val WINDOWS_SUFFIXES = listOf("-windows-x64-setup.exe", "BitChord Portable.zip", "BitChord.Portable.zip", "-windows-portable.zip")

    /** fork: split out of [installerUrl], so the matching is testable on any platform. */
    internal fun assetUrl(release: JsonObject, suffixes: List<String>): String? {
        val assets = release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        for (suffix in suffixes) {
            val hit = assets.firstOrNull { asset ->
                asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(suffix, ignoreCase = true) == true &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
            }
            hit?.get("browser_download_url")?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        return null
    }

    private class Parsed(val parts: List<Int>, val preRelease: Boolean)

    private fun parse(raw: String): Parsed {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return Parsed(base.split('.').map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /** Numeric comparison, with a `-betaN` build counted as older than the plain release it leads up to. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.preRelease && !l.preRelease
    }
}
