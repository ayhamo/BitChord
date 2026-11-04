package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.data.navidrome.NavidromeAlbums
import com.music.bitchord.data.navidrome.NavidromeArtists
import com.music.bitchord.data.navidrome.NavidromePlaylists
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.playback.QueueTimeline
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: a whole album or playlist as one thing, the phone's `BrowseActionsMenu` on the desktop.
 *
 * The phone offers a release's "Play next" and "Add to queue" (and from a card, Play, Shuffle,
 * "Download all", Open, Share and Pin) because a release is exactly the kind of thing someone
 * wants *after* what is playing rather than instead of it; until now the PC could only queue one
 * by right-clicking its rows one at a time. The page's own header already carries Play and
 * Shuffle, so there only the two queue rows are added to its "More" menu, before "Download all";
 * a Home card gets the full menu on a right-click ([ForkCardMenuHost]).
 *
 * The queue lives in the window ([DesktopApp]'s `liveQueue`), so the window hands its handles
 * over as a [Window] every composition, the way it does for [ForkQueues].
 */
internal object ForkCollectionActions {

    /** What the window lends: its queue and the calls that play, open and download. */
    class Window(
        val queue: () -> DesktopQueue,
        /** Puts the new queue in place, saves it and tells a party, as the window's `enqueue` does. */
        val replaceQueue: (DesktopQueue) -> Unit,
        /** A row as the window queues one by hand: its canonical form, the station's name, the queue's source. */
        val asQueued: (Song) -> Song,
        val play: (List<Song>, DesktopQueueSource) -> Unit,
        val open: (ShelfItem) -> Unit,
        val download: (List<Song>) -> Unit,
        /** The window's own scope: a card's fetch has to outlive the Home page that started it. */
        val scope: CoroutineScope,
    )

    @Volatile
    var window: Window? = null

    /**
     * Which tracks a card's "Download all" asked for, by release, so its row can say
     * "Downloading" while they are still queued or running: only this release's own, since two
     * releases can share a track (the phone's `Downloads.requested`).
     */
    private val requested = ConcurrentHashMap<String, Set<String>>()

    /**
     * The phone's `queueSongs`: [songs] go into the listener's own section, at its head for
     * "Play next" and at its end for "Add to queue", in the release's own order either way (this
     * adds a release, it does not start one, so shuffle has no say). On a silent player there is
     * nothing to queue behind, so it starts here instead. In a party, held to the twenty-five
     * upcoming songs the server allows, as the window's single-song `enqueue` is.
     *
     * The count is said out loud ([ForkNotice]): the queue panel is usually shut and the current
     * track carries on, so there is otherwise nothing on screen to show for it.
     */
    fun queue(songs: List<Song>, next: Boolean, source: DesktopQueueSource) {
        val window = window ?: return
        val playable = songs.filterNot { it.isUnresolvedSpotify }
        if (playable.isEmpty()) {
            ForkNotice.show(DesktopStrings["no_tracks_here", "No tracks here"])
            return
        }
        val party = DesktopListenTogether.state.value
        if (party.controlsLocked) return
        val current = window.queue()
        if (current.songs.isEmpty()) {
            window.play(playable, source)
            return
        }
        val toAdd = if (party.inParty) {
            val slotsLeft = (DesktopPartySync.MAX_PARTY_UPCOMING_QUEUE - current.upcoming.size).coerceAtLeast(0)
            if (slotsLeft == 0) {
                DesktopPlayerHost.showMessage(
                    "Queue is full (maximum ${DesktopPartySync.MAX_PARTY_UPCOMING_QUEUE} songs in party)",
                )
                return
            }
            playable.take(slotsLeft)
        } else {
            playable
        }
        window.replaceQueue(current.forkEnqueueAll(toAdd.map(window.asQueued), playNext = next))
        val n = toAdd.size
        ForkNotice.show(
            when {
                next && n == 1 -> "1 song will play next"
                next -> "$n songs will play next"
                n == 1 -> "Added 1 song to the queue"
                else -> "Added $n songs to the queue"
            },
        )
    }

    /** Where a release's tracks say they were played from when it starts a silent player. */
    fun sourceOf(title: String, browseId: String) = DesktopQueueSource(title, PlaybackSourceType.BROWSE, browseId)

    /**
     * What a card is, by its id. A Navidrome page's id is the server's own (`local:group:…`),
     * which [browseTypeOf] reads as OTHER.
     */
    fun cardType(browseId: String): BrowseType = when {
        browseId.startsWith(NavidromeAlbums.PREFIX) -> BrowseType.ALBUM
        browseId.startsWith(NavidromePlaylists.PREFIX) -> BrowseType.PLAYLIST
        browseId.startsWith(NavidromeArtists.PREFIX) -> BrowseType.ARTIST
        else -> browseTypeOf(browseId)
    }

    /**
     * Whether a card gets the release menu. Not an artist (a page of its own, which the phone's
     * menu reaches through its own radio row), and not a Spotify playlist: its rows have to be
     * matched to YouTube Music one by one before anything can play, which only its page does.
     */
    fun hasMenu(item: ShelfItem): Boolean {
        val id = item.browseId ?: return false
        return item.videoId == null && cardType(id) != BrowseType.ARTIST && !id.startsWith(SPOTIFY_PAGE_PREFIX)
    }

    /**
     * A card's whole track list: its page was never opened, so the listing is fetched, all of it
     * (`YtMusicRepository.allSongs`, the phone's `collectSongs`), or the server's page for a
     * Navidrome card. Album rows arrive with no album name of their own, so the title is stamped
     * on, and rows with no cover take the card's, as the phone's `withBrowseSongs` does: a track
     * queued or downloaded from an album card otherwise has nothing to file it under.
     */
    suspend fun songsOf(item: ShelfItem): Result<List<Song>> {
        val id = item.browseId ?: return Result.failure(IllegalStateException(DesktopStrings["no_tracks_here", "No tracks here"]))
        val type = cardType(id)
        val listing = ForkNavidrome.collection(
            DesktopCollection(id, item.title, item.subtitle, item.thumbnailUrl, type, emptyList(), loading = true),
        )?.map { it.songs } ?: YtMusicRepository.allSongs(id)
        return listing.map { songs ->
            songs.map { song ->
                song.copy(
                    albumName = if (type == BrowseType.ALBUM) song.albumName ?: item.title else song.albumName,
                    thumbnailUrl = song.thumbnailUrl?.takeIf(String::isNotBlank) ?: item.thumbnailUrl,
                    playbackSource = item.title,
                    playbackSourceType = PlaybackSourceType.BROWSE,
                    playbackSourceId = id,
                )
            }
        }
    }

    /**
     * A card's "Download all": the YouTube download every page's own row uses, plus the record
     * "Keep playlists updated" follows ([ForkPlaylistSync.remember]). Not offered for a Navidrome
     * card, as on the phone, whose server pages download through "Available offline" instead.
     */
    fun download(item: ShelfItem, songs: List<Song>) {
        val id = item.browseId ?: return
        requested[id] = songs.mapTo(HashSet()) { it.videoId }
        ForkPlaylistSync.remember(id, cardType(id), item.title, item.subtitle, item.thumbnailUrl, songs)
        window?.download?.invoke(songs)
    }

    fun waiting(browseId: String, active: Map<String, DesktopDownloadItem>): Boolean =
        requested[browseId].orEmpty().any { id ->
            when (active[id]?.state) {
                DesktopDownloadState.Queued, is DesktopDownloadState.Running -> true
                else -> false
            }
        }
}

/**
 * Queues [more] by hand in one go: the insertion point is found once and the whole list goes in
 * there, as the phone's `addMediaItems(at, …)` does. One [DesktopQueue.enqueue] per track would
 * reverse a release under "Play next", each one landing at the head ahead of the one before.
 */
internal fun DesktopQueue.forkEnqueueAll(more: List<Song>, playNext: Boolean): DesktopQueue {
    if (more.isEmpty()) return this
    val at = QueueTimeline.findUserQueueInsertionIndex(songs, index, isNext = playNext).coerceIn(0, songs.size)
    return copy(songs = songs.toMutableList().apply { addAll(at, more.map { it.asQueueEntry(QueueTier.USER_QUEUE) }) })
}

/**
 * The two queue rows in an album or playlist page's "More" menu, before "Download all", as the
 * phone's page overflow has them: Play and Shuffle are already in the header. [songs] are the rows
 * the page has loaded, as on the phone.
 */
@Composable
internal fun ForkCollectionQueueItems(collection: DesktopCollection, songs: List<Song>, onDone: () -> Unit) {
    if (collection.type != BrowseType.ALBUM && collection.type != BrowseType.PLAYLIST) return
    val source = ForkCollectionActions.sourceOf(collection.title, collection.browseId)
    DropdownMenuItem(
        text = { Text(DesktopStrings["play_next", "Play next"]) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
        onClick = {
            onDone()
            ForkCollectionActions.queue(songs, next = true, source = source)
        },
    )
    DropdownMenuItem(
        text = { Text(DesktopStrings["add_to_queue", "Add to queue"]) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
        onClick = {
            onDone()
            ForkCollectionActions.queue(songs, next = false, source = source)
        },
    )
}

/**
 * Right-click on a Home card that opens a page: the phone's hold on a card, its whole menu in its
 * order (Play, Shuffle, Play next, Add to queue, Download all, Open, Share, Pin). A card that plays
 * a song still gets the song menu; [content] is handed the call that opens this one.
 *
 * Every row closes the menu first, then fetches the tracks: leaving the menu up over a request it
 * says nothing about reads as a click that didn't land (the phone's reasoning). A failed fetch is
 * said once and nothing more is tried.
 */
@Composable
internal fun ForkCardMenuHost(content: @Composable (open: (ShelfItem) -> Unit) -> Unit) {
    // The window's scope, not one remembered here: this host is the Home page's, so a fetch
    // started from a card would be cancelled by leaving Home before it answered (a "Download all"
    // lost without a word). Only the page's own scope is the fallback.
    val pageScope = rememberCoroutineScope()
    DesktopPointerMenuHost<ShelfItem>(
        menu = { item, onDismiss ->
            val id = item.browseId ?: return@DesktopPointerMenuHost
            val type = ForkCollectionActions.cardType(id)
            val server = ForkNavidrome.ownsPage(id)
            val active by DesktopDownloadQueue.active.collectAsState()
            fun act(action: (List<Song>) -> Unit): () -> Unit = {
                onDismiss()
                (ForkCollectionActions.window?.scope ?: pageScope).launch {
                    ForkCollectionActions.songsOf(item).fold(
                        onSuccess = { songs -> if (songs.isEmpty()) ForkNotice.show(DesktopStrings["no_tracks_here", "No tracks here"]) else action(songs) },
                        onFailure = {
                            DesktopTrackLog.log("card menu: tracks of '${item.title}' failed: ${it.message}")
                            ForkNotice.show(it.message ?: DesktopStrings["couldnt_load_tracks", "Could not load these tracks"])
                        },
                    )
                }
            }
            val window = ForkCollectionActions.window
            val source = ForkCollectionActions.sourceOf(item.title, id)
            DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
                DropdownMenuItem(
                    text = { Text(DesktopStrings["play", "Play"]) },
                    leadingIcon = { Icon(BitChordIcons.Play, null) },
                    onClick = act { songs -> window?.play?.invoke(songs, source) },
                )
                DropdownMenuItem(
                    text = { Text(DesktopStrings["shuffle", "Shuffle"]) },
                    leadingIcon = { Icon(BitChordIcons.Shuffle, null) },
                    // As the page's own Shuffle on the PC: the list shuffled, then played from the top.
                    onClick = act { songs -> window?.play?.invoke(songs.shuffled(), source) },
                )
                DropdownMenuItem(
                    text = { Text(DesktopStrings["play_next", "Play next"]) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
                    onClick = act { songs -> ForkCollectionActions.queue(songs, next = true, source = source) },
                )
                DropdownMenuItem(
                    text = { Text(DesktopStrings["add_to_queue", "Add to queue"]) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                    onClick = act { songs -> ForkCollectionActions.queue(songs, next = false, source = source) },
                )
                if (!server) {
                    // The phone's three states read off the release's own request; a card has no
                    // tracks in hand, so "Downloaded" (every track saved) is never claimed from here.
                    val waiting = ForkCollectionActions.waiting(id, active)
                    DropdownMenuItem(
                        text = { Text(DesktopStrings["download_all", "Download all"]) },
                        leadingIcon = { Icon(if (waiting) BitChordIcons.Clock else BitChordIcons.Download, null) },
                        trailingIcon = if (waiting) {
                            { Text(DesktopStrings["downloading", "Downloading"], color = DesktopSecondary) }
                        } else {
                            null
                        },
                        onClick = act { songs -> ForkCollectionActions.download(item, songs) },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            when (type) {
                                BrowseType.ALBUM -> DesktopStrings["open_album", "Open album"]
                                BrowseType.PLAYLIST -> DesktopStrings["open_playlist", "Open playlist"]
                                else -> DesktopStrings["open", "Open"]
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null) },
                    onClick = {
                        onDismiss()
                        window?.open?.invoke(item)
                    },
                )
                if (!server && (type == BrowseType.ALBUM || type == BrowseType.PLAYLIST)) {
                    DropdownMenuItem(
                        text = { Text(DesktopStrings["share", "Share"]) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = {
                            onDismiss()
                            // The link YouTube Music's own overflow shares, as the page's Share copies it.
                            DesktopExternalLinks.copy(
                                if (type == BrowseType.PLAYLIST) "https://music.youtube.com/playlist?list=${id.removePrefix("VL")}"
                                else "https://music.youtube.com/browse/$id",
                            )
                            DesktopTrackLog.log("copied a link to '${item.title}'")
                        },
                    )
                }
                ForkPinMenuItem(id, type, onDismiss)
            }
        },
    ) { open -> content(open) }
}
