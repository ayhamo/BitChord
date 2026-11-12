package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
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
import com.music.bitchord.data.ForkAddonUserAgents
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fork: the PC's per-addon User-Agent choice ([ForkAddonUserAgents] says why), the phone's
 * `ForkAddonUserAgent`. Kept in the PC's preferences by addon host (`fork_addon_ua_<host>`);
 * `DesktopAddonClient.body` and `DesktopSourceFormats.fetch` send [agent], adding an addon goes
 * through [ForkAddonUserAgents.retryingRefused], and the Sources row shows [ForkAddonUserAgentRow].
 */
internal object ForkDesktopAddonUserAgent : ForkAddonUserAgents.Store {

    private const val KEY = "fork_addon_ua_"
    private val persistence by lazy { DesktopPersistence() }

    /** Host to preset id; a host read once is cached here, "" meaning never chosen. */
    private val _picks = MutableStateFlow<Map<String, String>>(emptyMap())
    val picks: StateFlow<Map<String, String>> = _picks.asStateFlow()

    override fun picked(host: String): String? {
        val known = _picks.value[host] ?: persistence.string(KEY + host).also { stored -> _picks.update { it + (host to stored) } }
        return known.ifBlank { null }
    }

    override fun pick(host: String, presetId: String?) {
        _picks.update { it + (host to presetId.orEmpty()) }
        persistence.saveString(KEY + host, presetId.orEmpty())
        DesktopTrackLog.log("addon $host: user agent ${presetId ?: "(BitChord)"}")
    }

    /** The agent for a request to [url], or [appAgent] for an addon left on the default. */
    fun agent(url: String, appAgent: String): String =
        ForkAddonUserAgents.agentFor(ForkAddonUserAgents.hostOf(url)?.let(::picked), appAgent)

    fun choose(baseUrl: String, presetId: String) {
        val host = ForkAddonUserAgents.hostOf(baseUrl) ?: return
        pick(host, presetId.takeUnless { it == ForkAddonUserAgents.BITCHORD })
    }
}

/** Under the quality line, styled like it (`ForkAddonQualityRow`): the agent this addon is called with. */
@Composable
internal fun ForkAddonUserAgentRow(config: DesktopSourceConfig) {
    if (config.kind != DesktopSourceKind.ADDON || !config.isComplete) return
    val host = ForkAddonUserAgents.hostOf(config.baseUrl) ?: return
    val picks by ForkDesktopAddonUserAgent.picks.collectAsState()
    val active = ForkAddonUserAgents.preset(picks[host]?.ifBlank { null } ?: ForkDesktopAddonUserAgent.picked(host))
    var open by remember(config.id) { mutableStateOf(false) }
    // Greyed with its row, never hidden.
    Box(Modifier.padding(start = 53.dp, bottom = 6.dp).alpha(if (config.enabled) 1f else 0.45f)) {
        Row(
            Modifier
                .desktopRowClickable(enabled = config.enabled) { open = true }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Language, contentDescription = null, tint = DesktopAccent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "User agent: ${active.label}",
                color = DesktopAccent,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Icon(BitChordIcons.ChevronRight, contentDescription = "User agent", tint = DesktopAccent, modifier = Modifier.size(10.dp))
        }
        DropdownMenu(expanded = open && config.enabled, onDismissRequest = { open = false }) {
            ForkAddonUserAgents.presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label, maxLines = 1) },
                    trailingIcon = if (preset == active) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, tint = DesktopAccent) }
                    } else {
                        null
                    },
                    onClick = {
                        ForkDesktopAddonUserAgent.choose(config.baseUrl, preset.id)
                        open = false
                    },
                )
            }
        }
    }
    ForkAddonLimitsRow(config) // the addon's limits, under its agent (ForkDesktopAddonLimits)
}
