package com.music.bitchord.playback.smart

/**
 * Fork: the pair of songs Automix is measuring for right now, so [TrackAnalyzer] drops work for songs the
 * listener has already moved past (a skip, a reorder, Play next, or music-only swapping a video for its
 * audio version, which is a new id).
 *
 * The analyzer runs one pass at a time, first come first served, and a pass takes tens of seconds (about
 * 30-40 s each on the test emulator). Only the playing song and the next one are ever queued
 * (`CrossfadeController.requestAnalysisAround`), but the old pair's passes, queued and running, still went
 * first. Measured, 2026-11-12: after skipping from "APT." to "The Fate of Ophelia" the analyzer spent
 * 20:48:29-20:49:10 on APT. and Golden, Ophelia was ready at 20:49:43 and Girls Like You at 20:50:10; and
 * after moving on to "Into You", 37 s went on "Creepin'". The overview read "songs not analysed yet"
 * meanwhile.
 *
 * Two parts: a queued pass whose song is no longer [wanted] returns at once, and a running one is abandoned
 * between stages through the analyzer's own abort ([movedPast] joins its `stopped`, which a Listen Together
 * party already used). An abandoned pass records nothing: if its song comes back into the pair, the next
 * tick queues it again. [movedPast] reads a thread-local set by [enter] on the analysis task's thread, so
 * the analyzer's other users (the mix waveform, the loop region decode) on other threads are never stopped.
 */
object ForkAnalysisFocus {
    @Volatile
    private var pair: Pair<String, String?>? = null

    /** The pass on this thread: its song, the copies already marked read before it, and whether it was abandoned. */
    private class Task(val trackId: String, val triedBefore: Set<String>) {
        var abandoned = false
    }

    private val task = ThreadLocal<Task?>()

    /** From each analysis tick: [currentId] playing, [nextId] after it (null at the end of the queue). */
    fun set(currentId: String, nextId: String?) {
        val value = currentId to nextId
        if (pair != value) pair = value
    }

    /** Whether a pass for [trackId] is still worth running; true before the first tick. */
    fun wanted(trackId: String): Boolean {
        val p = pair ?: return true
        return trackId == p.first || trackId == p.second
    }

    /**
     * The analysis task on this thread is for [trackId]; [leave] when it ends. [tried] is the analyzer's set of
     * copies already read, kept as it stands now for [untry].
     */
    fun enter(trackId: String, tried: Set<String>) = task.set(Task(trackId, HashSet(tried)))

    fun leave() = task.remove()

    /**
     * Whether this thread is running a pass for a song that is no longer [wanted]. Once true it stays true for
     * the pass: the analyzer reads its abort more than once on the way out, and a quick skip back between two
     * reads let a pass whose decode had already been cut short be recorded as "nothing in this song".
     */
    fun movedPast(): Boolean {
        val t = task.get() ?: return false
        if (!t.abandoned && !wanted(t.trackId)) t.abandoned = true
        return t.abandoned
    }

    /**
     * Whether this thread's pass was abandoned, as [movedPast] last found it, without asking again: a pass
     * that finished and recorded its result before the listener moved on was not abandoned.
     */
    fun abandoned(): Boolean = task.get()?.abandoned == true

    /** For an abandoned pass: takes back the copies it marked read, so a written-off song's new copy is still tried. */
    fun untry(tried: MutableSet<String>) {
        val t = task.get() ?: return
        tried.retainAll(t.triedBefore)
    }
}
