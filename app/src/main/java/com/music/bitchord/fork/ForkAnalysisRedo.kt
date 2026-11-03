package com.music.bitchord.fork

import java.io.File

/**
 * Fork: which saved Automix analyses without the beat model's grid (`ForkAutomix.worthStoring`) have
 * been measured again. A DSP-only grid is the weak kind that made a pair mix badly every time, so
 * each one is measured once more instead of restored; if that again finds no model grid, the new
 * result is kept and used from then on, rather than paying for a whole-track pass every session.
 */
object ForkAnalysisRedo {

    private const val FILE = "automix_redone.txt"
    private var done: MutableSet<String>? = null

    /** True the first time [trackId] is asked about (and records it), false after. */
    @Synchronized
    fun firstTime(filesDir: File, trackId: String): Boolean {
        val file = File(filesDir, FILE)
        val set = done ?: runCatching { file.readLines().toMutableSet() }.getOrDefault(mutableSetOf()).also { done = it }
        if (!set.add(trackId)) return false
        runCatching { file.appendText(trackId + "\n") }
        return true
    }
}
