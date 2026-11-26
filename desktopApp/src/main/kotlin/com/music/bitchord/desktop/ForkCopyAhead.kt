package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.math.max

/**
 * Fork: the next song's add-on copy is found and measured as soon as the song is next, not when its deck
 * opens (master, 2026-11-25: "start that copy's analysis as soon as the song is next in the queue").
 *
 * An add-on's song is not read ahead ([DesktopAudioEngine.prepareNext]: "may come from an addon"); its deck
 * opens when it is armed, [DesktopAudioEngine]'s ADDON_ARM_LEAD_MS before the transition (24.8 s on the
 * Nov 25 run), and only then did [ForkCopyAnalysis] start measuring the copy that plays. Measured on that
 * run: SI NO ES CONTIGO's FLAC took 26 s to analyse, so the loop mix, which reads the copy's analysis or
 * refuses the pair ("analysed off another recording"), could never have it in time.
 *
 * Now the sources ranked above YouTube are asked for the song once it is next ([DesktopMusicSources.upgradeFor],
 * the same search a playing song's second look makes) and the copy found is queued for analysis under its
 * own key (`<videoId>@<sourceId>`). Only the analysis is taken: the deck still opens at arming from the race,
 * and the race's copy from the same source finds its analysis by that key.
 */
internal object ForkCopyAhead {

    /**
     * Finds [song]'s copy with [find] and hands it to [measure] when it is a copy ([ForkCopyAnalysis.isCopy])
     * and [song] is still next ([stillNext]). Returns the copy measured, or null.
     */
    suspend fun run(
        song: Song,
        automix: Boolean,
        stillNext: () -> Boolean,
        find: suspend (Song) -> DesktopStream?,
        measure: (Song, DesktopStream) -> Unit,
    ): DesktopStream? {
        if (!automix || song.isVideoOrigin || song.videoId.isBlank()) return null
        val copy = find(song) ?: return null
        if (!stillNext() || !ForkCopyAnalysis.isCopy(song, copy)) return null
        measure(song, copy)
        return copy
    }

    /**
     * When, in seconds into the playing song, the next song's copy is measured: its search ends at
     * [askedAtSec] + [lookupSec], and the one analyser ([DesktopTrackAnalyzer], one pass at a time) first
     * finishes what is queued before it ([queuedSec], which started at [askedAtSec]).
     */
    fun readyAtSec(askedAtSec: Double, lookupSec: Double, queuedSec: List<Double>, copySec: Double): Double =
        max(askedAtSec + lookupSec, askedAtSec + queuedSec.sum()) + copySec
}
