package com.music.bitchord.data

import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Fork (owner 2026-11-11): per-addon request limits and "cached" detection, shared by the phone
 * (`ForkAddonLimitsPhone`) and the PC (`ForkDesktopAddonLimits`).
 *
 * Why: debrid-backed addons have limits nothing in the addon protocol states. NullBeat allows 5 stream
 * requests a minute, and its TorBox side 60 *uncached* tracks an hour (a cached one is free); going
 * past either gets the account rate limited or flagged. Hard-coding NullBeat's numbers would break the
 * day it is replaced, so the rules are data, per addon host, taken from (strongest first):
 *
 * 1. the listener's choice on the addon's Sources row ([Preset]);
 * 2. what the addon declares in its manifest, `"limits": {"streamsPerMinute": 5, "uncachedPerHour": 60,
 *    "cachedMarker": "cached"}` ([Declared]) — not a spec field; asked of the addon devs;
 * 3. what a `429` taught: the stream requests sent in the minute before it, less one ([learned]).
 *
 * Reaching a limit never fails a track: a stream request waits for the minute's slot, and once the
 * hour's uncached allowance is spent an uncached row is skipped ([Gate.SKIP]), so the resolver moves
 * on to a cached row or another source. "Cached" is read from what addons send ([isCached]):
 * NullBeat sets `infotext` ("⚡ Cached - DOLBY ATMOS - 21.2MB") on cached rows only.
 */
object ForkAddonLimits {

    /** What an addon may declare about itself in its manifest, under `limits`. */
    @Serializable
    data class Declared(
        @SerialName("streamsPerMinute") val streamsPerMinute: Int? = null,
        @SerialName("uncachedPerHour") val uncachedPerHour: Int? = null,
        @SerialName("cachedMarker") val cachedMarker: String? = null,
    )

    /** The limits in force for one addon; null fields are no limit. */
    data class Rules(
        val streamsPerMinute: Int? = null,
        val uncachedPerHour: Int? = null,
        val cachedMarker: String = DEFAULT_MARKER,
    ) {
        val limited: Boolean get() = streamsPerMinute != null || uncachedPerHour != null
    }

    /** The choices on the Sources row. [AUTOMATIC] is the addon's own declaration, else what a 429 taught. */
    data class Preset(val id: String, val label: String, val streamsPerMinute: Int?, val uncachedPerHour: Int?) {
        val description: String
            get() = when {
                id == AUTOMATIC -> "Automatic"
                streamsPerMinute == null && uncachedPerHour == null -> label
                else -> "$label (" + listOfNotNull(
                    streamsPerMinute?.let { "$it/min" },
                    uncachedPerHour?.let { "$it uncached/h" },
                ).joinToString(", ") + ")"
            }
    }

    const val AUTOMATIC = "auto"
    const val TORBOX = "torbox"
    const val GENTLE = "gentle"
    const val NONE = "none"
    const val DEFAULT_MARKER = "cached"

    val presets: List<Preset> = listOf(
        Preset(AUTOMATIC, "Automatic", null, null),
        Preset(TORBOX, "TorBox-backed", 5, 60),
        Preset(GENTLE, "Gentle", 2, 30),
        Preset(NONE, "No limits", null, null),
    )

    fun preset(id: String?): Preset = presets.firstOrNull { it.id == id } ?: presets.first()

    /** The rules for [presetId]: a fixed preset's numbers, else the addon's declaration, else what it taught. */
    fun rules(presetId: String?, declared: Declared?, learnedPerMinute: Int?): Rules {
        val marker = declared?.cachedMarker?.trim()?.lowercase()?.ifBlank { null } ?: DEFAULT_MARKER
        val preset = preset(presetId)
        return when (preset.id) {
            AUTOMATIC -> Rules(
                streamsPerMinute = declared?.streamsPerMinute?.takeIf { it > 0 } ?: learnedPerMinute?.takeIf { it > 0 },
                uncachedPerHour = declared?.uncachedPerHour?.takeIf { it > 0 },
                cachedMarker = marker,
            )
            else -> Rules(preset.streamsPerMinute, preset.uncachedPerHour, marker)
        }
    }

    /**
     * Whether a search row says it is cached: a `cached`/`isCached` flag, or a text field (NullBeat's
     * `infotext`) containing [marker] but not "not cached"/"uncached". Null when the row says nothing.
     */
    fun isCached(texts: List<String?>, flags: List<Boolean?>, marker: String = DEFAULT_MARKER): Boolean? {
        flags.firstOrNull { it != null }?.let { return it }
        val said = texts.mapNotNull { it?.lowercase()?.ifBlank { null } }
        if (said.isEmpty()) return null
        return said.any { text ->
            marker in text && "not $marker" !in text && "un$marker" !in text && "not-$marker" !in text
        }
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** A manifest's `limits`, read leniently: anything malformed is no declaration, never a broken manifest. */
    fun declaredFrom(element: JsonElement?): Declared? =
        element?.let { runCatching { lenient.decodeFromJsonElement(Declared.serializer(), it) }.getOrNull() }
            ?.takeIf { it.streamsPerMinute != null || it.uncachedPerHour != null || it.cachedMarker != null }

    /**
     * [isCached] over raw row fields, read as JSON so an addon sending a number or an object there
     * can't fail the whole search: strings are texts, booleans (or "true"/"false") are flags.
     */
    fun cachedFrom(fields: List<JsonElement?>, marker: String = DEFAULT_MARKER): Boolean? {
        val primitives = fields.filterIsInstance<JsonPrimitive>()
        val flags = primitives.map { it.booleanOrNull }
        val texts = primitives.filter { it.isString && it.booleanOrNull == null }.map { it.content }
        return isCached(texts, flags, marker)
    }

    /** A 429 after [sentLastMinute] stream requests in a minute: one fewer is the limit to keep. */
    fun learned(sentLastMinute: Int): Int? = if (sentLastMinute >= 2) sentLastMinute - 1 else null

    /** How long a limit a 429 taught is kept: an addon's limits change, and a lowered one never rises otherwise. */
    const val LEARNED_TTL_MS = 24 * 3_600_000L

    /** A learned limit as stored, "5@1731340800000"; null when absent, malformed or older than [LEARNED_TTL_MS]. */
    fun decodeLearned(raw: String?, now: Long): Int? {
        val parts = raw?.split('@') ?: return null
        val perMinute = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return perMinute.takeIf { now - at in 0 until LEARNED_TTL_MS }
    }

    fun encodeLearned(perMinute: Int, at: Long): String = "$perMinute@$at"

    enum class Gate { GO, SKIP }

    /**
     * Where an addon's choice and counts are kept between runs (preferences on each app), by key: the
     * addon's base URL, so two addons on one server (two accounts' configs) keep their own.
     */
    interface Store {
        fun preset(key: String): String?
        fun savePreset(key: String, presetId: String?)
        /** [encodeLearned] text, or null. */
        fun learned(key: String): String?
        fun saveLearned(key: String, value: String?)
        /** Epoch-millisecond times of uncached stream requests, for the hour window across restarts. */
        fun uncachedTimes(key: String): List<Long>
        fun saveUncachedTimes(key: String, times: List<Long>)
    }

    /** The key limits are kept by: [baseUrl] without scheme, query or trailing slash, lower-cased host. */
    fun keyOf(baseUrl: String): String {
        val rest = baseUrl.substringAfter("://").substringBefore('?').trimEnd('/')
        val host = rest.substringBefore('/')
        return host.lowercase() + rest.removePrefix(host)
    }

    /** What the Sources row shows for one addon. */
    data class Usage(val rules: Rules, val sentThisMinute: Int, val uncachedThisHour: Int) {
        val text: String
            get() = buildList {
                rules.streamsPerMinute?.let { add("$sentThisMinute of $it this minute") }
                rules.uncachedPerHour?.let { add("${(it - uncachedThisHour).coerceAtLeast(0)} uncached left this hour") }
            }.joinToString(" · ")
    }

    /**
     * The counting and waiting, per host. One per app; [now] and [sleep] are injectable for tests.
     * Stream request times live in memory (a minute is short); uncached times go to the [store],
     * since TorBox's hour does not restart with the app.
     */
    class Limiter(
        private val store: Store,
        private val now: () -> Long,
        private val sleep: suspend (Long) -> Unit = { delay(it) },
        private val log: (String) -> Unit = {},
    ) {
        private val lock = Mutex()
        private val sent = HashMap<String, ArrayDeque<Long>>()
        private val uncached = HashMap<String, ArrayDeque<Long>>()

        /** Manifest declarations by host, replaced whole on each change (read from any thread). */
        @Volatile
        private var declared: Map<String, Declared> = emptyMap()

        /** The manifest's `limits` for [host], as last read; null clears it. */
        fun declare(host: String, value: Declared?) {
            declared = if (value == null) declared - host else declared + (host to value)
        }

        fun rules(host: String): Rules = rules(store.preset(host), declared[host], decodeLearned(store.learned(host), now()))

        /**
         * Before a stream request is sent to [host]: SKIP when the row is known uncached
         * ([cached] false) and the hour's allowance is spent; otherwise waits for a slot in the minute
         * and, with [record], records the request. A row the app has no search answer for ([cached]
         * null, e.g. a queue restored after a restart) is counted as uncached but never skipped:
         * skipping it could hold back a cached copy.
         *
         * Called twice per request: without [record] in the caller's own coroutine before the shared
         * call (so the wait is cancelled with the caller, and nothing queues up for nobody), then
         * with [record] inside it, where the request is about to go out.
         */
        suspend fun beforeStream(host: String, cached: Boolean?, record: Boolean = true): Gate {
            val r = rules(host)
            if (!r.limited) {
                // Still recorded, so a 429 from an addon with no rules yet can teach one.
                if (record) lock.withLock { val t = now(); sentLocked(host, t).addLast(t) }
                return Gate.GO
            }
            // An addon given an uncached allowance is one that marks its cached rows, so a row that
            // says nothing is uncached there. Without an allowance nothing is counted.
            val countUncached = r.uncachedPerHour != null && cached != true
            while (true) {
                val wait = lock.withLock {
                    val t = now()
                    val hour = uncachedLocked(host, t)
                    if (cached == false && countUncached && hour.size >= r.uncachedPerHour!!) {
                        if (record) log("addon $host: uncached allowance (${r.uncachedPerHour}/h) used, skipping an uncached row")
                        return Gate.SKIP
                    }
                    val minute = sentLocked(host, t)
                    val perMinute = r.streamsPerMinute
                    if (perMinute != null && minute.size >= perMinute) {
                        // At most a minute, whatever the clock did in between.
                        (minute.first() + MINUTE_MS - t).coerceIn(1, MINUTE_MS)
                    } else {
                        if (record) {
                            minute.addLast(t)
                            if (countUncached) {
                                hour.addLast(t)
                                store.saveUncachedTimes(host, hour.toList())
                            }
                        }
                        0L
                    }
                }
                if (wait == 0L) return Gate.GO
                if (record) log("addon $host: ${r.streamsPerMinute} stream requests this minute, waiting ${wait / 1000}s")
                sleep(wait)
            }
        }

        /**
         * A 429 from [host] on [url]: under Automatic, keep one stream request a minute fewer than
         * were just sent. Only a stream request's 429 teaches; a busy search says nothing about streams.
         */
        suspend fun rateLimited(host: String, url: String) {
            if ("/stream/" !in url) return
            val perMinute = lock.withLock { sentLocked(host, now()).size }
            val learnt = learned(perMinute) ?: return
            val old = decodeLearned(store.learned(host), now())
            if (old == null || learnt < old) {
                store.saveLearned(host, encodeLearned(learnt, now()))
                log("addon $host: rate limited after $perMinute stream requests in a minute; keeping to $learnt")
            }
        }

        /** Whether a stream request for a row with [cached] counts against [host]'s uncached allowance. */
        fun countsUncached(host: String, cached: Boolean?): Boolean = rules(host).uncachedPerHour != null && cached != true

        /**
         * A stream request recorded as uncached did not get an answer (timeout, refused, 404): its
         * hour slot is given back. The minute's count stays, since the addon was still asked.
         */
        suspend fun refundUncached(host: String) {
            lock.withLock {
                val hour = uncachedLocked(host, now())
                if (hour.isNotEmpty()) {
                    hour.removeLast()
                    store.saveUncachedTimes(host, hour.toList())
                }
            }
        }

        suspend fun usage(host: String): Usage = lock.withLock {
            val t = now()
            Usage(rules(host), sentLocked(host, t).size, uncachedLocked(host, t).size)
        }

        /** The listener's choice for [host]; [AUTOMATIC] also forgets what a 429 taught. */
        fun choose(host: String, presetId: String) {
            store.savePreset(host, presetId.takeUnless { it == AUTOMATIC })
            if (presetId == AUTOMATIC) store.saveLearned(host, null)
        }

        private fun sentLocked(host: String, t: Long): ArrayDeque<Long> =
            sent.getOrPut(host) { ArrayDeque() }.apply {
                removeAll { it > t } // the clock went back: times "in the future" would hold the window shut
                while (isNotEmpty() && first() <= t - MINUTE_MS) removeFirst()
            }

        private fun uncachedLocked(host: String, t: Long): ArrayDeque<Long> =
            uncached.getOrPut(host) { ArrayDeque(store.uncachedTimes(host).sorted()) }
                .apply {
                    removeAll { it > t }
                    while (isNotEmpty() && first() <= t - HOUR_MS) removeFirst()
                }
    }

    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L

    /**
     * [rows] reordered so that a cached row ([cachedOf]) is opened before an uncached one it is
     * [sameRecording] as, within a run of the same [tierOf]. Nothing else moves: the caller's order
     * (length, Atmos, quality, then match confidence) stands, and cached only breaks ties between
     * copies of one recording — never puts a weaker match (a live cut, a remix) ahead of a better one.
     */
    fun <T> cachedFirst(
        rows: List<T>,
        tierOf: (T) -> Any?,
        cachedOf: (T) -> Boolean?,
        sameRecording: (T, T) -> Boolean,
    ): List<T> {
        if (rows.size < 2 || rows.none { cachedOf(it) == true }) return rows
        val out = ArrayList<T>(rows.size)
        val used = BooleanArray(rows.size)
        for (i in rows.indices) {
            if (used[i]) continue
            if (cachedOf(rows[i]) != true) {
                val tier = tierOf(rows[i])
                var j = i + 1
                while (j < rows.size && tierOf(rows[j]) == tier) {
                    if (!used[j] && cachedOf(rows[j]) == true && sameRecording(rows[i], rows[j])) {
                        used[j] = true
                        out += rows[j]
                        break
                    }
                    j++
                }
            }
            used[i] = true
            out += rows[i]
        }
        return out
    }

    /** "Title", "artist" and "m:ss" alike enough to be one recording: same words, runtimes within 2 s. */
    fun sameRecording(titleA: String, artistA: String, durationA: String?, titleB: String, artistB: String, durationB: String?): Boolean {
        fun norm(v: String) = v.lowercase().filter { it.isLetterOrDigit() }
        fun secs(v: String?) = v?.split(':')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size in 2..3 }?.fold(0) { a, b -> a * 60 + b }
        if (norm(titleA) != norm(titleB) || norm(artistA) != norm(artistB)) return false
        val a = secs(durationA)
        val b = secs(durationB)
        return a == null || b == null || kotlin.math.abs(a - b) <= 2
    }

    /** Store helpers: the uncached times as a comma list. */
    fun encodeTimes(times: List<Long>): String = times.joinToString(",")

    fun decodeTimes(raw: String?): List<Long> = raw.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
}
