package com.music.bitchord.fork

import java.io.File

/**
 * Fork: which saved Automix analyses without the beat model's grid (`ForkAutomix.worthStoring`) have
 * been measured again. A DSP-only grid is the weak kind that made a pair mix badly every time, so
 * each one is measured once more instead of restored; if that again finds no model grid, the new
 * result is kept and used from then on, rather than paying for a whole-track pass every session.
 *
 * A track counts as measured again only once the whole-track pass has saved its new result
 * ([measured]), not when its restore was skipped ([skipRestore]). Recording it at the skip marked
 * every upcoming track that was skipped past before its pass ran, and from the next session on its
 * weak analysis was restored for good without ever having been measured again.
 */
object ForkAnalysisRedo {

    private const val FILE = "automix_redone.txt"
    private var done: MutableSet<String>? = null

    /** Tracks whose weak saved analysis was not restored this session, waiting on their new pass. */
    private val pending = HashSet<String>()

    /**
     * True when [trackId]'s weak saved analysis should not be restored, because it has not been
     * measured again yet; the track then waits on [measured].
     */
    @Synchronized
    fun skipRestore(filesDir: File, trackId: String): Boolean {
        if (trackId in doneSet(filesDir)) return false
        pending += trackId
        return true
    }

    /** The whole-track pass saved a new result for [trackId]: recorded if it was waiting on one. */
    @Synchronized
    fun measured(filesDir: File, trackId: String) {
        if (!pending.remove(trackId)) return
        if (doneSet(filesDir).add(trackId)) runCatching { File(filesDir, FILE).appendText(trackId + "\n") }
    }

    private fun doneSet(filesDir: File): MutableSet<String> =
        done ?: runCatching { File(filesDir, FILE).readLines().toMutableSet() }.getOrDefault(mutableSetOf()).also { done = it }
}
