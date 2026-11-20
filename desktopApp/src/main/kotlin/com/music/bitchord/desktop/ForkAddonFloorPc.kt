package com.music.bitchord.desktop

import com.music.bitchord.data.sources.ForkAddonFloor
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: the rulebook's lossy floor and the lossless pass (protocol.md 8.2.1, a6322d5) on the PC, over
 * BitChord's race (`DesktopSourceRegistry.openBest` / `firstSubstitute`) and its second look
 * (`betterThan`, `upgradeFor`). The rules are [ForkAddonFloor]'s, shared with the phone.
 *
 *  - **The floor runs inside the race** ([admits], per opened row): an addon's lossy row under
 *    YouTube's bitrate + 96 kbps (160 assumed when no YouTube stream was seen yet) is passed over and
 *    the addon's next row, or another addon, answers instead. Downloads keep their own rule (a lossy
 *    file over 256 kbps), so a download's legs aren't held to it.
 *  - **The pass** ([lossyPass]): once a lossy addon copy plays (it won the race, or a second look
 *    swapped it in) and the top tier is asked, every addon but the one serving it is asked again, for
 *    a lossless copy or better ([admits]' `minRank`); once per song and copy. A lossless copy gets no
 *    pass. Upstream asked only sources of a better kind, so after an addon's lossy copy nothing was
 *    asked. With Dolby Atmos wanted and not playing, upstream's chase (every other source, any rung)
 *    runs instead, which asks at least as much.
 *  - The PC has no copy that wins because it is cached on disk (every play opens `/stream`; its range
 *    cache only saves the bytes), so the pass after a cached copy doesn't arise here.
 */
internal object ForkAddonFloorPc {

    @Volatile
    private var lastYouTubeKbps: Int? = null

    /** The YouTube leg of a race answered with [stream]: its bitrate is the floor's yardstick. */
    fun seenYouTube(stream: DesktopStream?) {
        stream?.format?.kbps?.takeIf { it > 0 }?.let { lastYouTubeKbps = it }
    }

    fun facts(format: DesktopStreamFormat): ForkAddonFloor.Facts = ForkAddonFloor.Facts(
        codec = format.codec?.substringAfterLast('/')?.substringBefore(';')?.trim(),
        tier = if (format.isLossless) "LOSSLESS" else null,
        bitDepth = format.bitDepth,
        sampleRate = format.sampleRateHz,
        bitrate = format.kbps,
    )

    private fun isAtmos(stream: DesktopStream) = ForkAtmos.isAtmos(stream)

    /**
     * Whether a row [config] opened as [stream] may answer: an addon's copy has to pass the floor (when
     * [floor], i.e. for playback) and, in a pass, be at least [minRank] in the rulebook's order.
     */
    fun admits(config: DesktopSourceConfig, stream: DesktopStream, minRank: Int, floor: Boolean): Boolean {
        if (isAtmos(stream)) return true
        if (minRank > 0 && ForkAddonFloor.qualityRank(facts(stream.format)) < minRank) {
            DesktopTrackLog.log("${config.displayName} offered ${stream.format.summary.ifBlank { "an unstated format" }}; the pass asks for lossless or better")
            return false
        }
        if (!floor || config.kind != DesktopSourceKind.ADDON) return true
        val why = ForkAddonFloor.floorVerdict(facts(stream.format), lastYouTubeKbps) ?: return true
        DesktopTrackLog.log("${config.displayName}'s ${stream.format.summary.ifBlank { "copy" }} doesn't replace YouTube's: $why")
        return false
    }

    /** When each song's pass last ran, by song and the copy it ran for. */
    private val passed = ConcurrentHashMap<String, Long>()
    private const val ONCE_MS = 10 * 60_000L

    /**
     * The rulebook's pass for [playing] (the copy of [songId] now playing): the sources to ask and the
     * lowest rank to take, `(empty, 0)` for no pass, or null to leave it to upstream (YouTube's or
     * another catalogue's copy plays, or the Atmos chase is on).
     */
    fun <T> lossyPass(
        playing: DesktopStream?,
        songId: String,
        candidates: List<T>,
        quality: String?,
        configOf: (T) -> DesktopSourceConfig,
    ): Pair<List<T>, Int>? {
        val served = playing?.sourceId ?: return null
        if (ForkAtmos.wanted() && !isAtmos(playing)) return null
        val kind = DesktopPersistence().sourceConfigs().firstOrNull { it.id == served }?.kind
        if (kind != DesktopSourceKind.ADDON) return null
        val none = emptyList<T>() to 0
        if (isAtmos(playing)) return none
        val tier = if (DesktopMusicSources.ceiling(quality) == DesktopAudioQuality.LOSSLESS) ForkAddonFloor.TIER_BEST else ForkAddonFloor.TIER_LOWER
        val pass = ForkAddonFloor.upgradeWanted(facts(playing.format), tier, cached = false) ?: return none
        val key = "$songId|$served"
        val now = System.currentTimeMillis()
        val last = passed[key]
        if (last != null && now - last < ONCE_MS) return none
        if (passed.size > 200) passed.entries.removeIf { now - it.value >= ONCE_MS }
        passed[key] = now
        val sources = candidates.filter { configOf(it).id != served && configOf(it).kind == DesktopSourceKind.ADDON }
        DesktopTrackLog.log("a lossy addon copy plays; asking ${sources.size} other addon(s) once for lossless")
        return sources to pass.minRank
    }
}
