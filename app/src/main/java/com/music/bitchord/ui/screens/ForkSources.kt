package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.ui.components.FieldConfig
import com.music.bitchord.ui.components.ServerEditorHost
import dev.chrisbanes.haze.HazeState

/**
 * The Navidrome server as a personal library, beside WebDAV and SMB (fork-only):
 * its section in Sources, its server editor, and its links in the Library. In
 * here so `SourcesScreen`, `MainActivity` and `LibraryPhoneParts` each carry one line.
 */

/** Whether the server editor is open; it opens from [ForkNavidromeSourceSection] and draws in [ForkNavidromeEditor]. */
private val editorOpen = mutableStateOf(false)

@Composable
internal fun ForkNavidromeSourceSection() {
    val url by NavidromeSettings.url.collectAsStateWithLifecycle()
    val user by NavidromeSettings.username.collectAsStateWithLifecycle()
    val password by NavidromeSettings.password.collectAsStateWithLifecycle()
    RemoteLibrarySection(
        icon = Icons.Rounded.Cloud,
        title = stringResource(R.string.navidrome),
        description = stringResource(R.string.navidrome_description),
        subtitle = if (url.isBlank()) stringResource(R.string.navidrome_not_configured) else "$url · $user",
        listSubtitle = stringResource(R.string.navidrome_subtitle),
        disconnectTitle = stringResource(R.string.webdav_disconnect),
        configured = url.isNotBlank(),
        testFailedRes = R.string.navidrome_test_failed,
        onEdit = { editorOpen.value = true },
        onTest = { NavidromeRepository.testConnection(url, user, password) },
        onDisconnect = { NavidromeSettings.clear() },
    )
}

/** The window-level server editor, as the WebDAV and SMB ones; draws nothing until opened. */
@Composable
fun ForkNavidromeEditor(hazeState: HazeState) {
    if (!editorOpen.value) return
    BackHandler { editorOpen.value = false }
    ServerEditorHost(
        hazeState = hazeState,
        title = stringResource(R.string.navidrome),
        description = stringResource(R.string.navidrome_description),
        fields = listOf(
            FieldConfig(
                initial = NavidromeSettings.url.value,
                placeholder = stringResource(R.string.navidrome_server_url_hint),
                keyboardType = KeyboardType.Uri,
            ),
            FieldConfig(
                initial = NavidromeSettings.username.value,
                placeholder = stringResource(R.string.username),
            ),
            FieldConfig(
                initial = NavidromeSettings.password.value,
                placeholder = stringResource(R.string.password),
                keyboardType = KeyboardType.Password,
                isPassword = true,
            ),
        ),
        canSubmit = { it[0].isNotBlank() && it[1].isNotBlank() },
        testFailedRes = R.string.navidrome_test_failed,
        onTest = { (url, username, password) -> NavidromeRepository.testConnection(url.trim(), username.trim(), password) },
        onSave = { (url, username, password) ->
            NavidromeSettings.save(url, username, password)
            editorOpen.value = false
        },
        onDismiss = { editorOpen.value = false },
    )
}

/** The Navidrome library's icon in the Library's folder list. */
internal val ForkNavidromeIcon: ImageVector get() = Icons.Rounded.LibraryMusic

/** The Library's Navidrome links, through upstream's own [link] builder; radio needs the network. */
@Composable
internal fun forkLibraryLinks(link: (title: String, subtitle: String, browseId: String, icon: ImageVector?) -> LibraryLink): Array<LibraryLink> {
    val url by NavidromeSettings.url.collectAsStateWithLifecycle()
    val user by NavidromeSettings.username.collectAsStateWithLifecycle()
    val offline by ForkLibraryPlaylists.offlineMode.collectAsStateWithLifecycle()
    if (user.isBlank() || url.isBlank()) return emptyArray()
    val library = link(stringResource(R.string.navidrome), stringResource(R.string.navidrome_subtitle), NavidromeSettings.BROWSE_ID, ForkNavidromeIcon)
    val radio = link(stringResource(R.string.fork_radio), stringResource(R.string.fork_radio_subtitle), NavidromeRadio.PAGE_ID, Icons.Rounded.Radio)
    return if (offline) arrayOf(library) else arrayOf(library, radio)
}

/** A folder page's first tab: Navidrome opens on its albums, the rest on upstream's default. */
fun LocalFolderState.forkDefaults(browseId: String): LocalFolderState = apply {
    if (browseId == NavidromeSettings.BROWSE_ID) tab = LocalFolderTab.ALBUMS
}
