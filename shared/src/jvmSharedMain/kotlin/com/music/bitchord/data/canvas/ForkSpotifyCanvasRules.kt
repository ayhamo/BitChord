package com.music.bitchord.data.canvas

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/**
 * Fork: the canvas rulebook's Spotify rules (§4), Metrolist's `DirectCanvasProvider` as the owner's
 * phone extension ports it (`SpotifyCanvas.java`): which search hit is the song, which media hosts
 * are trusted, and the anonymous web player's TOTP.
 *
 * The match replaces the fork's own Spotify check (exact title, else equal once brackets and a
 * " - Remastered" tail were cut; every artist credited). That check let "Monody (Remix)" through for
 * "Monody" and never looked at the length or the album. Metrolist's rule: title words shared over the
 * larger set at least 0.75, the same artists (or, when given, the same primary artist, so a guest
 * Spotify adds doesn't matter), lengths within 10 s and album words at least half the same, each only
 * when both sides know it. It is stricter on title tails: "Song - Remastered 2011" is not "Song".
 */
object ForkSpotifyCanvasRules {

    /** Words that say nothing about which song it is (Metrolist's `canvasNoiseWords`). */
    private val NOISE = setOf("feat", "ft", "featuring", "official", "video", "audio")

    /** NFKD, marks off, lower case, split on anything but letters and digits, [NOISE] left out. */
    fun words(value: String?): Set<String> =
        Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in NOISE }
            .toSet()

    /** Shared words over the larger set; 0 when either side has none. */
    fun similarity(a: String?, b: String?): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size / maxOf(x.size, y.size).toDouble()
    }

    /** Within this many ms the two lengths are the same recording (rule 4.4). */
    const val LENGTH_SLACK_MS = 10_000L

    /**
     * Rule 4.4: whether a found track ([foundTitle] by [foundArtist] on [foundAlbum], [foundMs]
     * long) is the song asked for. [primary] / [foundPrimary] are each side's first artist, or null
     * when not given; a length of 0 or less and a blank album are unknown and not checked.
     */
    fun matches(
        title: String, artist: String, album: String?, durationMs: Long?,
        foundTitle: String?, foundArtist: String?, foundAlbum: String?, foundMs: Long?,
        primary: String?, foundPrimary: String?,
    ): Boolean {
        val want = words(artist)
        val got = words(foundArtist)
        val wantPrimary = words(primary)
        val complete = want == got
        val primaryMatch = wantPrimary.isNotEmpty() && wantPrimary == words(foundPrimary)
        if (want.isEmpty() || !(complete || primaryMatch)) return false
        val wantMs = durationMs ?: 0L
        val gotMs = foundMs ?: 0L
        return similarity(title, foundTitle) >= 0.75 &&
            (wantMs <= 0 || gotMs <= 0 || kotlin.math.abs(wantMs - gotMs) <= LENGTH_SLACK_MS) &&
            (album.isNullOrBlank() || foundAlbum.isNullOrBlank() || similarity(album, foundAlbum) >= 0.5)
    }

    /**
     * The first artist of a YouTube Music artist line, as the phone extension cuts it (rule 4.4's
     * "primary artist"): before the first `,` `&` ` x ` `feat.` `ft.`.
     */
    fun primaryOf(artistLine: String): String =
        artistLine.split(Regex("""\s*(,|&| x | X |\bfeat\.?|\bft\.?)\s*""")).first().trim()

    /**
     * [matches] for a search hit given as its name, its artists in order, its album and length:
     * the hit's primary is its first artist, the song's the first of its artist line.
     */
    fun matchesHit(
        title: String, artist: String, album: String?, durationMs: Long?,
        hitName: String, hitArtists: List<String>, hitAlbum: String?, hitMs: Long?,
    ): Boolean = matches(
        title, artist, album, durationMs,
        hitName, hitArtists.joinToString(", "), hitAlbum, hitMs,
        primaryOf(artist), hitArtists.firstOrNull(),
    )

    /**
     * Rule 4.6 (Metrolist's `trustedMedia` for Spotify): https, port 443, no user or password, the
     * host `scdn.co` or under it.
     */
    fun trusted(url: String?): Boolean {
        if (url == null) return false
        return runCatching {
            val uri = URI(url)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) && (host == "scdn.co" || host.endsWith(".scdn.co"))
        }.getOrDefault(false)
    }

    /**
     * spotlib's web-player secret, version 61 (Totp.kt), as the phone extension ships it: the
     * obfuscated characters, each XORed with `(i % 33) + 9` and written out as decimal numbers.
     */
    const val TOTP_SECRET_V61 = ",7/*F(\"rLJ2oxaKL^f+E1xvP@N"
    const val TOTP_VERSION = 61

    /** [TOTP_SECRET_V61] as the code points [ForkSpotifyTotpToken.secretKey] takes. */
    val SECRET_V61: IntArray = TOTP_SECRET_V61.map { it.code }.toIntArray()

    /** Rule 4.1: the anonymous web player's TOTP at [nowMs]: HMAC-SHA1, 30 s steps, 6 digits. */
    fun totp(nowMs: Long): String =
        ForkSpotifyTotpToken.totp(ForkSpotifyTotpToken.secretKey(SECRET_V61), nowMs / 1000)
}
