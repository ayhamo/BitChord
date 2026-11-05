package com.music.bitchord.data.canvas

/**
 * Fork: remembers that Spotify's client token would not mint, so it isn't asked again per request.
 *
 * Upstream's `clientToken()` (phone `SpotifyToken`, PC `DesktopSpotifyToken`) is called by every
 * canvas request's headers (Pathfinder search, canvas query, canvaz-cache, REST search) and kept
 * nothing when it failed: with clienttoken.spotify.com refusing, or the web player page (the
 * session's client version) unreadable, each song sent about five POSTs (and page loads) that all
 * failed the same way. A failure is now held for [windowMs], keyed to the client id it was asked
 * for, so a different id (a new sign-in) is asked at once; the token callers clear it on sign-in
 * and Retry too. The callers go on without the header, as they always did when it failed.
 */
class ForkSpotifyClientTokenBackoff(
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var failedFor: String? = null
    private var untilMs = 0L

    /** True while a mint for [clientId] failed less than [windowMs] ago: skip asking. */
    @Synchronized
    fun blocks(clientId: String): Boolean = failedFor == clientId && now() < untilMs

    /** A mint (or the session it needs) for [clientId] failed just now. */
    @Synchronized
    fun failed(clientId: String) {
        failedFor = clientId
        untilMs = now() + windowMs
    }

    @Synchronized
    fun clear() {
        failedFor = null
        untilMs = 0L
    }

    companion object {
        const val WINDOW_MS = 10L * 60 * 1000
    }
}
