package com.music.bitchord.playback

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeClient
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.navidrome.NavidromeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork (owner, 2026-11-07): a "Navidrome" folder in Android Auto's browse tree, beside upstream's
 * Quick picks / Recents / Playlists / Liked / More, which are YouTube's and the device's only.
 *
 * Inside: the server's playlists, its album lists (the same four `getAlbumList2` kinds as the
 * Navidrome shelves on Home) and its starred songs. Albums and playlists are both browsable and
 * playable, so a car can open one or play it whole. A song is listed under an id that names the
 * list it was picked from ([inContext]), so picking it plays that list from it, as a tap on the
 * phone's album page does; upstream's folders queue the one song.
 *
 * Offline (the switch, or no network) the folder lists the songs kept on the phone, which play
 * from their files. Nothing here is shown when no server is set up.
 *
 * Hooked into `PlaybackService.MediaLibraryCallback` (`// fork` lines): the root list, `onGetChildren`,
 * `onGetItem`, `onSetMediaItems` and `resolveMediaItems`. The MediaItems are built here with the
 * service's own content-style keys, since its builders are private.
 */
internal object ForkAutoNavidrome {

    const val ROOT = "nd:root"
    private const val PLAYLISTS = "nd:playlists"
    private const val LIKED = "nd:liked"
    private const val KEPT = "nd:kept"
    private const val ALBUM_LIST = "nd:albums:"
    private const val ALBUM = "nd:album:"
    private const val PLAYLIST = "nd:playlist:"
    private const val IN = "nd:in|"

    /** `getAlbumList2` kinds, as Home's Navidrome shelves show them. */
    private val ALBUM_LISTS = listOf(
        "newest" to R.string.navidrome_home_newest,
        "recent" to R.string.navidrome_home_recent,
        "frequent" to R.string.navidrome_home_frequent,
        "random" to R.string.navidrome_home_random,
    )

    private const val ALBUMS_PER_LIST = 50

    /** A car waits on these; the server may be a home box reached over a tunnel. */
    private const val TIMEOUT_MS = 8_000L

    /** Starred songs can be the whole library: a queue starts with at most this many. */
    internal const val MAX_QUEUE = 500

    /** Every folder handed out, for `onGetItem` (a car asks for an item by id after a restart of its own). */
    private val folders = ConcurrentHashMap<String, MediaItem>()

    fun isOurs(id: String) = id.startsWith("nd:")

    /** Album lists are drawn as grids of covers; everything else as rows. */
    fun isGrid(parentId: String) = parentId.startsWith(ALBUM_LIST) || parentId == PLAYLISTS

    internal fun inContext(parentId: String, songId: String) = "$IN$parentId|$songId"

    /** The list and the song an [inContext] id names, or null for any other id. */
    internal fun parseInContext(id: String): Pair<String, String>? {
        if (!id.startsWith(IN)) return null
        val rest = id.removePrefix(IN)
        val cut = rest.lastIndexOf('|')
        if (cut <= 0 || cut == rest.length - 1) return null
        return rest.substring(0, cut) to rest.substring(cut + 1)
    }

    /** [songs] from [start], at most [MAX_QUEUE] of them, and where [start] lands in that. */
    internal fun <T> window(songs: List<T>, start: Int): Pair<List<T>, Int> {
        if (songs.isEmpty()) return songs to 0
        val at = start.coerceIn(0, songs.size - 1)
        if (songs.size <= MAX_QUEUE) return songs to at
        val from = (at - MAX_QUEUE / 2).coerceIn(0, songs.size - MAX_QUEUE)
        return songs.subList(from, from + MAX_QUEUE) to at - from
    }

    /** The root's Navidrome folder, or nothing when no server is set up. */
    fun rootFolders(context: Context): List<MediaItem> =
        if (!NavidromeSettings.isConfigured()) emptyList()
        else listOf(folder(ROOT, context.getString(R.string.navidrome), playable = false))

    /** A folder handed out, or a song listed under one (as the car remembers it, by its listed id). */
    fun item(mediaId: String, song: (String) -> Song?): MediaItem? {
        if (!isOurs(mediaId)) return null
        folders[mediaId]?.let { return it }
        val songId = parseInContext(mediaId)?.second ?: return null
        return song(songId)?.toMediaItem()?.buildUpon()?.setMediaId(mediaId)?.build()
    }

    /** The children of one of this folder's ids, or null for an id that isn't one. */
    suspend fun children(context: Context, parentId: String, remember: (Song) -> Unit): List<MediaItem>? {
        if (!isOurs(parentId)) return null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { load(context, parentId, remember) }
                ?: listOf(unavailable(context, parentId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(TAG, "auto: $parentId failed: ${e.message}")
            listOf(unavailable(context, parentId))
        }
    }

    private suspend fun load(context: Context, parentId: String, remember: (Song) -> Unit): List<MediaItem> {
        if (parentId == ROOT) {
            if (NavidromePlatform.offline.value) return songRows(KEPT, NavidromePlatform.kept.keptSongs(), remember).ifEmpty { listOf(empty(context, KEPT)) }
            return listOf(folder(PLAYLISTS, context.getString(R.string.navidrome_home_playlists), playable = false, grid = true)) +
                ALBUM_LISTS.map { (type, title) -> folder(ALBUM_LIST + type, context.getString(title), playable = false, grid = true) } +
                folder(LIKED, context.getString(R.string.auto_liked), playable = true)
        }
        val client = NavidromeRepository.client()
        return when {
            parentId == PLAYLISTS -> client.playlists().map {
                folder(PLAYLIST + it.id, it.name, art = it.coverArt?.let(client::coverUrl), playable = true)
            }
            parentId.startsWith(ALBUM_LIST) -> client.albums(parentId.removePrefix(ALBUM_LIST), ALBUMS_PER_LIST).map {
                folder(ALBUM + it.id, it.name, subtitle = it.artist, art = it.coverArt?.let(client::coverUrl), playable = true, album = true)
            }
            else -> songRows(parentId, songsOf(parentId).orEmpty(), remember)
        }.ifEmpty { listOf(empty(context, parentId)) }
    }

    /** The songs of a playable list, or null when [id] isn't one. */
    private suspend fun songsOf(id: String): List<Song>? {
        if (id == KEPT) return NavidromePlatform.kept.keptSongs()
        val client: NavidromeClient = NavidromeRepository.client()
        val rows = when {
            id == LIKED -> client.starredSongs()
            id.startsWith(ALBUM) -> client.album(id.removePrefix(ALBUM)).second
            id.startsWith(PLAYLIST) -> client.playlistSongs(id.removePrefix(PLAYLIST))
            else -> return null
        }
        return with(NavidromeRepository) { rows.map { it.toSong(client) } }
    }

    private fun songRows(parentId: String, songs: List<Song>, remember: (Song) -> Unit): List<MediaItem> =
        songs.map { song ->
            remember(song)
            song.toMediaItem().buildUpon().setMediaId(inContext(parentId, song.videoId)).build()
        }

    /**
     * What a car's "play this" becomes: a list played whole, or a song played on through the list
     * it was picked from. Null for anything else (upstream's own ids), and when the server can't
     * answer, so upstream's resolution runs as before.
     */
    suspend fun queueFor(items: List<MediaItem>, remember: (Song) -> Unit): MediaSession.MediaItemsWithStartPosition? {
        val id = items.singleOrNull()?.mediaId?.takeIf(::isOurs) ?: return null
        val (songs, start) = resolve(id) ?: return null
        songs.forEach(remember)
        val (queue, at) = window(songs, start)
        if (queue.isEmpty()) return null
        return MediaSession.MediaItemsWithStartPosition(ImmutableList.copyOf(queue.map { it.toMediaItem() }), at, 0L)
    }

    /** "Add to queue" of one of these ids: the one song, or the whole list. Null for other ids. */
    suspend fun itemsFor(id: String, remember: (Song) -> Unit): List<MediaItem>? {
        if (!isOurs(id)) return null
        val (songs, start) = resolve(id) ?: return emptyList()
        val picked = if (parseInContext(id) != null) listOfNotNull(songs.getOrNull(start)) else window(songs, 0).first
        picked.forEach(remember)
        return picked.map { it.toMediaItem() }
    }

    private suspend fun resolve(id: String): Pair<List<Song>, Int>? = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            val context = parseInContext(id)
            if (context != null) {
                val (parent, songId) = context
                val songs = songsOf(parent) ?: return@withTimeoutOrNull null
                songs to songs.indexOfFirst { it.videoId == songId }.coerceAtLeast(0)
            } else {
                songsOf(id)?.let { it to 0 }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DebugLog.d(TAG, "auto: play $id failed: ${e.message}")
        null
    }

    private fun folder(
        id: String,
        title: String,
        subtitle: String? = null,
        art: String? = null,
        playable: Boolean,
        grid: Boolean = false,
        album: Boolean = false,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                // Without the login, as every item's art (Song.toMediaItem): the session's bitmap loader adds it.
                .setArtworkUri(NavidromeRepository.atRest(art)?.toUri())
                .setIsBrowsable(true)
                .setIsPlayable(playable)
                .setFolderType(if (album) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                .setExtras(style(if (grid) GRID else LIST))
                .build(),
        )
        .build()
        .also { folders[id] = it }

    /** Upstream's own empty-folder lines, by what the folder holds. */
    private fun empty(context: Context, parentId: String): MediaItem {
        val (title, subtitle) = when {
            parentId == LIKED -> R.string.auto_empty_liked_title to R.string.auto_empty_liked_subtitle
            parentId == KEPT || parentId == ROOT -> R.string.auto_empty_offline_title to R.string.auto_empty_offline_subtitle
            parentId == PLAYLISTS -> R.string.auto_empty_playlists_title to R.string.auto_empty_playlists_subtitle
            else -> R.string.auto_unavailable_title to R.string.auto_unavailable_subtitle
        }
        return info("msg:navidrome_empty|$parentId", context.getString(title), context.getString(subtitle))
    }

    private fun unavailable(context: Context, parentId: String) = info(
        "msg:navidrome_unavailable|$parentId",
        context.getString(R.string.auto_unavailable_title),
        context.getString(R.string.auto_unavailable_subtitle),
    )

    private fun info(id: String, title: String, subtitle: String?) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    /** The children's style, under every key the service's own `buildContentStyleBundle` writes. */
    private fun style(browsable: Int) = Bundle().apply {
        for (prefix in STYLE_PREFIXES) {
            putInt("${prefix}CONTENT_STYLE_BROWSABLE_HINT", browsable)
            putInt("${prefix}CONTENT_STYLE_PLAYABLE_HINT", LIST)
        }
        putBoolean("android.media.browse.extra.CONTENT_STYLE_SUPPORTED", true)
        putBoolean("androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED", true)
    }

    private val STYLE_PREFIXES = listOf(
        "android.media.browse.extra.",
        "androidx.media.contentstyle.",
        "androidx.media.utils.",
        "android.media.extras.",
        "android.media.browse.",
    )
    private const val LIST = 1
    private const val GRID = 4
    private const val TAG = "BitChordNavidrome"
}
