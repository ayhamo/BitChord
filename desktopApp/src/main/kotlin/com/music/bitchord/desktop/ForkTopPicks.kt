package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.AppleArtistPhotos
import com.music.bitchord.data.TopPicks
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickKind
import com.music.bitchord.data.model.TopPickMood
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.ui.screens.MoodGridPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

/**
 * Fork (owner, 2026-11-06): Home's "Top Picks for You" row and "Find Your Mood" shelf on the PC,
 * as the phone has them. The shared `HomeScreen` draws both only for a caller that passes
 * `onTopPickClick` / `onMoodClick`, and the PC passed neither, so its Home had neither.
 *
 * The phone's `MainViewModel.loadTopPicks` / `buildTopPicks` / `localTaste` and `MainActivity`'s
 * `openTopPick` / `playMood`, over the same shared builder (`data/TopPicks`): a row lasts one hour
 * of the clock and is kept on disk, so Home paints the last one at once and builds the next in the
 * background; the local taste is this PC's own Replay for the month.
 *
 * Find Your Mood plays the mood's playlists as a watch queue (`YtMusicRepository.playlistAsAudio`),
 * so YouTube hands over each video's song version, as on the phone.
 */
internal object ForkTopPicks {

    private val _picks = MutableStateFlow<List<TopPick>?>(null)
    val picks: StateFlow<List<TopPick>?> = _picks

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private var job: Job? = null
    private var listener: String? = null
    /** The hour this listener's row was last settled for: shown from disk, built, or tried and failed. */
    private var doneSlot: String? = null
    private var requests = 0

    /** The moods grid (Find Your Mood's Show all), opened as a Home page so Back leaves it. */
    const val MOODS_PAGE_ID = "fork:moods"

    fun moodsPage(title: String) = HomeShelf(title, emptyList(), moreBrowseId = MOODS_PAGE_ID)

    fun isMoodsPage(shelf: HomeShelf?) = shelf?.moreBrowseId == MOODS_PAGE_ID

    /**
     * Puts the row on screen for [identity] (who is signed in). Within the hour it was built in, a
     * stored row is shown and nothing is asked; past it, the stored row stays up while the next one
     * builds. [homeShelves] is Home's first page once it has landed, where the account's mixes are.
     */
    fun load(scope: CoroutineScope, identity: String?, homeShelves: suspend () -> List<HomeShelf>) {
        val slot = slot()
        if (upToDate(identity, listener, job?.isActive == true, doneSlot, slot)) return
        job?.cancel()
        listener = identity
        job = scope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { file().takeIf { it.exists() }?.readText() }.getOrNull()?.let(TopPicks::decode)
            }?.takeIf { it.listener == identity && it.picks.isNotEmpty() }
            stored?.let { AppleArtistPhotos.seed(it.photos) }
            _picks.value = stored?.picks
            if (stored?.day == slot) {
                _loading.value = false
                doneSlot = slot
                return@launch
            }
            _loading.value = _picks.value == null
            val built = try {
                // The Replay read is a preference read and a sort: off the window's thread, as the app's own.
                val taste = withContext(Dispatchers.IO) { localTaste() }
                TopPicks.build(homeShelves(), taste).takeIf { it.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DesktopTrackLog.log("topPicks: failed: ${e.message}")
                null
            }
            if (identity != listener) return@launch
            _loading.value = false
            // Tried for this hour either way: a failure is not asked again at every focus.
            doneSlot = slot
            if (built == null) {
                if (_picks.value == null) _picks.value = emptyList()
                return@launch
            }
            _picks.value = built
            DesktopTrackLog.log("topPicks: ${built.size} cards (${built.joinToString { it.kind.name }})")
            withContext(Dispatchers.IO) {
                runCatching {
                    file().apply { parentFile?.mkdirs() }
                        .writeText(TopPicks.encode(TopPicks.Stored(identity, slot, built, AppleArtistPhotos.snapshot())))
                }
            }
        }
    }

    /** Nothing to do: the same listener, and the row is building or settled for this hour. */
    internal fun upToDate(identity: String?, listener: String?, building: Boolean, doneSlot: String?, slot: String): Boolean =
        identity == listener && (building || doneSlot == slot)

    /** No row at all: YouTube is switched off, and every card and mood would ask it. */
    fun clear() {
        job?.cancel()
        listener = null
        doneSlot = null
        _picks.value = null
        _loading.value = false
    }

    /**
     * Another play action started: a station still being fetched mustn't replace it when it lands,
     * as the phone's `playRequestGeneration`.
     */
    fun cancelPending() {
        requests++
    }

    /**
     * A card: a release opens its page, anything else starts the station behind it. The queue is
     * fetched only now, and the player is left alone when that fails.
     */
    fun open(
        scope: CoroutineScope,
        pick: TopPick,
        label: String,
        openPage: (ShelfItem) -> Unit,
        play: (List<Song>) -> Unit,
        blocked: () -> Boolean,
    ) {
        val browseId = pick.browseId
        if (pick.kind == TopPickKind.LATEST_RELEASE && browseId != null) {
            openPage(asItem(pick, browseId))
            return
        }
        // A party guest is told at once, before a fetch whose answer couldn't be played.
        if (blocked()) return
        val request = ++requests
        scope.launch {
            val songs = TopPicks.queue(pick).getOrNull().orEmpty()
            // A second tap before the first answered: only the latest plays, as the phone's playRequestGeneration.
            if (request != requests) return@launch
            if (songs.isEmpty()) {
                ForkNotice.show("Couldn't load the songs")
                return@launch
            }
            val sourceId = pick.watch?.playlistId ?: pick.mood?.name
            play(
                songs.map {
                    it.copy(
                        radioName = label,
                        playbackSource = label,
                        playbackSourceType = PlaybackSourceType.HOME,
                        playbackSourceId = sourceId,
                    )
                },
            )
            DesktopTrackLog.log("topPicks: started '$label' (${pick.kind}) with ${songs.size} songs")
        }
    }

    /** A Find Your Mood tile: that mood's station, steered by the scene words the Mood card was built with. */
    fun moodStation(mood: TopPickMood): TopPick {
        val hints = _picks.value?.firstOrNull { it.kind == TopPickKind.MOOD }?.hints
        return if (hints != null) TopPicks.moodStation(mood, hints) else TopPicks.moodStation(mood)
    }

    /** A card held (right-clicked), as the phone's long press: the release's or station's own menu, when it has a page. */
    fun menuItem(pick: TopPick): ShelfItem? = pick.browseId?.let { asItem(pick, it) }

    private fun asItem(pick: TopPick, browseId: String) = ShelfItem(
        title = pick.title,
        subtitle = pick.artistName.orEmpty(),
        thumbnailUrl = pick.images.firstOrNull(),
        videoId = null,
        browseId = browseId,
    )

    /** This PC's Replay for the month: the second opinion, and all there is signed out. */
    private fun localTaste(): TopPicks.LocalTaste {
        val month = runCatching { DesktopListeningStats.summary(DesktopReplayPeriod.THIS_MONTH) }.getOrNull()
            ?: return TopPicks.LocalTaste()
        return TopPicks.LocalTaste(
            artists = month.artists.take(10).map { ArtistRef(name = it.title, browseId = it.browseId) },
            // Top Picks seeds a YouTube station from these: a server song or a radio stream can't be one.
            songs = month.songs.map { it.song }.filterNot { NavidromeSettings.isNavidromeId(it.videoId) }.take(5),
        )
    }

    internal fun slot(now: LocalDateTime = LocalDateTime.now()): String = "${now.toLocalDate()}T%02d".format(now.hour)

    /** Beside the analyser's files: `user.home` is the portable `data` folder there. */
    private fun file(): File {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.local/share"
        return File(base, "bitchord/top_picks.json")
    }
}

/**
 * The phone's `refreshTopPicksIfStale`: Home shown, or the window back in focus, in a new hour builds
 * the next row. [ForkTopPicks.load] does nothing within the hour.
 */
@Composable
internal fun ForkTopPicksWhenStale(load: () -> Unit) {
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    androidx.compose.runtime.LaunchedEffect(focused) { if (focused) load() }
}

/** Find Your Mood's Show all: every mood and activity, under its heading. */
@Composable
internal fun ForkMoodsPage(title: String, contentPadding: PaddingValues, onClick: (TopPickMood, String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 16.dp),
        )
        MoodGridPage(
            gridState = rememberLazyGridState(),
            onClick = onClick,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        )
    }
}
