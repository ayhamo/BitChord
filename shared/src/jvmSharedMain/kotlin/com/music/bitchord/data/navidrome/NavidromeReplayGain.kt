package com.music.bitchord.data.navidrome

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The server's ReplayGain figure for each song it has listed (fork-only), for the PC's loudness
 * normalization (`ForkLoudness` in `desktopApp`).
 *
 * OpenSubsonic adds a `replayGain` object to a song row (`trackGain`, `albumGain`, `trackPeak`,
 * `albumPeak`, `baseGain`, `fallbackGain`), which Navidrome fills from the file's own ReplayGain
 * tags. The figure is a gain in dB to the ReplayGain 2.0 reference (-18 LUFS). Remembered here as
 * the rows are parsed, so every page that lists a song (search, albums, playlists, radio) gives the
 * player its figure without the `Song` model carrying a field upstream doesn't have.
 */
object NavidromeReplayGain {

    private val gains = ConcurrentHashMap<String, Double>()

    /** The track gain, else the album gain, from a song row's `replayGain` object; null when neither is there. */
    fun parse(element: JsonElement?): Double? {
        val o = element as? JsonObject ?: return null
        fun number(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        // Navidrome sends 0 for a missing tag in some versions; a real 0.0 dB is rare enough to lose.
        return number("trackGain")?.takeIf { it != 0.0 } ?: number("albumGain")?.takeIf { it != 0.0 }
    }

    fun remember(serverId: String, gainDb: Double?) {
        if (gainDb == null) gains.remove(serverId) else gains[serverId] = gainDb
    }

    /** The figure for a player id ([NavidromeSettings.ID_PREFIX] + server id), or null. */
    fun forVideoId(videoId: String): Double? {
        if (!NavidromeSettings.isNavidromeId(videoId)) return null
        return gains[videoId.removePrefix(NavidromeSettings.ID_PREFIX)]
    }
}
