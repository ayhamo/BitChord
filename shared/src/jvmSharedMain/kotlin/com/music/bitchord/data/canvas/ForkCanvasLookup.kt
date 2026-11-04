package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Fork: whether a canvas lookup that found nothing was *told* "no clip" or never got an answer.
 *
 * Upstream's repositories (the phone's `CanvasRepository`, the PC's `DesktopCanvasClient`) cache a
 * miss for the song whatever caused it, and every provider folds its own failures into the same
 * null a real "no clip" returns: Apple's 401s and HTTP errors, Tidal's and Spotify's 429s, a token
 * that would not mint, an exception in `firstHit`. A song played once while offline, or while a
 * token was being refused, then kept "no clip" until the cache dropped it, even after the network
 * or the token came back.
 *
 * Rather than give every provider's upstream signature a three-way answer (dozens of edited lines
 * in files upstream changes often), each provider keeps returning `CanvasArtwork?` and reports a
 * failure on the side with [fail] / [failed], one hook line where it gives up. The report goes to
 * the [Scope] of the lookup running on that coroutine: the repository opens one per lookup and
 * carries it as a [kotlinx.coroutines.ThreadContextElement], so it follows the lookup across
 * dispatcher hops (the phone's Spotify token hops to the main thread for its WebView) and a call
 * from anywhere else (the fork's own Spotify backdrop lookup, the Spotify library's token use) has
 * no scope and reports nothing. A global flag would have let those mark someone else's lookup.
 *
 * The repository then settles it with [keeps]: [Outcome.FOUND] and [Outcome.EMPTY] are cached as
 * before; [Outcome.FAILED] is not, and is noted for [RETRY_AFTER_MS] so a queue skipping past the
 * same song (or the player asking again when its album lands) doesn't re-ask a failing service at
 * once ([skips]). Found wins: a later provider's clip is used and cached even when an earlier one
 * failed, exactly as before. The providers' own backoffs (Apple's 30 min token retry, Spotify's)
 * are untouched; this only decides what the song-level cache remembers.
 *
 * A provider sitting out its own backoff reports with [coolingDown] instead, naming when it will
 * try again. The song's note then lasts until then rather than a minute: with Apple's token
 * refused for 30 minutes, or a Spotify sign-in that no longer mints, a minute-long note meant every
 * song with no clip was asked of every other provider again on each play, though the one that
 * failed could not have answered yet. It is still not cached as "no clip", so the song is asked
 * once more when the provider is back.
 */
object ForkCanvasLookup {

    private const val TAG = "ForkCanvasLookup"

    /** How long a failed lookup for one song is left alone before the next play may ask again. */
    const val RETRY_AFTER_MS = 60_000L

    private val current = ThreadLocal<Scope?>()

    /** What a lookup came to. */
    enum class Outcome {
        /** A provider answered with a clip that survived the match check. */
        FOUND,

        /** Every provider asked answered, and none had a clip for this song: a settled miss. */
        EMPTY,

        /** Nothing found, and at least one provider never got to answer (network, HTTP, token, exception). */
        FAILED,
    }

    /** The decision itself, kept pure for the tests: a find beats any failure, a failure beats a miss. */
    fun outcome(found: Boolean, failure: String?): Outcome = when {
        found -> Outcome.FOUND
        failure != null -> Outcome.FAILED
        else -> Outcome.EMPTY
    }

    /**
     * One lookup's failure report. [context] goes into the lookup's `withContext`, which is what
     * makes [fail] calls inside it land here.
     */
    class Scope {
        /** The first failure reported, for the log; null while every provider has answered. */
        @Volatile var failure: String? = null
            internal set

        /** The latest time a provider in its own backoff ([coolingDown]) will try again; 0 for none. */
        @Volatile var retryAtMs: Long = 0L
            internal set

        val context: CoroutineContext = current.asContextElement(this)
    }

    /**
     * A provider could not get an answer ([reason] is for the log). A no-op outside a lookup's
     * [Scope]. Only the first reason is kept: it is the one that explains the rest.
     */
    fun fail(reason: String) {
        val scope = current.get() ?: return
        if (scope.failure == null) scope.failure = reason
    }

    /** [fail], returning null, for a provider's `?: return ForkCanvasLookup.failed("…")`. */
    fun failed(reason: String): Nothing? {
        fail(reason)
        return null
    }

    /**
     * A provider is in its own cool-down and will not try again before [untilMs]: a failure (not
     * "no clip") whose song is left alone until then instead of for [RETRY_AFTER_MS]. Returns null,
     * for `?: return ForkCanvasLookup.coolingDown("…", retryAfterMs)`. An [untilMs] already past
     * is a plain [failed].
     */
    fun coolingDown(reason: String, untilMs: Long): Nothing? {
        val scope = current.get() ?: return null
        if (scope.failure == null) scope.failure = reason
        if (untilMs > scope.retryAtMs) scope.retryAtMs = untilMs
        return null
    }

    /**
     * Runs [block] with its failures not counted: for a second opinion asked after a service has
     * already answered. Spotify's Pathfinder saying "no canvas" is that answer; `canvaz-cache`
     * failing after it (it is old and often refuses) must not turn the song's settled miss into a
     * failure that is asked again on every play.
     */
    fun <T> answered(block: () -> T): T {
        val scope = current.get() ?: return block()
        val before = scope.failure
        val retryBefore = scope.retryAtMs
        return try {
            block()
        } finally {
            scope.failure = before
            scope.retryAtMs = retryBefore
        }
    }

    /** Failed lookups, by the repository's own cache key. One per process, both platforms. */
    val notes = FailedNotes()

    /**
     * Forgets every failed lookup: a new Spotify sign-in ends that provider's backoff, so songs held
     * back by it ([coolingDown], up to 30 min) are asked again on their next play.
     */
    fun clearNotes() = notes.clear()

    /** Whether the lookup for [key] failed less than [RETRY_AFTER_MS] ago and is to be answered null unasked. */
    fun skips(key: String, withAlbum: Boolean): Boolean = notes.recentlyFailed(key, withAlbum)

    /**
     * Settles a finished lookup: true when the repository should cache its answer (found or a
     * settled miss), false for a failure, which is noted instead.
     */
    fun keeps(key: String, withAlbum: Boolean, found: Boolean, scope: Scope): Boolean {
        val outcome = outcome(found, scope.failure)
        if (outcome == Outcome.FAILED) Log.d(TAG, "not caching '$key': ${scope.failure}")
        return notes.record(key, withAlbum, outcome, scope.retryAtMs)
    }

    /**
     * The in-memory note of which keys failed and when. Bounded like the repositories' own caches,
     * since a note only matters for the minute after it is written.
     */
    class FailedNotes(
        private val windowMs: Long = RETRY_AFTER_MS,
        private val now: () -> Long = System::currentTimeMillis,
        private val maxEntries: Int = 64,
    ) {
        /** [untilMs]: when the song may be asked again, at least [windowMs] after the failure. */
        private class Note(val untilMs: Long, val withAlbum: Boolean)

        private val notes = object : LinkedHashMap<String, Note>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Note>) = size > maxEntries
        }

        /**
         * True inside the window, unless the earlier attempt went without the album and this one
         * has it: the same exception the repositories make for a blind miss, since the album is
         * what can turn the answer around (and the player asks again the moment it lands).
         */
        @Synchronized
        fun recentlyFailed(key: String, withAlbum: Boolean): Boolean {
            val note = notes[key] ?: return false
            if (now() >= note.untilMs) {
                notes.remove(key)
                return false
            }
            return note.withAlbum || !withAlbum
        }

        @Synchronized
        fun clear() = notes.clear()

        /**
         * Notes a failure (returns false: don't cache) or clears the note (returns true: cache).
         * [retryAtMs] is a cooling-down provider's own retry time, which outlasts the window.
         */
        @Synchronized
        fun record(key: String, withAlbum: Boolean, outcome: Outcome, retryAtMs: Long = 0L): Boolean {
            if (outcome == Outcome.FAILED) {
                notes[key] = Note(maxOf(now() + windowMs, retryAtMs), withAlbum)
                return false
            }
            notes.remove(key)
            return true
        }
    }
}
