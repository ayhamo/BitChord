package com.music.bitchord.desktop

/**
 * Fork: a song's length for the analyser when the song itself carries none.
 *
 * The analyser refused a song without a length ("3:27"), and the next song's analysis was asked for from its
 * `durationText` alone while it was held back from read-ahead. A song queued without one (a mix file's "Play these
 * mixes", from YouTube Music's watch entry or the file's title) was then measured only once it was armed, 25 s before
 * its transition: the analysis finished after the transition was planned and the mix fell back to a plain crossfade
 * ("too close to the end for a blend", measured 2026-11-25 on Cinderella → Drowning). Now it is queued all the same
 * and its length is read off the decoded audio, which the analysis decodes whole anyway.
 */
internal object ForkAnalysisLength {

    /** Whether a song with this length is worth queueing: a known one, or none known (0). */
    fun asks(knownSeconds: Double): Boolean = knownSeconds >= 0 && !knownSeconds.isNaN()

    /** [knownSeconds] when the song carried it, else the decoded audio's own length. */
    fun seconds(knownSeconds: Double, samples: Int, sampleRate: Double): Double =
        knownSeconds.takeIf { it > 0 } ?: if (sampleRate > 0) samples / sampleRate else 0.0
}
