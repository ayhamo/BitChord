package com.music.bitchord.data

import com.music.bitchord.data.ForkAddonLimits.Gate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ForkAddonLimits]: rules, cached detection, the minute/hour gate, 429 learning, cached-first order. */
class ForkAddonLimitsTest {

    private class MapStore : ForkAddonLimits.Store {
        val presets = mutableMapOf<String, String>()
        val learnt = mutableMapOf<String, String>()
        val times = mutableMapOf<String, List<Long>>()
        override fun preset(key: String) = presets[key]
        override fun savePreset(key: String, presetId: String?) { if (presetId == null) presets.remove(key) else presets[key] = presetId }
        override fun learned(key: String) = learnt[key]
        override fun saveLearned(key: String, value: String?) { if (value == null) learnt.remove(key) else learnt[key] = value }
        override fun uncachedTimes(key: String) = times[key].orEmpty()
        override fun saveUncachedTimes(key: String, times: List<Long>) { this.times[key] = times }
    }

    private class Clock(var t: Long = 1_000_000_000L)

    private fun limiter(store: MapStore, clock: Clock, slept: MutableList<Long> = mutableListOf()) =
        ForkAddonLimits.Limiter(store, now = { clock.t }, sleep = { slept += it; clock.t += it })

    private val key = ForkAddonLimits.keyOf("https://NullBeat.example/abc/")
    private val streamUrl = "https://nullbeat.example/abc/stream/123?quality=hires"

    @Test
    fun keysAreTheAddonsBaseNotJustItsHost() {
        assertEquals("nullbeat.example/abc", key)
        assertTrue(ForkAddonLimits.keyOf("https://nullbeat.example/other") != key)
    }

    @Test
    fun rulesComeFromThePresetThenTheManifestThenA429() {
        val declared = ForkAddonLimits.Declared(streamsPerMinute = 5, uncachedPerHour = 60, cachedMarker = "Ready")
        assertEquals(ForkAddonLimits.Rules(5, 60, "ready"), ForkAddonLimits.rules(null, declared, learnedPerMinute = 3))
        assertEquals(ForkAddonLimits.Rules(3, null), ForkAddonLimits.rules(null, null, learnedPerMinute = 3))
        assertEquals(ForkAddonLimits.Rules(2, 30), ForkAddonLimits.rules(ForkAddonLimits.GENTLE, null, 3))
        assertFalse(ForkAddonLimits.rules(ForkAddonLimits.NONE, declared, 3).limited)
        assertFalse(ForkAddonLimits.rules(null, null, null).limited)
    }

    @Test
    fun cachedIsReadFromNullBeatsInfotextAndFlags() {
        assertEquals(true, ForkAddonLimits.isCached(listOf("⚡ Cached - DOLBY ATMOS - 21.2MB"), emptyList()))
        assertNull(ForkAddonLimits.isCached(listOf(null, ""), listOf(null)))
        assertEquals(false, ForkAddonLimits.isCached(listOf("Not cached yet"), emptyList()))
        assertEquals(false, ForkAddonLimits.isCached(listOf("uncached"), emptyList()))
        assertEquals(false, ForkAddonLimits.isCached(listOf("Cached"), listOf(false)))
        assertEquals(true, ForkAddonLimits.isCached(listOf("instant"), emptyList(), marker = "instant"))
    }

    @Test
    fun theSixthStreamInAMinuteWaitsForTheFirstToAgeOut() = runBlocking {
        val store = MapStore().apply { presets[key] = ForkAddonLimits.TORBOX }
        val clock = Clock()
        val slept = mutableListOf<Long>()
        val limiter = limiter(store, clock, slept)
        repeat(5) { assertEquals(Gate.GO, limiter.beforeStream(key, cached = true)); clock.t += 1_000 }
        assertEquals(Gate.GO, limiter.beforeStream(key, cached = true, record = false)) // the caller-side wait
        assertEquals(listOf(55_000L), slept) // the first went out 5 s ago; it ages out in 55 s
        assertEquals(4, limiter.usage(key).sentThisMinute) // a wait alone records nothing
    }

    @Test
    fun onlyRowsKnownUncachedAreSkippedOnceTheHourIsSpent() = runBlocking {
        val store = MapStore().apply { presets[key] = ForkAddonLimits.TORBOX }
        val clock = Clock()
        val limiter = limiter(store, clock)
        store.times[key] = List(60) { clock.t - 1_000L * (it + 1) }
        assertEquals(Gate.SKIP, limiter.beforeStream(key, cached = false))
        assertEquals(Gate.GO, limiter.beforeStream(key, cached = true))
        // Not searched (a queue restored after a restart): sent, and counted, never skipped.
        assertEquals(Gate.GO, limiter.beforeStream(key, cached = null))
        assertEquals(61, store.times[key]!!.size)
        clock.t += ForkAddonLimits.HOUR_MS
        val fresh = limiter(store, clock) // read back from the store, as after a restart
        assertEquals(Gate.GO, fresh.beforeStream(key, cached = false))
        assertEquals(1, store.times[key]!!.size)
        assertEquals("1 of 5 this minute · 59 uncached left this hour", fresh.usage(key).text)
        fresh.refundUncached(key)
        assertEquals(0, store.times[key]!!.size)
    }

    @Test
    fun withoutLimitsNothingIsDelayedOrCountedAsUncached() = runBlocking {
        val store = MapStore()
        val limiter = limiter(store, Clock())
        repeat(50) { assertEquals(Gate.GO, limiter.beforeStream(key, cached = false)) }
        assertTrue(store.times.isEmpty())
    }

    @Test
    fun a429OnAStreamTeachesOneFewerForADayAndAutomaticForgetsIt() = runBlocking {
        val store = MapStore()
        val clock = Clock()
        val limiter = limiter(store, clock)
        repeat(4) { limiter.beforeStream(key, cached = true) } // no rules yet: sent, and counted
        limiter.rateLimited(key, "https://nullbeat.example/abc/search?q=x")
        assertNull(store.learnt[key]) // a busy search says nothing about streams
        limiter.rateLimited(key, streamUrl)
        assertEquals(3, limiter.rules(key).streamsPerMinute)
        clock.t += ForkAddonLimits.LEARNED_TTL_MS
        assertNull(limiter.rules(key).streamsPerMinute) // forgotten after a day
        limiter.rateLimited(key, streamUrl) // the minute is long gone: nothing to learn from
        assertNull(limiter.rules(key).streamsPerMinute)
        repeat(3) { limiter.beforeStream(key, cached = true) }
        limiter.rateLimited(key, streamUrl)
        assertEquals(2, limiter.rules(key).streamsPerMinute)
        limiter.choose(key, ForkAddonLimits.AUTOMATIC)
        assertNull(store.learnt[key])
        limiter.choose(key, ForkAddonLimits.TORBOX)
        assertEquals(ForkAddonLimits.TORBOX, store.presets[key])
    }

    @Test
    fun aClockSetBackDoesNotHoldTheWindowShut() = runBlocking {
        val store = MapStore().apply { presets[key] = ForkAddonLimits.GENTLE }
        val clock = Clock()
        val slept = mutableListOf<Long>()
        val limiter = limiter(store, clock, slept)
        repeat(2) { limiter.beforeStream(key, cached = true) }
        clock.t -= 10 * ForkAddonLimits.HOUR_MS
        assertEquals(Gate.GO, limiter.beforeStream(key, cached = true))
        assertTrue(slept.isEmpty())
    }

    private data class Row(val name: String, val tier: Int, val cached: Boolean?, val recording: String)

    private fun order(rows: List<Row>) =
        ForkAddonLimits.cachedFirst(rows, { it.tier }, { it.cached }, { a, b -> a.recording == b.recording }).map { it.name }

    @Test
    fun cachedOnlyBreaksTiesBetweenCopiesOfOneRecording() {
        val rows = listOf(
            Row("atmos uncached", 3, false, "a"), Row("atmos cached", 3, true, "a"),
            Row("flac uncached", 2, false, "a"), Row("flac live cached", 2, true, "live"), Row("flac cached", 2, true, "a"),
            Row("aac cached", 1, true, "a"),
        )
        assertEquals(
            listOf("atmos cached", "atmos uncached", "flac cached", "flac uncached", "flac live cached", "aac cached"),
            order(rows),
        )
        val none = rows.map { it.copy(cached = null) }
        assertEquals(none.map { it.name }, order(none))
    }

    @Test
    fun sameRecordingIsTitleArtistAndRuntime() {
        assertTrue(ForkAddonLimits.sameRecording("Starboy", "The Weeknd, Daft Punk", "3:51", "starboy", "The Weeknd, Daft Punk", "3:50"))
        assertFalse(ForkAddonLimits.sameRecording("Starboy", "The Weeknd", "3:51", "Starboy (Live)", "The Weeknd", "3:51"))
        assertFalse(ForkAddonLimits.sameRecording("Starboy", "The Weeknd", "3:51", "Starboy", "The Weeknd", "4:10"))
        assertTrue(ForkAddonLimits.sameRecording("Starboy", "The Weeknd", null, "Starboy", "The Weeknd", "4:10"))
    }

    @Test
    fun rawJsonIsReadLeniently() {
        val p = { s: String -> Json.parseToJsonElement(s) }
        assertEquals(ForkAddonLimits.Declared(5, 60, null), ForkAddonLimits.declaredFrom(p("""{"streamsPerMinute":5,"uncachedPerHour":"60","x":1}""")))
        assertNull(ForkAddonLimits.declaredFrom(p("\"fast\"")))
        assertNull(ForkAddonLimits.declaredFrom(p("{}")))
        assertEquals(true, ForkAddonLimits.cachedFrom(listOf(p("\"⚡ Cached - 21MB\""), null)))
        assertEquals(true, ForkAddonLimits.cachedFrom(listOf(null, p("true"))))
        assertEquals(false, ForkAddonLimits.cachedFrom(listOf(p("\"false\""))))
        assertNull(ForkAddonLimits.cachedFrom(listOf(p("12"), p("{\"a\":1}"))))
    }

    @Test
    fun storedFormatsRoundTrip() {
        assertEquals(listOf(1L, 22L), ForkAddonLimits.decodeTimes(ForkAddonLimits.encodeTimes(listOf(1L, 22L))))
        assertEquals(emptyList(), ForkAddonLimits.decodeTimes(null))
        assertEquals(5, ForkAddonLimits.decodeLearned(ForkAddonLimits.encodeLearned(5, 100L), 200L))
        assertNull(ForkAddonLimits.decodeLearned("5", 200L))
        assertNull(ForkAddonLimits.decodeLearned(ForkAddonLimits.encodeLearned(5, 100L), 100L + ForkAddonLimits.LEARNED_TTL_MS))
    }
}
