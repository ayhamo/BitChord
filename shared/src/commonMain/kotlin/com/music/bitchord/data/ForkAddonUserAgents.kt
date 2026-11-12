package com.music.bitchord.data

/**
 * Fork: the User-Agent an addon is called with, chosen per addon (owner, 2026-11-11).
 *
 * The Tidotido addon's developer blocks every request whose User-Agent contains "bitchord", at
 * Cloudflare and in the addon itself, on all of his addons ("anything with bitchord user agent is
 * completely banned from my addons"), and said any other agent is fine. So each addon can be told
 * to send a browser's agent instead of `BitChord/v…`. The choice is stored per addon host by the
 * phone (`ForkAddonUserAgent`) and the PC (`ForkDesktopAddonUserAgent`); this is the shared list.
 *
 * Keyed by host rather than by source id because the first request, the one that identifies the
 * addon while it is being added, has no source id yet and is refused just the same.
 */
object ForkAddonUserAgents {

    data class Preset(val id: String, val label: String, val agent: String?)

    const val BITCHORD = "bitchord"
    const val FIREFOX = "firefox"
    const val EDGE = "edge"

    /** `agent == null` is the app's own `BitChord/v…`, which each platform builds from its version. */
    val presets: List<Preset> = listOf(
        Preset(BITCHORD, "BitChord", null),
        Preset(FIREFOX, "Firefox", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:159.0) Gecko/20100101 Firefox/159.0"),
        Preset(EDGE, "Edge", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36 Edg/157.0.0.0"),
    )

    fun preset(id: String?): Preset = presets.firstOrNull { it.id == id } ?: presets.first()

    /** The agent to send for [presetId], with [appAgent] (`BitChord/v…`) for the default. */
    fun agentFor(presetId: String?, appAgent: String): String = preset(presetId).agent ?: appAgent

    /** Where a platform keeps the choice, by host; null is "never chosen" (the BitChord default). */
    interface Store {
        fun picked(host: String): String?
        fun pick(host: String, presetId: String?)
    }

    /**
     * Runs [block] (the request that identifies an addon being added) and, if it is refused with a
     * 403 while this host is still on the default agent, runs it once more as [FIREFOX] and keeps
     * that choice when it gets through. Without this an addon that bans the app's agent could never
     * be added, and so never offer the picker that would fix it. A 403 that a browser's agent
     * doesn't cure leaves the choice as it was.
     */
    suspend fun <T> retryingRefused(url: String, store: Store, block: suspend () -> Result<T>): Result<T> {
        val first = block()
        val host = hostOf(url) ?: return first
        if (first.isSuccess || first.exceptionOrNull()?.message != "HTTP 403" || store.picked(host) != null) return first
        store.pick(host, FIREFOX)
        val second = block()
        if (second.isFailure) store.pick(host, null)
        return second
    }

    /** The host an addon's choice is stored under: lower case, no port, or null for a URL with none. */
    fun hostOf(url: String): String? {
        val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@')
        // An IPv6 literal keeps its brackets; its colons are not a port.
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.lowercase().takeIf { it.isNotBlank() && it != "[]" }
    }
}
