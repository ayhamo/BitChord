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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.ForkAddonUserAgents
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.addon.ForkAddonUserAgent
import com.music.bitchord.ui.components.ChoiceAlert
import com.music.bitchord.ui.icons.SfIcons
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow

/** Fork: the User-Agent line under an addon's Sources row, and its list (see [ForkAddonUserAgent]). */
object ForkAddonUserAgentUi {
    /** The addon whose list is open; `MainActivity` shows [ForkAddonUserAgentAlert] at its root for it. */
    val choosing = MutableStateFlow<SourceConfig?>(null)
}

/** Under the quality line, styled like it: the agent this addon is called with, then its [ForkAddonLimitsLine]. Addons only. */
@Composable
internal fun ForkAddonUserAgentLine(config: SourceConfig) {
    if (config.kind != SourceKind.ADDON || !config.isComplete) return
    val picks by ForkAddonUserAgent.picks.collectAsState()
    val preset = ForkAddonUserAgents.preset(ForkAddonUserAgents.hostOf(config.baseUrl)?.let { picks[it] })
    Row(
        modifier = Modifier
            .clickable(onClickLabel = "User agent") { ForkAddonUserAgentUi.choosing.value = config }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SfIcons.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = "User agent: ${preset.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(2.dp))
        Icon(SfIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(10.dp))
    }
    ForkAddonLimitsLine(config) // the addon's limits, under its agent
}

/** The presets for the addon in [ForkAddonUserAgentUi.choosing]; at the root, as the quality list is. */
@Composable
internal fun ForkAddonUserAgentAlert(hazeState: HazeState) {
    ForkAddonLimitsAlert(hazeState) // the limits list rides on this hook too
    val config by ForkAddonUserAgentUi.choosing.collectAsState()
    val open = config ?: return
    ChoiceAlert(
        hazeState = hazeState,
        title = "User agent",
        message = "What this addon is told the app is. Some addons refuse BitChord's own; a browser's gets through.",
        options = ForkAddonUserAgents.presets,
        selected = ForkAddonUserAgent.presetFor(open.baseUrl),
        label = { it.label },
        onSelect = { preset ->
            ForkAddonUserAgent.choose(open.baseUrl, preset.id)
            ForkAddonUserAgentUi.choosing.value = null
        },
        onDismiss = { ForkAddonUserAgentUi.choosing.value = null },
    )
}
