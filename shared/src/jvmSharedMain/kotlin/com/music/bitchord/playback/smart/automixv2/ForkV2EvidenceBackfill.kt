package com.music.bitchord.playback.smart.automixv2

import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: Automix v2's evidence for songs analysed before it existed (before 40dcbf79), measured
 * without the listener doing anything.
 *
 * Why it is needed: [ForkV2EvidenceStore] is filled only where each app decodes the whole song for
 * its DSP pass, and a song whose analysis is stored never takes that pass again (both analysers
 * restore it and return). Such a song plays Automix v2 without the sub-bass veto, the loudness
 * trim, the beatless bridge and the key rules until something deletes its analysis, which nothing
 * does: for a library analysed before v2 that is most of the pairs, for good.
 *
 * How, in two halves that meet by track id:
 * - **Wanted** ([want]): the planner ([ForkV2Automix.analysisOf]) finds a song with a usable
 *   analysis and no evidence. Only a pair Automix v2 is actually planning asks, so BitChord's own
 *   style, and songs nobody mixes, never cost a decode.
 * - **Claimed** ([claim]): each app's analyser, on its usual request for the playing or next song,
 *   finds that song's analysis complete (not the phone's provisional head pass, which the
 *   whole-track pass then replaces, measuring the evidence itself) and a copy it can read, claims
 *   it, and queues an evidence-only pass on its own analysis thread behind any real analysis: the
 *   decode at the DSP rate and [ForkV2EvidenceStore.measure], no beat or vocal model.
 *
 * Once per song per run: a claim is never given back, so a song whose copy won't decode is not
 * decoded again every tick (the next launch tries once more). A claim is refused when the evidence
 * landed meanwhile (the song was analysed afresh). [want] is bounded ([MAX_WANTED]), so a long
 * session's skipped songs don't pile up; the oldest are forgotten and asked for again if they come
 * back into a pair.
 *
 * The plan picks the evidence up by itself: the memo's key holds the mapped analysis, which changes
 * once the evidence is there; a plan already latched for its blend keeps what it had.
 */
object ForkV2EvidenceBackfill {

    /** Songs waiting for an evidence pass, at most this many. */
    const val MAX_WANTED = 64

    private val wanted = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Unit>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > MAX_WANTED
        },
    )
    private val claimed = ConcurrentHashMap.newKeySet<String>()

    /** Whether evidence for an id is already there; [ForkV2EvidenceStore.get] unless a test swaps it. */
    @Volatile
    internal var has: (String) -> Boolean = { ForkV2EvidenceStore.get(it) != null }

    /** Wired into [ForkV2Automix.evidenceMissing] when the store is installed. */
    fun install() {
        ForkV2Automix.evidenceMissing = ::want
    }

    /** The planner found [id] analysed and without evidence. Cheap: called on every plan without it. */
    fun want(id: String) {
        if (id.isBlank() || id in claimed) return
        wanted[id] = Unit
    }

    /**
     * True exactly once for a [want]ed song whose evidence is still missing: the caller then runs the
     * evidence pass. False for anything not wanted, already claimed, or measured meanwhile.
     */
    fun claim(id: String): Boolean {
        if (id.isBlank() || wanted.remove(id) == null) return false
        if (has(id)) return false
        return claimed.add(id)
    }

    /** Whether [id] is waiting for a claim (tests, and the analysers' cheap pre-check). */
    fun isWanted(id: String): Boolean = wanted.containsKey(id)

    /** Forgets everything (tests). */
    internal fun forget() {
        wanted.clear()
        claimed.clear()
        has = { ForkV2EvidenceStore.get(it) != null }
    }
}
