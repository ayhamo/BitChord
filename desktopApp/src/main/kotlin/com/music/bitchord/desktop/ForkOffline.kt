package com.music.bitchord.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.music.bitchord.data.Http
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeOffline
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.data.navidrome.NavidromeSettings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Fork: "Available offline" on the desktop, the phone's [NavidromeOffline] with a page's button,
 * the Navidrome page's list and offline mode.
 *
 * Offline is the switch, or the server not answering. The phone asks Android whether there is a
 * network; a desktop usually has adapters up with nothing behind them (Docker, VPNs), and the
 * question that matters here is whether the music can be reached anyway.
 */
internal object ForkOffline {

    private const val PROBE_MS = 20_000L
    private const val PROBE_TIMEOUT_MS = 3_000
    private const val MISSES_TO_OFFLINE = 2

    private const val KEY_MODE = "offline_mode"
    private const val IMAGE_CACHE_BYTES = 32L * 1024 * 1024

    private val prefs = Preferences.userRoot().node("com/music/bitchord/navidrome_fork")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mode = MutableStateFlow(prefs.getBoolean(KEY_MODE, false))
    private val reachable = MutableStateFlow(true)
    @Volatile private var misses = 0

    /** Offline mode: switched on, or the server isn't answering. */
    val offline: StateFlow<Boolean> = combine(mode, reachable) { switched, server -> switched || !server }
        .stateIn(scope, SharingStarted.Eagerly, mode.value)

    val switched: StateFlow<Boolean> = mode

    fun setMode(on: Boolean) {
        mode.value = on
        prefs.putBoolean(KEY_MODE, on)
    }

    fun install() {
        NavidromePlatform.kept = NavidromeOffline.Files
        NavidromePlatform.offline = offline
        NavidromePlatform.awaitDownload = { offline.first { !it } }
        NavidromePlatform.onlineLyrics = { song ->
            DesktopLyricsClient.lookup(song).getOrNull()?.lines?.takeIf { it.isNotEmpty() }?.joinToString("\n", transform = ::lrcLine)
        }
        NavidromeOffline.init(dataDirectory(), "Available offline", "Deleted on server")
        installImageLoader()
        scope.launch {
            while (true) {
                // Twice in a row: going offline reloads Home and the library, too much for one lost packet.
                if (serverAnswers()) {
                    misses = 0
                    reachable.value = true
                } else if (++misses >= MISSES_TO_OFFLINE) {
                    reachable.value = false
                }
                delay(PROBE_MS)
            }
        }
        // A new login isn't judged by the last server's answer.
        scope.launch {
            NavidromeSettings.saved.drop(1).collect {
                misses = 0
                reachable.value = true
            }
        }
    }

    /** The app's data folder rather than a cache, so nothing evicts it; the analyser's convention. */
    private fun dataDirectory(): File {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.local/share"
        return File(base, "bitchord/navidrome_offline")
    }

    /** True without a login: there is nothing to be cut off from. */
    private fun serverAnswers(): Boolean {
        if (!NavidromeSettings.isConfigured()) return true
        val url = NavidromeSettings.url.value.toHttpUrlOrNull() ?: return true
        return runCatching {
            Socket().use { it.connect(InetSocketAddress(url.host, url.port), PROBE_TIMEOUT_MS) }
        }.isSuccess
    }

    private fun lrcLine(line: LyricLine): String {
        val ms = line.timeMs
        return "[%02d:%02d.%02d]%s".format(ms / 60_000, ms / 1_000 % 60, ms / 10 % 100, line.text)
    }

    /**
     * The image loader the shared player's covers come through: over the app's HTTP client, whose
     * interceptor answers kept covers (Coil's own client would ask a server that may not be there),
     * and with a smaller memory cache than Coil's share of the heap, which holds few of these anyway.
     */
    fun installImageLoader() {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
                .memoryCache { MemoryCache.Builder().maxSizeBytes(IMAGE_CACHE_BYTES).build() }
                .build()
        }
    }

    /** A kept cover's bytes for an artwork address, or null to fetch it. */
    fun keptImage(url: String): ByteArray? {
        if ("getCoverArt" !in url) return null
        return runCatching {
            NavidromeOffline.serve(Request.Builder().url(url).build())?.use { it.body.bytes() }
        }.getOrNull()
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes / (1L shl 20)} MB"
}

/**
 * Fork: "Available offline" on a server album or playlist page. A tap keeps the page, stops it
 * while it comes down, and lets it go once kept; nothing for any other page.
 */
@Composable
internal fun ForkOfflineButton(id: String, title: String, subtitle: String, cover: String?, songs: List<Song>) {
    if (!NavidromeOffline.canKeep(id) || songs.isEmpty()) return
    val pages by NavidromeOffline.pages.collectAsState()
    val progress by NavidromeOffline.progress.collectAsState()
    val swept by NavidromeOffline.swept.collectAsState()
    val running = progress[id]
    val kept = id in pages
    val missing = remember(id, pages, running == null) { NavidromeOffline.missing(id) }
    val bytes by produceState(0L, id, pages, running == null, swept) {
        value = withContext(Dispatchers.IO) { NavidromeOffline.pageBytes(id) }
    }
    val estimate = remember(id, songs, pages, swept) { NavidromeOffline.estimate(songs) }
    val label = when {
        running != null -> "${(running * 100).toInt()}%"
        kept && missing > 0 -> "Kept ${pages[id]!!.songs.size - missing} of ${pages[id]!!.songs.size} · ${sizeText(bytes)} · keep the rest"
        kept -> "Kept · ${sizeText(bytes)} · remove"
        estimate != null -> "Available offline · ${sizeText(estimate)}"
        else -> "Available offline"
    }
    TextButton(
        onClick = {
            when {
                running != null -> NavidromeOffline.stop(id)
                kept && missing == 0 -> NavidromeOffline.remove(id)
                else -> NavidromeOffline.keep(id, title, subtitle, cover, songs)
            }
        },
    ) {
        Icon(
            when {
                running != null -> Icons.Rounded.Downloading
                kept -> Icons.Rounded.OfflinePin
                else -> Icons.Rounded.DownloadForOffline
            },
            null,
            Modifier.size(18.dp),
            tint = if (kept) DesktopAccent else Color.White,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

/** Fork: the Navidrome page's offline switch and the list of what is kept. */
@Composable
internal fun ForkOfflineControls(onOpenPage: (String) -> Unit) {
    val offline by ForkOffline.offline.collectAsState()
    val switched by ForkOffline.switched.collectAsState()
    val pages by NavidromeOffline.pages.collectAsState()
    var listing by remember { mutableStateOf(false) }
    if (offline && !switched) {
        Text("Server not answering: music on this PC only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(16.dp))
    }
    TextButton(onClick = { listing = true }) { Text("Available offline · ${pages.size}", style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.width(8.dp))
    Text("Offline mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(8.dp))
    Switch(checked = switched, onCheckedChange = ForkOffline::setMode)
    if (listing) {
        ForkOfflineList(
            onOpen = {
                listing = false
                onOpenPage(it)
            },
            onDismiss = { listing = false },
        )
    }
}

@Composable
private fun ForkOfflineList(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val pages by NavidromeOffline.pages.collectAsState()
    val swept by NavidromeOffline.swept.collectAsState()
    val rows = pages.values.sortedBy { it.title.lowercase() }
    val sizes by produceState(emptyMap<String, Long>(), pages, swept) {
        value = withContext(Dispatchers.IO) { pages.keys.associateWith(NavidromeOffline::pageBytes) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Available offline") },
        text = {
            if (rows.isEmpty()) {
                Text("Nothing kept yet. Keep an album or playlist from its page.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows, key = { it.id }) { page ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onOpen(page.id) }.padding(vertical = 6.dp)) {
                                Text(page.title, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(2.dp))
                                val note = if (page.gone) "Deleted on server · " else ""
                                Text(
                                    "$note${page.songs.size} songs · ${sizeText(sizes[page.id] ?: 0L)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { NavidromeOffline.remove(page.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (rows.size > 1) {
            { TextButton(onClick = { NavidromeOffline.removeAll() }) { Text("Remove all") } }
        } else {
            null
        },
    )
}
