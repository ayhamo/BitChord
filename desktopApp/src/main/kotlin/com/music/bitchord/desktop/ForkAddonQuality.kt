package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fork: upstream v1.9.1's addon quality picker on the PC (2fe5f3c7, "addon quality support": the
 * phone's `AddonManifest.qualityOptions`, `AddonClient.settingsFor`, `SourceConfig.qualityOptions` /
 * `pickedQuality` / `activeQuality`, `SourceRegistry.updatePolicy`, the Sources row's quality line
 * and `MainActivity`'s list).
 *
 * An addon that declares a `quality` select with two or more values gets a line under its Sources
 * row naming the rendition in force; a click lists the addon's own options. As on the phone:
 *  - the options are kept from the manifest each time it is read ([record]), so the list can be set
 *    while the addon is down;
 *  - asking for the top tier sends the pick, or the addon's first listed option with none (matching
 *    the word "lossless" was dropped upstream: an addon may use it for its CD tier and list better
 *    ones above it); a pick the addon no longer lists falls back to its first option, and with no
 *    manifest in hand the pick goes as chosen ([valueFor]);
 *  - a lower tier (a download kept at High) still matches by words: a pick doesn't overrule what a
 *    file may cost.
 *
 * The PC's: the pick and the options live in their own preferences by source id
 * (`fork_addon_quality_<id>`, `fork_addon_quality_options_<id>`) instead of on the source's config,
 * which is upstream's saved shape; the client reads the pick live, and its cached answers are keyed
 * by the parameters sent, so no rebuild is needed when it changes.
 */
internal object ForkAddonQuality {

    @Serializable
    data class Option(val value: String, val label: String)

    private const val KEY_PICK = "fork_addon_quality_"
    private const val KEY_OPTIONS = "fork_addon_quality_options_"
    private const val QUALITY_KEY = "quality"

    private val persistence by lazy { DesktopPersistence() }
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Option.serializer())

    /** Options by source id, as last stored; read lazily per source. */
    private val _options = MutableStateFlow<Map<String, List<Option>>>(emptyMap())
    private val _picks = MutableStateFlow<Map<String, String?>>(emptyMap())
    val picks: StateFlow<Map<String, String?>> = _picks.asStateFlow()
    val options: StateFlow<Map<String, List<Option>>> = _options.asStateFlow()

    fun optionsOf(sourceId: String): List<Option> = _options.value[sourceId] ?: load(sourceId).first

    fun picked(sourceId: String): String? =
        if (_picks.value.containsKey(sourceId)) _picks.value[sourceId] else load(sourceId).second

    private fun load(sourceId: String): Pair<List<Option>, String?> {
        val stored = runCatching {
            persistence.string(KEY_OPTIONS + sourceId).takeIf { it.isNotBlank() }?.let { json.decodeFromString(listSerializer, it) }
        }.getOrNull().orEmpty()
        val pick = persistence.string(KEY_PICK + sourceId).ifBlank { null }
        _options.update { if (it.containsKey(sourceId)) it else it + (sourceId to stored) }
        _picks.update { if (it.containsKey(sourceId)) it else it + (sourceId to pick) }
        return stored to pick
    }

    /** The phone's `activeQuality`: the pick while the addon still lists it, else its first option. */
    fun active(sourceId: String): Option? {
        val list = optionsOf(sourceId)
        val pick = picked(sourceId)
        return list.firstOrNull { it.value == pick } ?: list.firstOrNull()
    }

    fun pick(sourceId: String, value: String?) {
        _picks.update { it + (sourceId to value) }
        persistence.saveString(KEY_PICK + sourceId, value.orEmpty())
        DesktopTrackLog.log("addon $sourceId: quality picked ${value ?: "(first listed)"}")
    }

    /** The phone's `AddonManifest.qualityOptions`: a declared `quality` select with two or more values. */
    private fun optionsIn(manifest: DesktopAddonManifest): List<Option> =
        manifest.settings.firstOrNull { it.key == QUALITY_KEY }
            ?.options.orEmpty()
            .mapNotNull { option ->
                val value = option.stringValue ?: return@mapNotNull null
                Option(value, option.label.text() ?: value)
            }
            .distinctBy { it.value }
            .takeIf { it.size > 1 }
            .orEmpty()

    private fun kotlinx.serialization.json.JsonElement?.text(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    /** From `DesktopAddonSource.record`, each time a manifest is read: stored when it changed (`updatePolicy`). */
    fun record(sourceId: String, manifest: DesktopAddonManifest) {
        val fresh = optionsIn(manifest)
        if (optionsOf(sourceId) == fresh) return
        DesktopTrackLog.log("addon $sourceId: manifest now offers qualityOptions=${fresh.map { it.value }}")
        _options.update { it + (sourceId to fresh) }
        persistence.saveString(KEY_OPTIONS + sourceId, json.encodeToString(listSerializer, fresh))
    }

    /**
     * The `quality` value sent for [tier] (the phone's `settingsFor`): below the top tier the PC's
     * own word match ([lower]); at it, the pick, or the first listed option.
     */
    fun valueFor(sourceId: String?, tier: String, listed: List<String>, manifestInHand: Boolean, lower: () -> String?): String? {
        if (!tier.equals(DesktopAddonClient.TIER_LOSSLESS, ignoreCase = true)) return lower()
        val pick = sourceId?.let(::picked)
        return if (!manifestInHand) pick else pick?.takeIf { it in listed } ?: listed.firstOrNull()
    }
}

/**
 * The quality line under an addon's Sources row (the phone's `SourceRow`): the rendition in force,
 * one line, and the addon's own list on a click. Nothing for an addon with no choice to offer.
 */
@Composable
internal fun ForkAddonQualityRow(
    config: DesktopSourceConfig,
    /** On but passed over at the audio quality in force, as `DesktopSourceSettingsRow` dims it. */
    skippedByQuality: Boolean = false,
) {
    if (config.kind != DesktopSourceKind.ADDON || !config.isComplete) return
    val options by ForkAddonQuality.options.collectAsState()
    val picks by ForkAddonQuality.picks.collectAsState()
    val list = options[config.id] ?: ForkAddonQuality.optionsOf(config.id)
    val pick = if (picks.containsKey(config.id)) picks[config.id] else ForkAddonQuality.picked(config.id)
    val active = list.firstOrNull { it.value == pick } ?: list.firstOrNull() ?: return
    var open by remember(config.id) { mutableStateOf(false) }
    // Greyed with its row, never hidden: an addon that is off or skipped has no pick in force.
    val usable = config.enabled && !skippedByQuality
    Box(Modifier.padding(start = 53.dp, bottom = 6.dp).alpha(if (usable) 1f else 0.45f)) {
        Row(
            Modifier
                .desktopRowClickable(enabled = usable) { open = true }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = DesktopAccent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                active.label,
                color = DesktopAccent,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Icon(BitChordIcons.ChevronRight, contentDescription = DesktopStrings["audio_quality", "Audio quality"], tint = DesktopAccent, modifier = Modifier.size(10.dp))
        }
        DropdownMenu(expanded = open && usable, onDismissRequest = { open = false }) {
            list.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = if (option == active) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, tint = DesktopAccent) }
                    } else {
                        null
                    },
                    onClick = {
                        ForkAddonQuality.pick(config.id, option.value)
                        open = false
                    },
                )
            }
        }
    }
}
