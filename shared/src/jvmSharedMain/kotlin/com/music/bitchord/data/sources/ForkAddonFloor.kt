package com.music.bitchord.data.sources

import java.util.Locale

/**
 * Fork: the rulebook's lossy floor and the pass after a copy plays (YT Music Extension
 * `spec/addons/protocol.md` 8.2.1, a6322d5; `pick.js` qualityRank, floorVerdict, upgradeWanted and
 * their `pick-cases.json` groups), shared by the phone and the PC.
 *
 *  - [qualityRank]: 0 lossy, 1 lossless up to 16-bit/48 kHz, 2 Hi-Res (over 16-bit or over 48 kHz). A
 *    lossless tier that names a lossy codec is lossy (an addon labelling a 320 kbps AAC "LOSSLESS").
 *  - [floorVerdict]: an addon copy replaces YouTube's only when it is lossless, or lossy at
 *    [FLOOR_KBPS] or more over what YouTube plays ([YT_KBPS_USUAL], the usual Opus 251, when that
 *    isn't known); a lossy copy that states no bitrate is refused.
 *  - [upgradeWanted]: once a copy plays, one pass: a lossy copy, when the tier asked is the top one,
 *    every addon again for lossless or better; a copy that won because it was cached and is below the
 *    tier asked, only the addons ranked above the one it came from, for better than it. A fresh
 *    lossless copy gets no pass, whatever its depth.
 *
 * The tier names are the rulebook's: `LOSSLESS` (its "Best") and `HIRES` want Hi-Res, `CD` wants
 * lossless, anything else (`HIGH`, `LOW`) wants nothing more. BitChord's top request (the phone's
 * `StreamRequest.Lossless`, the PC's `LOSSLESS` tier) is the rulebook's `LOSSLESS`: it is the tier
 * that sends the addon's quality pick (8.4.2), i.e. the best the addon has.
 */
object ForkAddonFloor {

    const val FLOOR_KBPS = 96
    const val YT_KBPS_USUAL = 160

    const val TIER_BEST = "LOSSLESS"
    const val TIER_LOWER = "HIGH"

    /** What a stream says about itself, as the rulebook reads an opened stream (`st`). */
    data class Facts(
        val codec: String? = null,
        val tier: String? = null,
        val bitDepth: Int? = null,
        val sampleRate: Int? = null,
        val bitrate: Int? = null,
    )

    /** The pass [upgradeWanted] asks for. */
    data class Pass(val aboveOnly: Boolean, val minRank: Int)

    private val LOSSLESS_CODEC = Regex("flac|alac|wav|pcm|aiff?", RegexOption.IGNORE_CASE)
    private val LOSSY_CODEC = Regex("mp3|mpeg|aac|mp4a|opus|vorbis|ogg", RegexOption.IGNORE_CASE)

    fun qualityRank(st: Facts): Int {
        val c = st.codec.orEmpty()
        val lossless = LOSSLESS_CODEC.containsMatchIn(c) ||
            (st.tier.equals("LOSSLESS", ignoreCase = true) && !LOSSY_CODEC.containsMatchIn(c))
        if (!lossless) return 0
        return if ((st.bitDepth ?: 0) > 16 || (st.sampleRate ?: 0) > 48_000) 2 else 1
    }

    /** null: the copy passes; else why not, in the rulebook's words. */
    fun floorVerdict(st: Facts, ytKbps: Int?): String? {
        if (qualityRank(st) >= 1) return null
        val yt = ytKbps?.takeIf { it > 0 } ?: YT_KBPS_USUAL
        val b = st.bitrate?.takeIf { it > 0 } ?: return "lossy, bitrate unknown"
        return if (b >= yt + FLOOR_KBPS) null else "lossy $b kbps, not $FLOOR_KBPS kbps over YouTube's $yt"
    }

    fun upgradeWanted(st: Facts, tier: String?, cached: Boolean): Pass? {
        val r = qualityRank(st)
        val want = when (tier?.uppercase(Locale.ROOT)) {
            "LOSSLESS", "HIRES" -> 2
            "CD" -> 1
            else -> 0
        }
        if (r >= want) return null
        if (cached) return Pass(aboveOnly = true, minRank = r + 1)
        return if (r == 0) Pass(aboveOnly = false, minRank = 1) else null
    }

    // ── BitChord's own stream descriptions ─────────────────────────────

    /**
     * A [StreamFormat] as the rulebook reads it. A Dolby Atmos copy is lossy by codec (its rank is 0)
     * but passes the floor on the phone and BitChord ([passesFloor]): it is the immersive mix the
     * listener chose, not a bitrate rung (protocol.md 8.2.1, "or Atmos, phone only").
     */
    fun factsOf(format: StreamFormat): Facts = Facts(
        codec = format.codec,
        tier = if (format.isLossless == true) "LOSSLESS" else null,
        bitDepth = format.bitDepth,
        sampleRate = format.sampleRateHz,
        // An inferred figure is the answer's own words (HIGH → 320), which the rulebook reads too.
        bitrate = format.kbps,
    )

    fun rankOf(format: StreamFormat): Int = qualityRank(factsOf(format))

    /** The floor for a [StreamFormat]: Atmos passes, else [floorVerdict]. */
    fun passesFloor(format: StreamFormat, ytKbps: Int?): Boolean =
        format.isDolbyAtmos || floorVerdict(factsOf(format), ytKbps) == null

    /** Why [format] is refused by the floor, or null; for the log line. */
    fun refusal(format: StreamFormat, ytKbps: Int?): String? =
        if (format.isDolbyAtmos) null else floorVerdict(factsOf(format), ytKbps)

    /**
     * Whether a copy found by the pass [pass] may replace [playing]: higher in the quality order than
     * the pass asked for (an Atmos copy too, when it isn't what plays; the swap checks still run).
     */
    fun passAccepts(pass: Pass, candidate: StreamFormat, playing: StreamFormat?): Boolean {
        if (playing?.isDolbyAtmos == true) return false
        if (candidate.isDolbyAtmos) return true
        return rankOf(candidate) >= pass.minRank
    }
}

/**
 * Fork: the rulebook's read-ahead (protocol.md 8.1.3, user decision 2026-11-20): the next song's addon
 * `/stream` call and its audio are fetched only from [LEAD_MS] before the transition, the planned
 * mix-out when Automix has one, else the song's end. Searching (manifest, search, pick) may run
 * earlier; a `/stream` may not, so skipping through a queue never spends an addon's (TorBox's)
 * `/stream` allowance on songs that are never heard.
 */
object ForkReadAheadWindow {

    const val LEAD_MS = 25_000L

    /** Real time left to [boundaryMs] (song time) from [positionMs] at playback [speed]. */
    fun remainingMs(boundaryMs: Long, positionMs: Long, speed: Float = 1f): Long =
        ((boundaryMs - positionMs) / speed.coerceAtLeast(MIN_SPEED)).toLong()

    /** Whether the next song's `/stream` may be called now, [remainingMs] from the transition. */
    fun due(remainingMs: Long): Boolean = remainingMs <= LEAD_MS

    /**
     * Which songs may open their addon copy: the ones audible now, and the next one once its window
     * is open. Anything else waits, the way Direct Source Stream's gate holds it.
     */
    data class Open(val playing: Set<String> = emptySet(), val next: String? = null) {
        fun admits(mediaId: String): Boolean = mediaId in playing || mediaId == next

        /** After a tick that saw [audible] playing: a next song that started playing stays admitted by it. */
        fun playing(audible: Set<String>): Open = Open(audible, next?.takeIf { it in audible })

        /** The window into [mediaId] opened; [mediaId] is the next song. */
        fun opening(mediaId: String): Open = if (mediaId in playing) this else copy(next = mediaId)
    }

    private const val MIN_SPEED = 0.25f
}
