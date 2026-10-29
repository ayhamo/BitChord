package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.fork.ForkSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * The fork's switches as full settings rows (fork-only), for the "Fork
 * settings" group at the end of Settings. Each one is the same setting as the
 * row where it also lives in the normal sections (the same [ForkSettings]
 * flow), so flipping either moves both.
 */
@Composable
internal fun ForkCrossfadeOnSkipRow() = ForkSwitchRow(
    icon = Icons.Rounded.Waves,
    title = stringResource(R.string.fork_crossfade_on_skip),
    subtitle = stringResource(R.string.fork_crossfade_on_skip_subtitle),
    state = ForkSettings.crossfadeOnSkip,
    onChange = ForkSettings::setCrossfadeOnSkip,
)

@Composable
internal fun ForkCoverClipsRow() = ForkSwitchRow(
    icon = Icons.Rounded.Animation,
    title = stringResource(R.string.fork_cover_clips),
    subtitle = stringResource(R.string.fork_cover_clips_subtitle),
    state = ForkSettings.coverClips,
    onChange = ForkSettings::setCoverClips,
)

@Composable
internal fun ForkBackdropClipsRow() = ForkSwitchRow(
    icon = Icons.Rounded.Wallpaper,
    title = stringResource(R.string.fork_backdrop_clips),
    subtitle = stringResource(R.string.fork_backdrop_clips_subtitle),
    state = ForkSettings.backdropClips,
    onChange = ForkSettings::setBackdropClips,
)

@Composable
internal fun ForkNavidromeScrobbleRow() = ForkSwitchRow(
    icon = Icons.Rounded.Cloud,
    title = stringResource(R.string.fork_navidrome_scrobble),
    subtitle = stringResource(R.string.fork_navidrome_scrobble_subtitle),
    state = ForkSettings.navidromeScrobble,
    onChange = ForkSettings::setNavidromeScrobble,
)

@Composable
private fun ForkSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    state: StateFlow<Boolean>,
    onChange: (Boolean) -> Unit,
) {
    val checked by state.collectAsStateWithLifecycle()
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { onChange(!checked) },
    )
}
