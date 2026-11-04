package com.music.bitchord.data.canvas

/**
 * Fork: the last Apple Music web-player token that worked, kept on disk, used when reading a fresh
 * one fails (music.apple.com down, its page or bundle names changed, no network at the moment of the
 * scrape). A token typed into the code would be useless: Apple's web tokens live about 35 days (the
 * YT Music Extension's baked one, issued 2026-06-09, expired 2026-07-14). Saved this way the backup
 * renews itself every time a scrape succeeds, and is never used past its own expiry.
 *
 * The phone (`AppleMusicCanvas`) and the PC (`DesktopCanvasSources`) each give it their own store;
 * neither puts it in a backup (it is Apple's public token, but a backup is no place for one).
 */
object ForkAppleTokenBackup {

    /** Where the platform keeps the one line, "<expiry ms>|<jwt>". */
    interface Store {
        fun read(): String?
        fun write(line: String?)
    }

    @Volatile var store: Store? = null

    /** A token just read from Apple, with its expiry: kept as the new backup. */
    fun save(jwt: String, expiresAtMs: Long) {
        runCatching { store?.write("$expiresAtMs|$jwt") }
    }

    /** The saved token and its expiry, when it is still good for a minute and not one Apple refused. */
    fun load(nowMs: Long, refused: Collection<String> = emptySet()): Pair<String, Long>? {
        val line = runCatching { store?.read() }.getOrNull() ?: return null
        return parse(line, nowMs)?.takeIf { it.first !in refused }
    }

    /** Apple turned [jwt] down: if it is the backup, the backup goes too. */
    fun forget(jwt: String) {
        runCatching {
            val line = store?.read() ?: return
            if (line.substringAfter('|') == jwt) store?.write(null)
        }
    }

    internal fun parse(line: String, nowMs: Long): Pair<String, Long>? {
        val expires = line.substringBefore('|', "").toLongOrNull() ?: return null
        val jwt = line.substringAfter('|', "").takeIf { it.count { c -> c == '.' } == 2 } ?: return null
        return (jwt to expires).takeIf { expires - 60_000 > nowMs }
    }
}
