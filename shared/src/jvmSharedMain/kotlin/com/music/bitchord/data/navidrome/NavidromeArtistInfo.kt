package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * What a Navidrome artist page (`local:group:artist:navidrome:<name>`) asks of the server about the
 * artist itself (fork-only): "Start radio" (`getSimilarSongs2` with the artist's id, which Navidrome
 * takes as well as a song's) and the "Similar artists" row (`getArtistInfo2`).
 *
 * The page knows the artist by name only. The id comes from the songs already read: every
 * [NavidromeClient.SongRow] names its credited artists with their ids ([remember]); only a name no
 * listing has carried yet is searched for, once. Both answers are kept for the session, so a page
 * opened again asks nothing; [clear] drops them with the server.
 */
object NavidromeArtistInfo {

    /** As many songs as Navidrome's own Instant Mix asks for, the song radio's size too. */
    private const val RADIO_SIZE = 100

    const val SIMILAR_TITLE = "Similar artists"

    private val ids = ConcurrentHashMap<String, String>()

    /** Names searched for without an exact match: not searched again this session. */
    private val unknown: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val similar = ConcurrentHashMap<String, List<ShelfItem>>()

    fun clear() {
        ids.clear()
        unknown.clear()
        similar.clear()
    }

    /** The artist name of a Navidrome artist page, or null for any other page. */
    fun nameOf(browseId: String?): String? =
        browseId?.takeIf { it.startsWith(NavidromeArtists.PREFIX) }?.removePrefix(NavidromeArtists.PREFIX)?.takeIf { it.isNotBlank() }

    /** Notes an artist's id from a listing of artists (`getArtists`, [NavidromeArtistPictures]' pass). */
    fun noteId(name: String, id: String) {
        ids.putIfAbsent(name, id)
    }

    /** Notes the artist ids [row] carries, so its artists' pages need no search for them. */
    fun remember(row: NavidromeClient.SongRow) {
        idsOf(row).forEach { (name, id) -> ids.putIfAbsent(name, id) }
    }

    /**
     * (name, id) of each artist [row] credits: the OpenSubsonic `artists` list, or, from a server that
     * sends none, its one `artist` with `artistId`. A display credit like "A feat. B" is no artist of
     * its own on a newer Navidrome, whose `artistId` is A's, so it is only taken when nothing else is sent.
     */
    fun idsOf(row: NavidromeClient.SongRow): List<Pair<String, String>> = when {
        row.artists.isNotEmpty() -> row.artists.map { (id, name) -> name to id }
        row.artist != null && row.artistId != null -> listOf(row.artist to row.artistId)
        else -> emptyList()
    }

    private val CREDIT_SEPARATORS = Regex("""\s*(?:,|&|;|\+|\s(?:feat\.?|ft\.?|featuring|with|x|vs\.?)\s)\s*""", RegexOption.IGNORE_CASE)

    /** The names in a credit like "Metro Boomin, The Weeknd & 21 Savage", trimmed; one name for a plain artist. */
    fun creditParts(credit: String): List<String> =
        credit.split(CREDIT_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

    /** Whether the credit [credit] names [artist] among several (ignoring case), and isn't [artist] alone. */
    fun credits(credit: String, artist: String): Boolean {
        val parts = creditParts(credit)
        return parts.size > 1 && parts.any { it.equals(artist, ignoreCase = true) }
    }

    /**
     * The first credited artist of a collaboration credit, or null for one artist. A credit counts as a
     * collaboration only when at least two of its names are artists the server's songs credit on their
     * own ([known]), so a band named with "&" or "," ("Simon & Garfunkel", "Earth, Wind & Fire") stays one.
     */
    fun collabLead(credit: String, known: (String) -> Boolean = ::isKnown): String? {
        val parts = creditParts(credit)
        if (parts.size < 2) return null
        return parts.first().takeIf { parts.count(known) >= 2 }
    }

    /** Whether an artist of exactly that name has been seen in the server's songs or artist lists this session. */
    fun isKnown(name: String): Boolean = ids.containsKey(name) || ids.keys.any { it.equals(name, ignoreCase = true) }

    /** The search hit that is [name] itself: exactly, else ignoring case; never a near match. */
    fun pick(name: String, hits: List<NavidromeClient.ArtistRow>): NavidromeClient.ArtistRow? =
        hits.firstOrNull { it.name == name } ?: hits.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The server's id for the artist named [name]: remembered from the songs, else one `search3`. */
    suspend fun artistId(name: String, client: NavidromeClient = NavidromeRepository.client()): String? {
        ids[name]?.let { return it }
        if (name in unknown) return null
        val found = pick(name, client.searchArtists(name))
        if (found == null) unknown += name else ids[name] = found.id
        return found?.id
    }

    /**
     * "Start radio" on the artist page of [name]: the server's similar songs to the artist, in its
     * order, the artist's own songs included (as Navidrome's artist radio plays them), each song once.
     * Empty when the server has nothing (no agent, or a name it has no artist for): the player says so.
     */
    suspend fun radio(name: String): Result<List<Song>> = runCatching {
        val client = NavidromeRepository.client()
        val id = artistId(name, client) ?: return@runCatching emptyList()
        val songs = with(NavidromeRepository) { client.similarSongs(id, RADIO_SIZE).map { it.toSong(client) } }
        station(name, songs)
    }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }

    /** [songs] as the station "[name]" plays them: the server's order, one copy of each recording, labelled with the artist. */
    fun station(name: String, songs: List<Song>): List<Song> {
        val taken = mutableListOf<Song>()
        return songs.filter { song ->
            taken.none { com.music.bitchord.playback.QueueBuilder.isSameRecording(it, song) } && taken.add(song)
        }.map {
            it.copy(
                radioName = name,
                playbackSource = name,
                playbackSourceType = PlaybackSourceType.BROWSE,
                playbackSourceId = NavidromeSettings.artistPageId(name),
            )
        }
    }

    /**
     * The "Similar artists" row for the page [browseId], or null: not a Navidrome artist page,
     * offline, or the server knows none. One `getArtistInfo2` per artist and session; a failed
     * request is not kept, so the next visit asks again (never a retry here).
     */
    suspend fun similarShelf(browseId: String?): HomeShelf? {
        val name = nameOf(browseId) ?: return null
        if (!NavidromeSettings.isConfigured() || NavidromePlatform.offline.value) return null
        val items = similar[name] ?: runCatching {
            val client = NavidromeRepository.client()
            val id = artistId(name, client)
            val rows = if (id == null) emptyList() else client.similarArtists(id)
            rows.forEach { noteId(it.name, it.id) } // their pictures (NavidromeArtistPictures) need no search for the id
            shelfItems(name, rows) { client.coverUrl(it) }
        }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
            .getOrNull()?.also { similar[name] = it } ?: return null
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(SIMILAR_TITLE, it) }
    }

    /**
     * One card per similar artist (never [name] itself, each once), opening its Navidrome page, with its server picture.
     * A credit that includes [name] ("The Weeknd & Future" on The Weeknd's page) is left out: Navidrome files each
     * collaboration as an artist of its own and its similarity agent ranks them first, so the row was mostly the
     * page's own artist again (seen 2026-11-11: five of six cards).
     */
    fun shelfItems(name: String, rows: List<NavidromeClient.ArtistRow>, cover: (String) -> String): List<ShelfItem> =
        rows.filter { it.name != name && !credits(it.name, name) }.distinctBy { it.name }.map { row ->
            ShelfItem(
                title = row.name,
                subtitle = "",
                // Navidrome's artist picture is `ar-<id>`; its `artistImageUrl` is a `/share/img` link, not `/rest`.
                thumbnailUrl = cover(row.coverArt ?: "ar-${row.id}"),
                videoId = null,
                browseId = NavidromeSettings.artistPageId(row.name),
            )
        }
}
