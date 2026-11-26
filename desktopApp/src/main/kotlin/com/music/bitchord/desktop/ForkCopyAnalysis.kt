package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song

/**
 * Fork: an add-on's copy is analysed as the recording it is (owner via master, 2026-11-25: "Analyse the
 * FLAC"). Before, every streamed song was measured on its YouTube Opus whatever played it, so a song
 * the add-on swapped to its own copy (a FLAC, another master offset by up to seconds) had no analysis
 * of what was heard, and the loop mix refused it ([ForkLoopMixRules.analysedCopy]: "analysed off
 * another recording"). Now the copy that plays is measured too, kept under its own key
 * (`<videoId>@<sourceId>`, [key]) beside the YouTube one, and the loop mix reads it when it is ready.
 *
 * The guard stays for a real mismatch: until the copy's analysis is usable (or when it fails) the pair
 * reads the song's own analysis, which is the YouTube recording's, and the loop is refused as before.
 * The blend planner still reads the song's own analysis.
 */
internal object ForkCopyAnalysis {

    /** Between the song's id and the copy's source in [key]. */
    const val MARK = '@'

    /** An add-on's (or JioSaavn's, or a module's) copy: not a file here, not YouTube, not the song's server. */
    fun isCopy(song: Song, stream: DesktopStream?): Boolean =
        stream != null && !stream.isLocalFile && !stream.sourceId.isNullOrBlank() && stream.sourceId != "youtube" &&
            !ForkNavidrome.owns(song) && song.videoId.isNotBlank()

    /** The analysis key of what [stream] plays: the copy's own for a copy, else the song's id. */
    fun key(song: Song, stream: DesktopStream?): String =
        if (isCopy(song, stream)) "${song.videoId}$MARK${stream!!.sourceId}" else song.videoId

    /** The song id a [key] belongs to. */
    fun songOf(key: String): String = key.substringBefore(MARK)

    /**
     * The analysis to plan what [stream] plays with: the copy's when [usable] says it is measured, else the
     * song's own; and whether it is the copy's ([ForkLoopPair.outCopy] / `inCopy`).
     */
    fun planKey(song: Song, stream: DesktopStream?, usable: (String) -> Boolean): Pair<String, Boolean> {
        val k = key(song, stream)
        return if (k != song.videoId && usable(k)) k to true else song.videoId to false
    }
}
