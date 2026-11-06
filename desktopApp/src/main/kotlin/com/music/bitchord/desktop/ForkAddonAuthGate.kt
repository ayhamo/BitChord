package com.music.bitchord.desktop

import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: upstream v1.9.1's addon verification (`AddonAuth` on the phone) seen from the PC.
 *
 * An addon can ask for proof that a request comes from the official BitChord phone app: it
 * declares `bitchordAuth` in its manifest, or answers `401` with `WWW-Authenticate: BitChord`.
 * The proof is an Android hardware key attestation naming upstream's package and signing key,
 * so neither this fork's phone app nor any PC can give it. Upstream's phone code already skips
 * such an addon cleanly after a refusal; the PC has no verification at all and, without this,
 * sent every search to the addon, got `HTTP 401` each time, and showed the addon as ready.
 *
 * Here the first sign (the manifest flag or the challenge) marks the addon for the rest of the
 * run: its Sources row says why, and searches and streams skip it without a request, so playback
 * falls through to the next source as for any other miss. Only the manifest is still fetched, so
 * the row keeps the addon's name. A restart asks again.
 */
internal object ForkAddonAuthGate {
    const val MESSAGE = "This addon only accepts the official BitChord phone app, so it can't be used here"

    private val refused = ConcurrentHashMap.newKeySet<String>()

    fun refused(baseUrl: String): Boolean = baseUrl in refused

    fun refuse(baseUrl: String) {
        if (refused.add(baseUrl)) {
            DesktopTrackLog.log("addon ${DesktopAddonClient.redact(baseUrl)}: asks for BitChord's phone verification; skipped on the PC")
        }
    }

    /** Whether a response is the addon asking for BitChord's verification. */
    fun isChallenge(code: Int, wwwAuthenticate: String?): Boolean =
        code == 401 && wwwAuthenticate?.trimStart()?.startsWith("BitChord", ignoreCase = true) == true
}

/** An addon that needs BitChord's phone verification ([ForkAddonAuthGate]). */
internal class ForkAddonAuthRefused : DesktopAddonException(ForkAddonAuthGate.MESSAGE)
