package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.ForkAddonLimits
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.addon.ForkAddonLimitsPhone
import com.music.bitchord.ui.components.ChoiceAlert
import com.music.bitchord.ui.icons.SfIcons
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork: the Limits line under an addon's Sources row and its list ([ForkAddonLimitsPhone]). Shown by
 * [ForkAddonUserAgentLine] and [ForkAddonUserAgentAlert], so the upstream screens carry one hook each.
 */
object ForkAddonLimitsUi {
    val choosing = MutableStateFlow<SourceConfig?>(null)
}

/** "Limits: TorBox-backed (5/min, 60 uncached/h) · 2 of 5 this minute · 58 uncached left this hour". Addons only. */
@Composable
internal fun ForkAddonLimitsLine(config: SourceConfig) {
    if (config.kind != SourceKind.ADDON || !config.isComplete) return
    val presets by ForkAddonLimitsPhone.presets.collectAsState()
    val preset = ForkAddonLimitsPhone.presetFor(config.baseUrl)
    // What has been used moves on its own as the minute and hour pass, so it is re-read every few seconds.
    val usage by produceState<ForkAddonLimits.Usage?>(null, config.baseUrl, presets) {
        while (true) {
            value = ForkAddonLimitsPhone.usage(config.baseUrl)
            delay(5_000)
        }
    }
    val text = buildString {
        append("Limits: ")
        val rules = usage?.rules
        append(
            if (preset.id == ForkAddonLimits.AUTOMATIC && rules?.limited == true) {
                "Automatic (" + listOfNotNull(rules.streamsPerMinute?.let { "$it/min" }, rules.uncachedPerHour?.let { "$it uncached/h" }).joinToString(", ") + ")"
            } else {
                preset.description
            },
        )
        usage?.text?.ifBlank { null }?.let { append(" · ").append(it) }
    }
    Row(
        modifier = Modifier
            .clickable(onClickLabel = "Limits") { ForkAddonLimitsUi.choosing.value = config }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SfIcons.Clock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(2.dp))
        Icon(SfIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(10.dp))
    }
}

@Composable
internal fun ForkAddonLimitsAlert(hazeState: HazeState) {
    val config by ForkAddonLimitsUi.choosing.collectAsState()
    val open = config ?: return
    ChoiceAlert(
        hazeState = hazeState,
        title = "Limits",
        message = "How often this addon is asked for streams. Automatic uses what the addon declares, " +
            "else what it showed when it said \"too many requests\". Past a limit, BitChord waits, " +
            "or plays a cached copy or another source.",
        options = ForkAddonLimits.presets,
        selected = ForkAddonLimitsPhone.presetFor(open.baseUrl),
        label = { it.description },
        onSelect = { preset ->
            ForkAddonLimitsPhone.choose(open.baseUrl, preset.id)
            ForkAddonLimitsUi.choosing.value = null
        },
        onDismiss = { ForkAddonLimitsUi.choosing.value = null },
    )
}
