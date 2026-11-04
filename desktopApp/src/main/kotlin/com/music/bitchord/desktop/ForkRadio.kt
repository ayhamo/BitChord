package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.ui.components.contextClick
import java.security.MessageDigest
import kotlinx.coroutines.launch

/**
 * Fork: the server's internet radio stations on the desktop. The stations are the phone's
 * [NavidromeRadio]; what is special about a live stream is held at its own hook: the engine's
 * `load`, `seekTo` and `prepareNext`, Previous, the song menu, the canvas lookup and the scrobbles.
 */
internal object ForkRadio {

    const val TITLE = "Internet radio"
    private const val EMPTY = "No radio stations on your server. Add them in Navidrome (Radios)"

    fun install(store: NavidromePlatform.Store) = NavidromeRadio.init(HashedKeys(store))

    /**
     * The stations, for the page. A station's "Playing from" link goes nowhere here: on the phone
     * it opens the Library page, which the desktop's browse pages don't know, and a YouTube browse
     * of that id would only come back as an error page.
     */
    suspend fun stations(): UiState<List<Song>> {
        val answer = NavidromeRadio.pageState(NavidromeRadio.PAGE_ID) { EMPTY } ?: return UiState.Error(EMPTY)
        return if (answer is UiState.Success) UiState.Success(answer.data.map { it.copy(playbackSourceId = null) }) else answer
    }

    /**
     * The logo cache is keyed on a station's id and whole address. Java Preferences refuses a key
     * over 80 characters, which a stream address easily is, and the question would fail the page.
     */
    private class HashedKeys(private val store: NavidromePlatform.Store) : NavidromePlatform.Store by store {
        override fun string(key: String): String? = store.string(hash(key))

        override fun putStrings(values: Map<String, String>) = store.putStrings(values.mapKeys { hash(it.key) })

        private fun hash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Fork: the phone's "Internet radio" Library page, on the Navidrome page. A row plays the whole
 * list from there, so Next and Previous move between stations, as on the phone.
 */
@Composable
internal fun ForkRadioPage(
    playingId: String?,
    onPlay: (List<Song>, Int) -> Unit,
    menu: @Composable (Song) -> Unit,
) {
    var attempt by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<UiState<List<Song>>>(UiState.Loading) }
    LaunchedEffect(attempt) {
        state = UiState.Loading
        state = ForkRadio.stations()
    }
    when (val shown = state) {
        is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(shown.message)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { attempt++ }) { Text("Retry") }
            }
        }
        is UiState.Success -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "radio-title") {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(ForkRadio.TITLE, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(2.dp))
                    Text("Radio stations from your Navidrome server", style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
                }
            }
            itemsIndexed(shown.data, key = { _, station -> station.videoId }) { index, station ->
                ForkRadioRow(station, station.videoId == playingId, onClick = { onPlay(shown.data, index) }, menu = menu)
            }
        }
    }
}

@Composable
private fun ForkRadioRow(station: Song, playing: Boolean, onClick: () -> Unit, menu: @Composable (Song) -> Unit) {
    val openContextMenu = LocalDesktopContextMenu.current
    Row(
        Modifier
            .fillMaxWidth()
            .contextClick { openContextMenu(station) }
            .clip(RoundedCornerShape(10.dp))
            .background(DesktopGlass.copy(alpha = 0.38f))
            .desktopHoverFill(corner = 10.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopArtwork(station.thumbnailUrl, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.title,
                color = if (playing) Color.White else Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(
                station.artist,
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        menu(station)
    }
}

/**
 * Fork: a station whose server closes the stream cleanly (a restart, a dropped mount, an idle
 * timeout) reads to the engine as a track that ended, and the queue moved on as if the song had
 * finished. A live stream has no end: it is opened again after [DELAY_MS], up to [MAX] times a
 * minute, then let go as before (a server that keeps closing it isn't coming back).
 */
internal object ForkRadioReconnect {
    private const val MAX = 5
    private const val WINDOW_MS = 60_000L
    private const val DELAY_MS = 1_000L

    private val recent = ArrayDeque<Long>()

    /**
     * Called as [song] runs out. False for anything but a station, or one out of tries: the engine
     * ends it as usual. True: [reopen] runs in a moment on [scope], while [stillCurrent] says
     * nothing else was started meanwhile.
     */
    fun closed(
        song: Song,
        scope: kotlinx.coroutines.CoroutineScope,
        stillCurrent: () -> Boolean,
        now: Long = System.currentTimeMillis(),
        reopen: () -> Unit,
    ): Boolean {
        if (!NavidromeRadio.isRadio(song.videoId)) return false
        synchronized(recent) {
            while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) recent.removeFirst()
            if (recent.size >= MAX) return false
            recent.addLast(now)
        }
        DesktopTrackLog.log("radio: '${song.title}' closed by its server, opening it again")
        scope.launch {
            kotlinx.coroutines.delay(DELAY_MS)
            if (stillCurrent()) reopen()
        }
        return true
    }

    /** For tests: forget the tries counted so far. */
    internal fun reset() = synchronized(recent) { recent.clear() }
}
