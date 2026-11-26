package com.music.bitchord.data.canvas

import java.util.concurrent.atomic.AtomicInteger

/**
 * Fork: a Spotify sign-in's generation, so a token minted for one sign-in is never kept after it ended.
 *
 * A mint takes seconds (the phone's page harvest up to 20 s, the PC's WebView and signed request), and
 * a sign-out (or a sign-in to another account) clears the cached token without waiting for it: the mint
 * then stored its token afterwards, and the next account was handed the old one's bearer until it expired.
 *
 * Use: [start] when a mint begins; [bump] first on sign-out or a new cookie, then clear the cache; after
 * storing a minted token, [current] with the mint's generation, and undo the store when it is false.
 * Store-then-check with bump-then-clear ends clean in every interleaving: a bump before the check undoes
 * the store, one after it is followed by its own clear.
 */
class ForkTokenGeneration {
    private val generation = AtomicInteger()

    fun start(): Int = generation.get()

    fun bump() {
        generation.incrementAndGet()
    }

    fun current(started: Int): Boolean = generation.get() == started
}
