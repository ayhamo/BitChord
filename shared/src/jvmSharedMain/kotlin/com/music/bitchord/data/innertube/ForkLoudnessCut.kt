package com.music.bitchord.data.innertube

/**
 * Fork: the level from YouTube's `loudnessDb` is a cut only (the rulebook's offset-map.md "Level",
 * spec 5ece86d; `pick-cases.json` `loudnessGain`).
 *
 * A song louder than YouTube's reference (`loudnessDb` > 0) is turned down by that much; a quieter
 * one is left alone, never boosted, because YouTube's own player never boosts. Zero, missing or a
 * non-number gives 0 dB. The figure is `playerConfig.audioConfig.loudnessDb` (innertube-x reads
 * that, falling back to the format's own `loudnessDb`), never `perceptualLoudnessDb`, which this app
 * doesn't read at all. Upstream BitChord applied `-loudnessDb` both ways, so a quiet song was raised
 * up to +3 dB.
 */
object ForkLoudnessCut {

    /** The gain in dB for a song whose `loudnessDb` is [loudnessDb]: `-loudnessDb` when positive, else 0. */
    fun gainDb(loudnessDb: Double?): Double =
        loudnessDb?.takeIf { it.isFinite() && it > 0.0 }?.let { -it } ?: 0.0
}
