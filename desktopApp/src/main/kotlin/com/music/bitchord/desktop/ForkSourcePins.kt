package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Fork (PC-Continued 5b1b855c, `DesktopSourcePins`): the tracks the listener named a source for,
 * from the Audio Pipeline panel.
 *
 * The walk that picks a stream normally ranks the sources and takes the first that answers; this is
 * the exception to it, the general form of [DesktopOriginalVersion] (which only sends a row back to
 * YouTube): the Tidal copy rather than the Qobuz one, or YouTube's own upload rather than a module's.
 *
 * A *request*, not a guarantee: a source with no copy of the track must not fail playback, so
 * [DesktopSourceRegistry.resolve] asks the named source alone and, when that comes back empty,
 * walks the usual order. PC only: the phone's shared `SourceResolver` is untouched.
 *
 * Stored under upstream's own preference key (`source_pins`, `videoId<TAB>sourceId` lines), so a
 * later merge of PC-Continued's `DesktopPersistence.sourcePins()` reads the same data.
 */
internal object ForkSourcePins {

    private const val KEY = "source_pins"

    /** Bumped on every change and read by [of], so the panel recomposes on it. */
    private var revision by androidx.compose.runtime.mutableIntStateOf(0)

    private val pins = ConcurrentHashMap<String, String>().apply {
        runCatching {
            DesktopPreferenceChunks.read(DesktopPersistence().preferences, KEY).orEmpty()
                .split('\n')
                .forEach { line ->
                    val videoId = line.substringBefore('\t', "")
                    val sourceId = line.substringAfter('\t', "")
                    if (videoId.isNotBlank() && sourceId.isNotBlank()) put(videoId, sourceId)
                }
        }
    }

    /** The source [videoId] was told to play from, or null for the usual ranking. */
    fun of(videoId: String?): String? {
        @Suppress("UNUSED_EXPRESSION") revision
        return if (videoId.isNullOrBlank()) null else pins[videoId]
    }

    /** Serves [videoId] from [sourceId] while that source can; null goes back to the usual order. */
    fun set(videoId: String, sourceId: String?) {
        if (videoId.isBlank()) return
        val changed = if (sourceId == null) pins.remove(videoId) != null else pins.put(videoId, sourceId) != sourceId
        if (!changed) return
        revision++
        runCatching {
            DesktopPreferenceChunks.write(
                DesktopPersistence().preferences,
                KEY,
                pins.entries.joinToString("\n") { (id, source) -> "$id\t$source" },
            )
        }
    }

    /**
     * [available] cut to the pinned source alone, or left whole when nothing is pinned or the pinned
     * source isn't in it (switched off, or kept out by the quality ceiling): the ordinary ranking is
     * the answer to that, not a failure to find. Generic because the registry's adapter type is
     * private to it.
     */
    fun <T> narrow(available: List<T>, pinned: String?, idOf: (T) -> String): List<T> {
        if (pinned == null) return available
        val only = available.firstOrNull { idOf(it) == pinned } ?: return available
        DesktopTrackLog.log("  this track is set to play from $pinned")
        return listOf(only)
    }
}

/**
 * Fork (PC-Continued 5b1b855c): where the music comes out, at the top of the Audio Pipeline panel,
 * since that is what the quality badge is most often opened to change. Enumerated when the panel
 * opens rather than held, as in [DesktopAudioOutputDialog]; a pick takes effect at once.
 */
@Composable
internal fun ForkPipelineOutputs() {
    val selected by DesktopAudioDevices.selected.collectAsState()
    val changes by DesktopAudioDevices.changes.collectAsState()
    val devices = remember(changes) { runCatching { DesktopAudioDevices.available() }.getOrDefault(emptyList()) }
    ForkChoiceGroup(DesktopStrings["audio_output", "Audio output"]) {
        ForkChoiceRow(
            DesktopStrings["d_system_default", "System default"],
            detail = null,
            selected = selected == DesktopAudioDevices.SYSTEM_DEFAULT,
            onClick = { DesktopAudioDevices.select(DesktopAudioDevices.SYSTEM_DEFAULT) },
        )
        devices.forEach { device ->
            ForkChoiceRow(
                device.name,
                detail = device.description,
                selected = selected == device.id,
                onClick = { DesktopAudioDevices.select(device.id) },
            )
        }
    }
}

/**
 * Fork (PC-Continued 5b1b855c): which source plays the playing track, the last word below the chain
 * it overrides. Choosing re-opens the track with the source cache cleared, so the named source is
 * actually asked rather than a cached "no copy" from the walk that picked the current one.
 *
 * Only enabled, complete sources are listed (as upstream): a disabled source is not a dependent
 * setting of this panel but a choice that can't serve. A source the quality ceiling keeps out is
 * listed greyed, since it can serve once the ceiling allows it.
 */
@Composable
internal fun ForkPipelineSourceChooser() {
    val engine = ForkDebugBridge.engine ?: return
    // Only the song and its source, not the whole state: that carries the position and would
    // recompose the panel several times a second (the same care ForkAudioPipelineDialog takes).
    val playing by remember(engine) {
        engine.state.map { it.song?.videoId to it.streamSourceId }.distinctUntilChanged()
    }.collectAsState(engine.state.value.let { it.song?.videoId to it.streamSourceId })
    val videoId = playing.first?.takeIf(String::isNotBlank) ?: return
    val playingSourceId = playing.second
    // A server's song has one copy; there is nothing to choose between.
    if (com.music.bitchord.data.navidrome.NavidromeSettings.isNavidromeId(videoId)) return
    var opened by remember { mutableIntStateOf(0) }
    val sources = remember(opened) {
        runCatching { DesktopPersistence().sourceConfigs().inSourceOrder().filter { it.enabled && it.isComplete } }
            .getOrDefault(emptyList())
    }
    if (sources.isEmpty()) return
    val ceiling = remember(opened) { runCatching { DesktopPersistence().audioQuality() }.getOrNull() }
    val pinned = ForkSourcePins.of(videoId)
    val choose: (String?) -> Unit = { sourceId ->
        ForkSourcePins.set(videoId, sourceId)
        opened++
        engine.reloadCurrent(forceSourceRefresh = true)
    }
    ForkChoiceGroup(DesktopStrings["d_play_from", "Play this from"]) {
        ForkChoiceRow(
            DesktopStrings["d_play_from_auto", "Automatic — the usual order"],
            detail = null,
            selected = pinned == null,
            onClick = { if (pinned != null) choose(null) },
        )
        sources.forEach { source ->
            val allowed = ceiling?.permits(source.kind) != false
            ForkChoiceRow(
                source.displayName,
                detail = when {
                    !allowed -> DesktopStrings["d_play_from_ceiling", "Above the quality setting"]
                    source.id == playingSourceId -> DesktopStrings["now_playing", "Now playing"]
                    else -> null
                },
                selected = source.id == pinned,
                enabled = allowed,
                onClick = { if (source.id != pinned) choose(source.id) },
            )
        }
    }
    Text(
        DesktopStrings[
            "d_track_info_hint",
            "Applies to this track only. BitChord falls back to the usual order when the source you picked has no copy of it.",
        ],
        color = DesktopSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 8.dp),
    )
}

@Composable
private fun ForkChoiceGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
        Text(
            title.uppercase(),
            color = DesktopSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(10.dp)).padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun ForkChoiceRow(
    name: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Greyed, never hidden: a row that can't be picked right now still says it exists.
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier.fillMaxWidth().desktopRowClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = Color.White.copy(alpha = alpha),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    color = DesktopSecondary.copy(alpha = alpha),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (selected) {
            Icon(
                BitChordIcons.Check,
                DesktopStrings["selected", "Selected"],
                tint = DesktopAccent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
