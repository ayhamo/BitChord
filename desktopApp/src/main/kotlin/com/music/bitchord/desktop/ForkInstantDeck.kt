package com.music.bitchord.desktop

/**
 * Fork (from Desktop Revived 88dcc5d0): whether a next deck opened ahead can still be started when
 * Next lands on its song.
 *
 * A deck read in windows ([DesktopStream.windowedReads], a new request per window) or from a file has
 * no connection to go stale, so it is good however long ago it was opened, until its address says it
 * expires (`expire=` in a signed URL, with [EXPIRY_MARGIN_MS] to spare). One on FFmpeg's own HTTP
 * holds a connection a server may have dropped since, so it is only taken within [OPEN_CONNECTION_NS]
 * of its opening, as in Revived; past that the song is opened again.
 */
internal object ForkInstantDeck {
    const val OPEN_CONNECTION_NS = 60_000_000_000L
    const val EXPIRY_MARGIN_MS = 5_000L

    private val expiry = Regex("""[?&]expire=(\d+)(?:&|$)""")

    fun fresh(stream: DesktopStream, openedAtNs: Long, nowNs: Long, nowMs: Long): Boolean {
        val expiresAtMs = expiry.find(stream.url)?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
        if (expiresAtMs != null && expiresAtMs <= nowMs + EXPIRY_MARGIN_MS) return false
        if (stream.windowedReads || stream.isLocalFile) return true
        return nowNs - openedAtNs < OPEN_CONNECTION_NS
    }
}
