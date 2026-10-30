package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.navidrome.NavidromeAlbums
import com.music.bitchord.data.navidrome.NavidromeArtists
import com.music.bitchord.data.navidrome.NavidromeClient
import com.music.bitchord.data.navidrome.NavidromeClips
import com.music.bitchord.data.navidrome.NavidromeLyrics
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.data.navidrome.NavidromePlaylists
import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.data.navidrome.NavidromeSongInfo
import com.music.bitchord.playback.QueueBuilder
import java.util.Base64
import java.util.prefs.Preferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Fork: the Navidrome library on the desktop. The client, library, lyrics, clips
 * and stars are the phone's, from `shared`; this is the desktop's side of
 * [NavidromePlatform], the sidebar page and the login.
 *
 * Nothing is kept offline here yet, so [NavidromePlatform.kept] stays empty.
 */
internal object ForkNavidrome {

    fun install() {
        NavidromeSettings.init(PrefsStore("com/music/bitchord/navidrome", sealed = setOf("password")))
        NavidromeClips.init(PrefsStore("com/music/bitchord/navidrome_clips", sealed = emptySet()))
        NavidromeSongInfo.init()
        NavidromeLyrics.init(DesktopMediaCache.directory.resolve("navidrome_lyrics").toFile())
    }

    fun owns(song: Song): Boolean = NavidromeSettings.isNavidromeId(song.videoId)

    /** Where a Navidrome song plays from, built from the current login rather than the address it was queued with. */
    fun stream(song: Song): DesktopStream? {
        if (!owns(song) || !NavidromeSettings.isConfigured()) return null
        return DesktopStream(NavidromeRepository.client().streamUrl(song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)))
    }

    /** The server's timed lyrics for a Navidrome song, or null: the online sources are asked next. */
    suspend fun lyrics(song: Song): List<LyricLine>? =
        if (owns(song)) NavidromeLyrics.forSong(song.videoId.removePrefix(NavidromeSettings.ID_PREFIX)) else null

    /**
     * A Navidrome seed's next songs, always from this library: "Start radio" is the server's
     * Instant Mix, AutoPlay its similar songs (random ones when it has no agent). Null for any
     * other seed, which goes to YouTube.
     */
    suspend fun tracks(existing: List<Song>, seed: Song, limit: Int, station: Boolean): Result<List<Song>>? {
        if (!owns(seed)) return null
        if (station) return NavidromeRepository.instantMix(seed)
        return NavidromeRepository.related(seed).map { related ->
            QueueBuilder.extend(existing, related, limit).map {
                it.copy(queueTier = QueueTier.AUTOPLAY, radioName = seed.radioName, playbackSource = seed.playbackSource)
            }
        }
    }

    fun ownsPage(browseId: String): Boolean =
        browseId.startsWith(NavidromeAlbums.PREFIX) || browseId.startsWith(NavidromeArtists.PREFIX) || browseId.startsWith(NavidromePlaylists.PREFIX)

    /** The page for a Navidrome album, artist or playlist id, or null for any other id. */
    suspend fun collection(pending: DesktopCollection): Result<DesktopCollection>? {
        val browseId = pending.browseId
        if (!ownsPage(browseId)) return null
        val empty = { "Nothing here" }
        val answer = NavidromeAlbums.pageState(browseId, empty)
            ?: NavidromeArtists.pageState(browseId, empty)
            ?: NavidromePlaylists.pageState(browseId, empty)
            ?: return Result.failure(IllegalStateException("Nothing here"))
        return when (answer) {
            is UiState.Success -> {
                val header = NavidromeAlbums.header(browseId)
                val name = browseId.removePrefix(NavidromeAlbums.PREFIX).removePrefix(NavidromeArtists.PREFIX)
                Result.success(
                    pending.copy(
                        title = pending.title.ifBlank { name },
                        type = if (browseId.startsWith(NavidromeAlbums.PREFIX)) BrowseType.ALBUM else BrowseType.PLAYLIST,
                        subtitle = header?.artist ?: pending.subtitle,
                        thumbnailUrl = header?.cover ?: pending.thumbnailUrl ?: answer.data.firstOrNull()?.thumbnailUrl,
                        songs = answer.data,
                        loading = false,
                    ),
                )
            }
            is UiState.Error -> Result.failure(IllegalStateException(answer.message))
            else -> Result.failure(IllegalStateException("Nothing here"))
        }
    }

    /** The server's album and playlist shelves for the top of Home; none without a login or a server. */
    suspend fun homeShelves(): List<HomeShelf> {
        if (!NavidromeSettings.isConfigured()) return emptyList()
        val client = NavidromeRepository.client()
        fun albums(rows: List<NavidromeClient.AlbumRow>) = rows.map { row ->
            NavidromeAlbums.remember(row.name, row.id)
            ShelfItem(
                title = row.name,
                subtitle = row.artist.orEmpty(),
                thumbnailUrl = row.coverArt?.let { client.coverUrl(it) },
                videoId = null,
                browseId = NavidromeSettings.albumPageId(row.name),
            )
        }.distinctBy { it.browseId }
        return runCatching {
            coroutineScope {
                val shelves = HOME_SHELVES.map { (type, title) ->
                    async { HomeShelf(title, albums(client.albums(type, PER_SHELF)), "Navidrome") }
                }
                val playlists = async {
                    val items = client.playlists().map { row ->
                        ShelfItem(
                            title = row.name,
                            subtitle = row.songCount?.let { "$it songs" }.orEmpty(),
                            thumbnailUrl = row.coverArt?.let { client.coverUrl(it) },
                            videoId = null,
                            browseId = NavidromePlaylists.pageId(row.id),
                        )
                    }
                    HomeShelf("Playlists", items, "Navidrome")
                }
                val loaded = shelves.awaitAll()
                (listOf(loaded.first(), playlists.await()) + loaded.drop(1)).filter { it.items.isNotEmpty() }
            }
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            DesktopTrackLog.log("navidrome: home shelves: ${it.message}")
            emptyList()
        }
    }

    private const val PER_SHELF = 20

    /** `getAlbumList2` types, in the order the phone shows them. */
    private val HOME_SHELVES = listOf(
        "newest" to "Recently added",
        "recent" to "Recents",
        "frequent" to "Most played",
        "random" to "Random",
    )

    /** The stars read so far, from the server's listings. */
    fun starred(): Set<String> =
        LikeState.overrides.value.filterValues { it == LikeStatus.LIKE }.keys.filterTo(HashSet(), NavidromeSettings::isNavidromeId)

    /**
     * Java Preferences (the registry, on Windows). A [sealed] value goes through the
     * Windows user's Data Protection API first, and where that isn't available it is
     * not written at all: the login then lasts until the app closes.
     */
    private class PrefsStore(path: String, private val sealed: Set<String>) : NavidromePlatform.Store {
        private val node = Preferences.userRoot().node(path)

        override fun string(key: String): String? {
            val stored = node.get(key, null) ?: return null
            if (key !in sealed || stored.isEmpty()) return stored
            return runCatching { DesktopWindowsCrypto.unprotect(Base64.getDecoder().decode(stored)) }.getOrNull()?.toString(Charsets.UTF_8)
        }

        override fun long(key: String): Long = node.getLong(key, 0L)

        override fun putStrings(values: Map<String, String>) {
            values.forEach { (key, value) ->
                when {
                    key !in sealed || value.isEmpty() -> node.put(key, value)
                    else -> {
                        val closed = DesktopWindowsCrypto.protect(value.toByteArray(Charsets.UTF_8))
                        if (closed != null) node.put(key, Base64.getEncoder().encodeToString(closed)) else node.remove(key)
                    }
                }
            }
            node.flush()
        }

        override fun putLong(key: String, value: Long) {
            node.putLong(key, value)
            node.flush()
        }

        override fun keys(): Set<String> = node.keys().toSet()

        override fun remove(keys: Collection<String>) {
            keys.forEach(node::remove)
            node.flush()
        }

        override fun clear() {
            node.clear()
            node.flush()
        }
    }
}

/**
 * The sidebar's "Navidrome" page: the login until there is one, then [page] with the
 * library's songs. [onStars] gets the stars the listing brought with it.
 */
@Composable
internal fun ForkNavidromeScreen(
    contentPadding: PaddingValues,
    onStars: (Set<String>) -> Unit,
    page: @Composable (List<Song>) -> Unit,
) {
    val url by NavidromeSettings.url.collectAsState()
    val username by NavidromeSettings.username.collectAsState()
    val saved by NavidromeSettings.saved.collectAsState()
    if (!NavidromeSettings.isConfigured(url, username)) {
        ForkNavidromeLogin(contentPadding)
        return
    }
    var songs by remember(saved) { mutableStateOf<List<Song>?>(null) }
    var failure by remember(saved) { mutableStateOf<String?>(null) }
    LaunchedEffect(saved) {
        runCatching { NavidromeRepository.getSongs() }
            .onSuccess {
                songs = it
                onStars(ForkNavidrome.starred())
            }
            .onFailure { failure = it.message ?: "Couldn't read the library" }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(contentPadding).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("$username on $url", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { NavidromeSettings.clear() }) { Text("Disconnect") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val loaded = songs
            when {
                loaded != null -> page(loaded)
                failure != null -> Text(failure.orEmpty(), Modifier.align(Alignment.Center).padding(24.dp))
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun ForkNavidromeLogin(contentPadding: PaddingValues) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var trying by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to Navidrome", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Server address") }, singleLine = true)
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
            OutlinedTextField(
                password,
                { password = it },
                Modifier.fillMaxWidth(),
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(4.dp))
            Button(
                enabled = !trying && NavidromeSettings.isConfigured(url, username),
                onClick = {
                    trying = true
                    failure = null
                    scope.launch {
                        val address = NavidromeSettings.normalizeUrl(url)
                        NavidromeRepository.testConnection(address, username.trim(), password)
                            .onSuccess { NavidromeSettings.save(address, username, password) }
                            .onFailure { failure = it.message ?: "Couldn't connect" }
                        trying = false
                    }
                },
            ) { Text(if (trying) "Connecting…" else "Connect") }
        }
    }
}
