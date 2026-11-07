package com.music.bitchord.fork

import com.music.bitchord.BuildConfig

/**
 * Fork (owner, 2026-11-07): the phone's build number, the same scheme as the PC's `ForkUpdater`.
 *
 * `.github/workflows/android.yml` replaces the release `fork-v<version>` at every push to main,
 * mostly with bug fixes under the same version, so upstream's check (version only) never told a
 * phone on the earlier build of that version; the first outside user's http fix (ayhamo/BitChord#10)
 * was such a rebuild. The workflow now passes `github.run_number` into the build
 * (`-Pbitchord.build` → `BuildConfig.FORK_BUILD`; a local build is 0) and writes a `Build: N` line
 * into the release notes, the same line the PC reads. Upstream's update logic stays as it is (the
 * dialog, the download, the install, the APK pick); only its "is this newer" gains the build:
 * offered when the version is higher, or the same with a higher build ([offers]). A release without
 * the line (all the ones before this) counts as build 0, so it is never offered over the same version.
 */
object ForkAppBuild {

    /** This build's number; 0 for a local build. */
    val current: Int get() = BuildConfig.FORK_BUILD

    /** What follows the version where Settings shows it: " (build 42)", or nothing for a local build. */
    val suffix: String get() = if (current > 0) " (build $current)" else ""

    private val BUILD_LINE = Regex("""^\W*build\W*(\d{1,9})\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * The release's build from its notes' `Build: N` line, 0 when there is none. Lenient about
     * Markdown bold, case and CRLF, but only at the start of a line, so a changelog entry that
     * mentions a build is not read as one.
     */
    fun buildOf(notes: String?): Int =
        notes?.let { BUILD_LINE.find(it.replace("\r", "")) }?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * Whether [latestVersion] / [latestBuild] is an update over [currentVersion] / [currentBuild].
     * [isNewer] is upstream's own version comparison (`AppUpdateChecker.isNewer`), so a beta and
     * its release compare exactly as before; "the same version" is neither newer than the other.
     */
    fun offers(
        latestVersion: String,
        latestBuild: Int,
        currentVersion: String,
        currentBuild: Int,
        isNewer: (latest: String, current: String) -> Boolean,
    ): Boolean {
        if (isNewer(latestVersion, currentVersion)) return true
        return !isNewer(currentVersion, latestVersion) && latestBuild > currentBuild
    }
}
