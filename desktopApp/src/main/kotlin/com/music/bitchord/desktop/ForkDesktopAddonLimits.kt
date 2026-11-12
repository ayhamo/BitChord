package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.ForkAddonLimits
import com.music.bitchord.data.ForkAddonUserAgents
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * Fork: the PC's per-addon limits and cached detection ([ForkAddonLimits] says why), the phone's
 * `ForkAddonLimitsPhone`. Kept in the PC's preferences by addon host (`fork_addon_limits_*`). Hooks:
 * `DesktopAddonClient.manifest` declares, `.stream` gates each real request, `.body` reports a 429;
 * `DesktopSourceRegistry`'s match loop orders cached rows first ([cachedFirst]); the Sources row
 * shows [ForkAddonLimitsRow] (from [ForkAddonUserAgentRow]).
 */
internal object ForkDesktopAddonLimits : ForkAddonLimits.Store {

    private const val PRESET = "fork_addon_limits_preset_"
    private const val LEARNED = "fork_addon_limits_learned_"
    private const val UNCACHED = "fork_addon_limits_uncached_"
    private val persistence by lazy { DesktopPersistence() }

    /** Addon key ([ForkAddonLimits.keyOf]) to preset id as last read or chosen, "" for Automatic; for the Sources row. */
    private val _presets = MutableStateFlow<Map<String, String>>(emptyMap())
    val presets: StateFlow<Map<String, String>> = _presets.asStateFlow()

    val limiter = ForkAddonLimits.Limiter(this, now = System::currentTimeMillis, log = { DesktopTrackLog.log(it) })

    override fun preset(key: String): String? {
        val known = _presets.value[key] ?: persistence.string(PRESET + key).also { stored -> _presets.update { it + (key to stored) } }
        return known.ifBlank { null }
    }

    override fun savePreset(key: String, presetId: String?) {
        _presets.update { it + (key to presetId.orEmpty()) }
        persistence.saveString(PRESET + key, presetId.orEmpty())
    }

    override fun learned(key: String): String? = persistence.string(LEARNED + key).ifBlank { null }

    override fun saveLearned(key: String, value: String?) = persistence.saveString(LEARNED + key, value.orEmpty())

    override fun uncachedTimes(key: String): List<Long> = ForkAddonLimits.decodeTimes(persistence.string(UNCACHED + key))

    override fun saveUncachedTimes(key: String, times: List<Long>) = persistence.saveString(UNCACHED + key, ForkAddonLimits.encodeTimes(times))

    private fun key(baseUrl: String): String = ForkAddonLimits.keyOf(DesktopAddonClient.normalizeBase(baseUrl))

    fun declare(baseUrl: String, limits: JsonElement?) = limiter.declare(key(baseUrl), ForkAddonLimits.declaredFrom(limits))

    /** Whether [track] says it is cached: false for a row answered without a marker, null only when there is no row. */
    fun cachedOf(baseUrl: String, track: DesktopAddonTrack?): Boolean? = track?.let {
        ForkAddonLimits.cachedFrom(
            listOf(it.forkInfotext, it.forkInfoText, it.forkCached, it.forkIsCached),
            limiter.rules(key(baseUrl)).cachedMarker,
        ) ?: false
    }

    /** As the phone's: [wait] in the caller's coroutine before the shared call, [send] inside it. */
    suspend fun wait(baseUrl: String, cached: Boolean?): ForkAddonLimits.Gate = limiter.beforeStream(key(baseUrl), cached, record = false)

    suspend fun <T> send(baseUrl: String, cached: Boolean?, request: suspend () -> Result<T>): Result<T> {
        val key = key(baseUrl)
        if (limiter.beforeStream(key, cached) == ForkAddonLimits.Gate.SKIP) return Result.failure(Skipped())
        val counted = limiter.countsUncached(key, cached)
        return request().also { if (it.isFailure && counted) limiter.refundUncached(key) }
    }

    /** A `/stream` request sent again after a 429 wait is counted too. */
    suspend fun resending(baseUrl: String, url: String) {
        if ("/stream/" in url) limiter.beforeStream(key(baseUrl), cached = true)
    }

    suspend fun rateLimited(baseUrl: String, url: String) = limiter.rateLimited(key(baseUrl), url)

    suspend fun usage(baseUrl: String): ForkAddonLimits.Usage = limiter.usage(key(baseUrl))

    fun presetFor(baseUrl: String): ForkAddonLimits.Preset = ForkAddonLimits.preset(preset(key(baseUrl)))

    fun choose(baseUrl: String, presetId: String) {
        limiter.choose(key(baseUrl), presetId)
        DesktopTrackLog.log("addon ${ForkAddonUserAgents.hostOf(baseUrl)}: limits ${ForkAddonLimits.preset(presetId).description}")
    }

    /** [ordered] with an addon's cached rows ahead of uncached copies of the same recording and tier; other sources untouched. */
    fun cachedFirst(config: DesktopSourceConfig, ordered: List<Song>): List<Song> {
        if (config.kind != DesktopSourceKind.ADDON) return ordered
        return ForkAddonLimits.cachedFirst(
            ordered,
            tierOf = { it.sourceQuality to it.sourceHiRes },
            cachedOf = { song -> DesktopAddonSource.parseTrack(song.videoId)?.let { DesktopAddonSource.forkCached(config, it.trackId) } },
            sameRecording = { a, b -> ForkAddonLimits.sameRecording(a.title, a.artist, a.durationText, b.title, b.artist, b.durationText) },
        )
    }

    /** What a stream request held back by the hour's uncached allowance fails with: a miss, not a fault. */
    class Skipped : Exception("uncached allowance for this hour is used")
}

/** Under the user agent line, styled like it: the addon's limits and what is used of them. */
@Composable
internal fun ForkAddonLimitsRow(config: DesktopSourceConfig) {
    if (config.kind != DesktopSourceKind.ADDON || !config.isComplete) return
    val presets by ForkDesktopAddonLimits.presets.collectAsState()
    val preset = ForkDesktopAddonLimits.presetFor(config.baseUrl)
    // What has been used moves on its own as the minute and hour pass, so it is re-read every few seconds.
    val usage by produceState<ForkAddonLimits.Usage?>(null, config.baseUrl, presets) {
        while (true) {
            value = ForkDesktopAddonLimits.usage(config.baseUrl)
            delay(5_000)
        }
    }
    val rules = usage?.rules
    val label = if (preset.id == ForkAddonLimits.AUTOMATIC && rules?.limited == true) {
        "Automatic (" + listOfNotNull(rules.streamsPerMinute?.let { "$it/min" }, rules.uncachedPerHour?.let { "$it uncached/h" }).joinToString(", ") + ")"
    } else {
        preset.description
    }
    val text = "Limits: $label" + (usage?.text?.ifBlank { null }?.let { " · $it" } ?: "")
    var open by remember(config.id) { mutableStateOf(false) }
    // Greyed with its row, never hidden.
    Box(Modifier.padding(start = 53.dp, bottom = 6.dp).alpha(if (config.enabled) 1f else 0.45f)) {
        Row(
            Modifier
                .desktopRowClickable(enabled = config.enabled) { open = true }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = DesktopAccent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                text,
                color = DesktopAccent,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Icon(BitChordIcons.ChevronRight, contentDescription = "Limits", tint = DesktopAccent, modifier = Modifier.size(10.dp))
        }
        DropdownMenu(expanded = open && config.enabled, onDismissRequest = { open = false }) {
            ForkAddonLimits.presets.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.description, maxLines = 1) },
                    trailingIcon = if (option == preset) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, tint = DesktopAccent) }
                    } else {
                        null
                    },
                    onClick = {
                        ForkDesktopAddonLimits.choose(config.baseUrl, option.id)
                        open = false
                    },
                )
            }
        }
    }
}
