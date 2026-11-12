package com.music.bitchord.data.sources.addon

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.ForkAddonLimits
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/**
 * Fork: the phone's per-addon limits and cached detection ([ForkAddonLimits] says why). Kept in
 * its own preferences by addon host. Hooks: `AddonClient.manifest` declares, `AddonClient.stream`
 * gates each real request ([beforeStream]) and `AddonClient.body` reports a 429 ([rateLimited]);
 * `SourceResolver.streamBest` orders cached rows first ([cachedFirst]); the Sources row shows
 * `ForkAddonLimitsLine`. Started from `ForkAddonUserAgent.init`.
 */
object ForkAddonLimitsPhone : ForkAddonLimits.Store {

    private const val TAG = "AddonLimits"
    private const val PREFS = "fork_addon_limits"

    private var prefs: SharedPreferences? = null

    /** Addon key ([ForkAddonLimits.keyOf]) to preset id, for the Sources row; absent is Automatic. */
    private val _presets = MutableStateFlow<Map<String, String>>(emptyMap())
    val presets: StateFlow<Map<String, String>> = _presets.asStateFlow()

    val limiter = ForkAddonLimits.Limiter(
        store = this,
        now = System::currentTimeMillis,
        log = { TrackLog.d(TAG, it) },
    )

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _presets.value = p.all.mapNotNull { (key, value) ->
            key.removePrefix("preset_").takeIf { key.startsWith("preset_") }?.let { addon -> (value as? String)?.let { addon to it } }
        }.toMap()
    }

    override fun preset(key: String): String? = _presets.value[key]

    override fun savePreset(key: String, presetId: String?) {
        _presets.value = if (presetId == null) _presets.value - key else _presets.value + (key to presetId)
        prefs?.edit()?.apply { if (presetId == null) remove("preset_$key") else putString("preset_$key", presetId) }?.apply()
    }

    override fun learned(key: String): String? = prefs?.getString("learned_$key", null)

    override fun saveLearned(key: String, value: String?) {
        prefs?.edit()?.apply { if (value == null) remove("learned_$key") else putString("learned_$key", value) }?.apply()
    }

    override fun uncachedTimes(key: String): List<Long> = ForkAddonLimits.decodeTimes(prefs?.getString("uncached_$key", null))

    override fun saveUncachedTimes(key: String, times: List<Long>) {
        prefs?.edit()?.putString("uncached_$key", ForkAddonLimits.encodeTimes(times))?.apply()
    }

    private fun key(baseUrl: String): String = ForkAddonLimits.keyOf(AddonClient.normalizeBase(baseUrl))

    fun declare(baseUrl: String, limits: JsonElement?) = limiter.declare(key(baseUrl), ForkAddonLimits.declaredFrom(limits))

    /**
     * Whether [track] says it is cached, by the marker in force for the addon at [baseUrl]: false for
     * a row the addon answered without a marker, null only when there is no row (never searched).
     */
    fun cachedOf(baseUrl: String, track: AddonTrack?): Boolean? = track?.let {
        ForkAddonLimits.cachedFrom(
            listOf(it.forkInfotext, it.forkInfoText, it.forkCached, it.forkIsCached),
            limiter.rules(key(baseUrl)).cachedMarker,
        ) ?: false
    }

    /**
     * The gate around one `/stream` request: [wait] runs in the caller's coroutine before the shared
     * call (cancelled with it), [send] inside it, recording the request and giving the hour's slot
     * back if no answer came.
     */
    suspend fun wait(baseUrl: String, cached: Boolean?): ForkAddonLimits.Gate = limiter.beforeStream(key(baseUrl), cached, record = false)

    suspend fun <T> send(baseUrl: String, cached: Boolean?, request: suspend () -> Result<T>): Result<T> {
        val key = key(baseUrl)
        if (limiter.beforeStream(key, cached) == ForkAddonLimits.Gate.SKIP) return Result.failure(Skipped())
        val counted = limiter.countsUncached(key, cached)
        return request().also { if (it.isFailure && counted) limiter.refundUncached(key) }
    }

    /** A `/stream` request sent again inside the client (after a 429 wait or an auth challenge) is counted too. */
    suspend fun resending(baseUrl: String, url: String) {
        if ("/stream/" in url) limiter.beforeStream(key(baseUrl), cached = true)
    }

    suspend fun rateLimited(baseUrl: String, url: String) = limiter.rateLimited(key(baseUrl), url)

    suspend fun usage(baseUrl: String): ForkAddonLimits.Usage = limiter.usage(key(baseUrl))

    fun presetFor(baseUrl: String): ForkAddonLimits.Preset = ForkAddonLimits.preset(preset(key(baseUrl)))

    fun choose(baseUrl: String, presetId: String) = limiter.choose(key(baseUrl), presetId)

    /** [ordered] with an addon's cached rows ahead of uncached copies of the same recording and tier; other sources untouched. */
    fun cachedFirst(source: MusicSource, ordered: List<Song>): List<Song> {
        val addon = source as? AddonSource ?: return ordered
        return ForkAddonLimits.cachedFirst(
            ordered,
            tierOf = { it.sourceQuality to it.sourceHiRes },
            cachedOf = { addon.forkCached(SourceRegistry.parseTrackKey(it.videoId)?.second ?: it.videoId) },
            sameRecording = { a, b -> ForkAddonLimits.sameRecording(a.title, a.artist, a.durationText, b.title, b.artist, b.durationText) },
        )
    }

    /** What a stream request held back by the hour's uncached allowance fails with: a miss, not a fault. */
    class Skipped : Exception("uncached allowance for this hour is used")
}
