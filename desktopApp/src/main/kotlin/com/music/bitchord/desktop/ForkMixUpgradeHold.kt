package com.music.bitchord.desktop

/*
 * Fork: a better copy that arrives while an Automix mix is playing is kept and installed a moment
 * after the mix settles (from PC-Continued be257dad, `mixEngaged` / `deferredUpgrade` /
 * `UPGRADE_AFTER_MIX_DELAY_MS`, fitted to this engine's crossfaded quality swap).
 *
 * An addon routinely answers 10-15 s after it is asked, so its lossless copy of a song often lands in
 * the middle of a blend: the song was started by hand and the next one is already mixing in, or the
 * next song's raced resolve was still waiting when the blend into it began. The engine refused every
 * such copy (`replaceCurrent`: a blend running or the display switched, or a loop mix's decks
 * spliced) and closed it, because a swap on either deck mid-mix is heard as a dropout over the
 * transition. And the search that found it counted the question as settled, so the song played on
 * its lossy copy to the end. A blend cancelled under the copy (a seek back, the next song changed)
 * left the very song it was for playing, still on the worse copy.
 *
 * So the copy waits here instead. Once no mix has played for [SETTLE_MS], the engine takes it out
 * and checks it against the song playing *then*: the song it was for (the blend went into it, or the
 * blend was cancelled and left it playing) gets it through the ordinary crossfaded swap
 * (`forkQueueSwap`); any other song (the blend left it behind, the queue moved on) closes it unheard.
 * The settle is PC-Continued's: their upgrade installed on the seam's heels re-cued the deck while the
 * hand-over was still settling, which moved the song's position, and its lyrics, for the rest of the
 * track. This engine's swap lines the copy up on its landing instead of re-cueing, but the hand-over's
 * own cue checks (`forkReconcileIncoming`, `forkCheckCue`) run in those first moments, and a second
 * deck change on top of them is not worth the risk for a copy that can as well wait a second and a half.
 *
 * Pure bookkeeping, generic so it is tested without FFmpeg: the engine holds its own `Track`s in it
 * and closes whatever it is handed back. Audio thread only.
 */
internal class ForkMixUpgradeHold<T : Any>(private val settleNs: Long = SETTLE_MS * 1_000_000L) {
    private var held: T? = null
    private var mixedAtNs = 0L
    private var hasMixed = false

    /** The copy waiting, if any. */
    val holding: T? get() = held

    /**
     * Notes whether a mix is playing at [nowNs]. Called on every audio step whether or not anything is
     * held, so the settle is counted from the mix's last moment, not from when a copy happened to come.
     */
    fun observe(mixing: Boolean, nowNs: Long) {
        if (!mixing) return
        mixedAtNs = nowNs
        hasMixed = true
    }

    /** Whether a copy arriving at [nowNs] has to wait: a mix is playing, or ended under [SETTLE_MS] ago. */
    fun mustWait(mixing: Boolean, nowNs: Long): Boolean {
        observe(mixing, nowNs)
        return mixing || (hasMixed && nowNs - mixedAtNs < settleNs)
    }

    /**
     * Keeps [item], the newest copy winning: a second answer for the same song is the later look, and
     * one for another song means the first is already stale. Hands back the copy it replaced, for the
     * caller to close.
     */
    fun hold(item: T): T? {
        val old = held
        held = item
        return old?.takeIf { it !== item }
    }

    /** The held copy once the mix has settled, taken out of the hold; null while it must wait, or with none. */
    fun due(mixing: Boolean, nowNs: Long): T? {
        if (mustWait(mixing, nowNs)) return null
        return held.also { held = null }
    }

    /** Empties the hold (a new song, the engine closing); the caller closes what comes back. */
    fun drop(): T? = held.also { held = null }

    companion object {
        /** How long after a mix a copy still waits (PC-Continued's `UPGRADE_AFTER_MIX_DELAY_MS`). */
        const val SETTLE_MS = 1_500L
    }
}
