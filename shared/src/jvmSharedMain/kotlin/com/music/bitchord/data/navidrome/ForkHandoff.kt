package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Locale

/**
 * Fork: "continue on the other device". Stop on the phone, open the PC (or the other way round),
 * and the same queue comes back at the same song and second.
 *
 * ### Why the Navidrome server carries it
 *
 * Both apps already talk to one server the owner runs, logged in as the same user, and nothing
 * else is shared between them: no account of ours, no cloud, no sync channel. So the note rides in
 * the one free-text field Subsonic lets a user write and read back on their own object: a
 * playlist's `comment`. One playlist per user, [PLAYLIST_NAME], never public and never holding a
 * song, is the mailbox; both apps leave it out of every playlist list they show
 * ([isHiddenPlaylist], applied in [NavidromeClient.playlists]). Only Subsonic `/rest/…` calls are
 * made (`getPlaylists`, `createPlaylist`, `updatePlaylist`, `getPlaylist`), each once per event,
 * with a back-off after a failure ([Saver]) rather than a retry loop.
 *
 * ### The note
 *
 * A compact JSON object with short keys ([encode] / [decode]): version, when and by which device it
 * was saved, the index and position of the playing song, the queue's origin ([Origin]: what "Playing
 * from …" names and what AutoPlay / radio continue from) and the songs around the playing one
 * ([Item]). Nothing in it is a credential: a Navidrome song is its server id (its addresses are built
 * again on the other side with that side's own login), a YouTube song its video id, an addon song
 * its source by kind and a hash of its address ([SourceRef]), never the address itself, which can
 * carry an addon's own key.
 *
 * How long a comment the server keeps is not documented, so the note degrades instead of failing:
 * [encode] tries the whole window (the playing song ±[RADIUS]), then without thumbnails, then
 * narrower windows, down to the playing song alone, and takes the first that fits the budget. The
 * PC's debug bridge (`/handoff-probe`, [ForkHandoffStore.probe]) measures the real limit.
 *
 * ### What survives
 *
 * Navidrome songs, YouTube songs (an album or playlist from YouTube keeps its origin), addon-only
 * songs (playable on the other side only when it has that addon: [Item.Kind.ADDON] rows the other
 * device can't map are dropped there), and a YouTube song playing as an upgraded copy: the row stays
 * the YouTube song, with a [Hint] naming the copy (source + its track id) and the "original version"
 * pin, so the other device asks that copy first, or else does its own upgrade search. Local files
 * (`content://`, `file://`, `C:\…`) and radio stations are dropped: the other device has no way to
 * reach them. When the playing song itself is dropped, the next surviving one is the playing one and
 * the position is 0.
 */
object ForkHandoff {

    /** The mailbox playlist's name; its leading `~` sorts it out of the way in Navidrome's own web UI too. */
    const val PLAYLIST_NAME = "~bitchord-handoff"

    /** The PC's measuring playlist ([ForkHandoffStore.probe]), deleted when the probe ends. */
    const val PROBE_PLAYLIST_NAME = "~bitchord-handoff-probe"

    const val VERSION = 1

    /** Songs kept on each side of the playing one. */
    const val RADIUS = 50

    /**
     * The note's size limit, in UTF-8 bytes of JSON, while the server's own limit is unknown. A note
     * of 101 songs with thumbnails is about 25-30 KB; [encode] shrinks it below this.
     */
    const val DEFAULT_BUDGET = 48 * 1024

    /** The smaller budget for a server that only reads the comment from the address (a GET), where a proxy may cap the line at 8 KB. */
    const val GET_BUDGET = 4 * 1024

    /** A note older than this is still restorable from the menu row, but no longer offered by itself. */
    const val OFFER_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** The windows [encode] tries, widest first. */
    private val RADII = listOf(RADIUS, 25, 10, 3, 0)

    /** Whether a server playlist is one of this feature's and must not be listed anywhere. */
    fun isHiddenPlaylist(name: String?): Boolean = name != null && name.startsWith(PLAYLIST_NAME)

    data class Device(val id: String, val name: String, val kind: String) {
        companion object {
            const val PHONE = "phone"
            const val PC = "pc"
        }
    }

    /**
     * Where the queue was started from: [name] is what "Playing from …" shows, [type] and [id] what
     * tapping it opens and what AutoPlay / a radio continues from, [radio] a station's seed title.
     */
    data class Origin(val name: String?, val type: String?, val id: String?, val radio: String?) {
        val sourceType: PlaybackSourceType? get() = type?.let { runCatching { PlaybackSourceType.valueOf(it) }.getOrNull() }
    }

    /**
     * An addon (or module, or JioSaavn) as the other device can recognise it: its [kind] by name
     * (the same enum names on the phone and the PC), a short hash of its address ([hashBase]), and
     * its label as a last tie-break. The address itself never leaves the device.
     */
    data class SourceRef(val kind: String, val baseHash: String?, val label: String?)

    /** The copy a song was playing as: a source, and that source's own track id when known. */
    data class Hint(val source: SourceRef, val trackId: String?)

    data class Item(
        val kind: Kind,
        /** Navidrome: the server id (no prefix). YouTube: the video id. Addon: the source's track id. */
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationSec: Int? = null,
        /** A public thumbnail (YouTube's); dropped first when the note is too big. */
        val thumb: String? = null,
        /** A Navidrome song's cover id (`mf-…`), for its address on the other side. */
        val coverId: String? = null,
        /** Addon: which source the row belongs to. YouTube: the upgraded copy, when one was playing. */
        val hint: Hint? = null,
        /** Pinned to YouTube's own upload by the listener ("original version"). */
        val pinned: Boolean = false,
        val isVideo: Boolean = false,
        /** An addon row's own `sourceQuality` (LOSSLESS / HIGH / …), which its playback reads. */
        val sourceQuality: String? = null,
    ) {
        enum class Kind(val code: String) {
            NAVIDROME("n"), YOUTUBE("y"), ADDON("a");

            companion object {
                fun of(code: String?) = entries.firstOrNull { it.code == code }
            }
        }
    }

    data class Note(
        val version: Int = VERSION,
        val savedAt: Long,
        val device: Device,
        val index: Int,
        val positionMs: Long,
        val origin: Origin?,
        val items: List<Item>,
    ) {
        val current: Item? get() = items.getOrNull(index)

        /** Identifies this note for "dismissed": one device's save at one moment. */
        val key: String get() = "${device.id}@$savedAt"
    }

    // ── Which songs go ──────────────────────────────────────────────────

    /** A YouTube video id: 11 characters of the URL-safe alphabet. */
    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun isYouTubeId(id: String): Boolean = YOUTUBE_ID.matches(id)

    /**
     * Whether [localUri] names a file only this device can open: `content://`, `file://`, a Windows
     * or Unix path. A server's own address (http, https) is not local.
     */
    fun isLocalAddress(localUri: String?): Boolean {
        val uri = localUri?.trim().orEmpty()
        if (uri.isEmpty()) return false
        val lower = uri.lowercase(Locale.ROOT)
        return lower.startsWith("content:") || lower.startsWith("file:") || lower.startsWith("/") ||
            lower.startsWith("\\\\") || Regex("^[a-z]:[\\\\/]").containsMatchIn(lower)
    }

    /**
     * The note's songs and the playing one's place among them: the window around [index], each song
     * turned into an [Item] by [toItem] (null drops it). When the playing song is dropped, the next
     * one kept plays (from 0), else the one before it; an empty result means nothing can go.
     */
    fun <T> snapshot(songs: List<T>, index: Int, positionMs: Long, radius: Int = RADIUS, toItem: (T) -> Item?): Triple<List<Item>, Int, Long>? {
        if (songs.isEmpty()) return null
        val at = index.coerceIn(songs.indices)
        val from = (at - radius).coerceAtLeast(0)
        val to = (at + radius).coerceAtMost(songs.lastIndex)
        val kept = mutableListOf<Item>()
        var newIndex = -1
        var position = positionMs.coerceAtLeast(0L)
        var playingDropped = false
        for (i in from..to) {
            val item = toItem(songs[i])
            if (i == at) {
                if (item != null) newIndex = kept.size else playingDropped = true
            } else if (i > at && playingDropped && newIndex < 0 && item != null) {
                newIndex = kept.size
                position = 0L
            }
            if (item != null) kept += item
        }
        if (kept.isEmpty()) return null
        if (newIndex < 0) {
            newIndex = kept.lastIndex // nothing after the dropped one: the last kept before it
            position = 0L
        }
        return Triple(kept, newIndex, position)
    }

    /**
     * The usual classification of a queue row, for both apps: a Navidrome song, else a source's
     * own row when [sourceRow] can describe it (addon / module / JioSaavn), else a YouTube video id
     * (downloaded or cached ones too), else dropped (a local file, a station). [hint] and [pinned] are the platform's knowledge about the
     * playing copy of a YouTube song.
     */
    fun itemFor(
        song: Song,
        sourceRow: (Song) -> Pair<SourceRef, String>?,
        hint: Hint? = null,
        pinned: Boolean = false,
    ): Item? {
        val id = song.videoId
        if (NavidromeSettings.isNavidromeId(id)) {
            val serverId = id.removePrefix(NavidromeSettings.ID_PREFIX)
            return Item(
                kind = Item.Kind.NAVIDROME,
                id = serverId,
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                durationSec = secondsOf(song.durationText),
                // A YouTube thumbnail (NavidromeYouTubeCover) is public; a server cover goes by its id.
                thumb = song.thumbnailUrl?.takeIf { it.startsWith("https://i.ytimg.com/") },
                coverId = coverIdOf(song.thumbnailUrl),
            )
        }
        sourceRow(song)?.let { (ref, trackId) ->
            return Item(
                kind = Item.Kind.ADDON,
                id = trackId,
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                durationSec = secondsOf(song.durationText),
                thumb = publicThumb(song.thumbnailUrl)?.takeIf { addonThumbIsPublic(it, ref) }, // the addon's own address may carry its key
                hint = Hint(ref, trackId),
                sourceQuality = song.sourceQuality,
            )
        }
        // A YouTube id travels whatever local address the row has: a download (the PC's file: URI and
        // C:\ path, the phone's content:// localUri) or the PC's cached copy is still that YouTube
        // song, which the other side streams. Dropping those left a queue of downloads out of the
        // note entirely, and a downloaded playing song made the note jump to another song at 0:00.
        // Only a row that isn't a YouTube song is dropped: a local file (content://, file:, a path,
        // whatever its id), a station, a WebDAV/SMB file, anything else this side alone can reach.
        if (!isYouTubeId(id)) return null
        return Item(
            kind = Item.Kind.YOUTUBE,
            id = id,
            title = song.title,
            artist = song.artist,
            album = song.albumName,
            durationSec = secondsOf(song.durationText),
            thumb = publicThumb(song.thumbnailUrl),
            hint = hint,
            pinned = pinned,
            isVideo = song.isVideo,
        )
    }

    /** The queue's origin as the playing song carries it. */
    fun originOf(song: Song?): Origin? = song?.let {
        if (it.playbackSource == null && it.playbackSourceType == null && it.radioName == null) null
        else Origin(it.playbackSource, it.playbackSourceType?.name, it.playbackSourceId, it.radioName)
    }

    /** A thumbnail with no login in it: anything carrying a Subsonic login, or not http(s), is left out. */
    private fun publicThumb(url: String?): String? {
        val u = url ?: return null
        if (!u.startsWith("https://") && !u.startsWith("http://")) return null
        if ("/rest/" in u) return null // a server's address: built again on the other side, never carried
        return u
    }

    /**
     * Whether an addon row's thumbnail may go in the note: not under the addon's own address (an
     * addon keyed in its path, `https://host/<key>/…`, serves its covers there too; found by
     * hashing each leading part of [url] against [ref]'s [SourceRef.baseHash]), and with no query
     * or `user@` that could hold a key.
     */
    private fun addonThumbIsPublic(url: String, ref: SourceRef): Boolean {
        val authority = url.substringAfter("://").substringBefore('/')
        if ('?' in url || '@' in authority) return false
        val base = ref.baseHash ?: return true
        var prefix = url
        while (prefix.contains("://") && prefix.substringAfter("://").isNotEmpty()) {
            if (hashBase(prefix) == base) return false
            val cut = prefix.trimEnd('/').lastIndexOf('/')
            if (cut <= prefix.indexOf("://") + 2) break
            prefix = prefix.substring(0, cut)
        }
        return true
    }

    /** `getCoverArt?id=…` → the id; anything else none. */
    internal fun coverIdOf(url: String?): String? {
        val u = url ?: return null
        if ("getCoverArt" !in u) return null
        return Regex("[?&]id=([^&]+)").find(u)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } // the String overload: the Charset one is Android API 33+
    }

    internal fun secondsOf(durationText: String?): Int? {
        val parts = durationText?.split(':')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        return parts.fold(0) { total, part -> total * 60 + part }.takeIf { it > 0 }
    }

    private fun durationText(sec: Int?): String? = sec?.takeIf { it > 0 }?.let {
        if (it >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", it / 3600, it / 60 % 60, it % 60)
        else String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60)
    }

    /** The first 12 hex digits of the SHA-256 of an address, trimmed and lower-cased, without its trailing slashes. */
    fun hashBase(baseUrl: String?): String? {
        val normal = baseUrl?.trim()?.trimEnd('/')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return MessageDigest.getInstance("SHA-256").digest(normal.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    /**
     * Which of [candidates] (this device's sources: id, kind name, address, label) is [ref]: the same
     * kind and address first, else for a kind with no address (JioSaavn) the one of that kind, else
     * the same kind and label. Null: this device doesn't have it.
     */
    fun matchSource(ref: SourceRef, candidates: List<LocalSource>): LocalSource? {
        val sameKind = candidates.filter { it.kind.equals(ref.kind, ignoreCase = true) }
        if (ref.baseHash != null) sameKind.firstOrNull { hashBase(it.baseUrl) == ref.baseHash }?.let { return it }
        if (ref.baseHash == null) sameKind.singleOrNull()?.let { return it }
        return ref.label?.takeIf { it.isNotBlank() }?.let { label -> sameKind.firstOrNull { it.label.equals(label, ignoreCase = true) } }
    }

    /** One of this device's sources, for [matchSource]. */
    data class LocalSource(val id: String, val kind: String, val baseUrl: String?, val label: String?)

    // ── Back to songs ───────────────────────────────────────────────────

    /**
     * The note as rows for this device's queue, and the playing one's index among them. A Navidrome
     * row gets this device's addresses (a kept file first, else a stream with its own login; the
     * cover by id); an addon row the id [addonKey] gives it here (null drops it: no such addon);
     * every row carries the note's origin. The note's playing song dropped here: the next one plays,
     * from 0. Null when nothing can play here.
     */
    fun songsFor(note: Note, addonKey: (Item) -> String?): Restored? {
        val client = runCatching { NavidromeRepository.client() }.getOrNull()
        val origin = note.origin
        val songs = mutableListOf<Song>()
        var index = -1
        var position = note.positionMs
        var playingDropped = false
        note.items.forEachIndexed { i, item ->
            val song = when (item.kind) {
                Item.Kind.NAVIDROME -> client?.let { c ->
                    Song(
                        videoId = NavidromeSettings.ID_PREFIX + item.id,
                        title = item.title,
                        artist = item.artist,
                        thumbnailUrl = item.thumb ?: c.coverUrl(item.coverId ?: "mf-${item.id}"),
                        durationText = durationText(item.durationSec),
                        artistId = NavidromeSettings.artistPageId(item.artist),
                        albumId = item.album?.let { NavidromeSettings.albumPageId(it) },
                        albumName = item.album,
                        localUri = NavidromePlatform.kept.localUri(item.id) ?: c.streamUrl(item.id),
                    )
                }
                Item.Kind.YOUTUBE -> Song(
                    videoId = item.id,
                    title = item.title,
                    artist = item.artist,
                    thumbnailUrl = item.thumb,
                    durationText = durationText(item.durationSec),
                    albumName = item.album,
                    isVideo = item.isVideo,
                )
                Item.Kind.ADDON -> addonKey(item)?.let { key ->
                    Song(
                        videoId = key,
                        title = item.title,
                        artist = item.artist,
                        thumbnailUrl = item.thumb,
                        durationText = durationText(item.durationSec),
                        albumName = item.album,
                        sourceQuality = item.sourceQuality,
                    )
                }
            }?.let {
                it.copy(
                    playbackSource = origin?.name ?: it.playbackSource,
                    playbackSourceType = origin?.sourceType ?: it.playbackSourceType,
                    playbackSourceId = origin?.id ?: it.playbackSourceId,
                    radioName = origin?.radio,
                )
            }
            if (i == note.index) {
                if (song != null) index = songs.size else playingDropped = true
            } else if (i > note.index && playingDropped && index < 0 && song != null) {
                index = songs.size
                position = 0L
            }
            if (song != null) songs += song
        }
        if (songs.isEmpty()) return null
        if (index < 0) {
            index = songs.lastIndex
            position = 0L
        }
        return Restored(songs, index, position.coerceAtLeast(0L))
    }

    data class Restored(val songs: List<Song>, val index: Int, val positionMs: Long)

    // ── Offering ────────────────────────────────────────────────────────

    /**
     * Whether [note] should be offered by itself on this device: it came from another device, it is
     * newer than this device's own last save ([myLastSaveAt]), it wasn't dismissed ([dismissedKey]),
     * it has something to play, and it is not older than [OFFER_MAX_AGE_MS].
     */
    fun shouldOffer(note: Note?, myDeviceId: String, myLastSaveAt: Long, dismissedKey: String?, now: Long): Boolean {
        if (note == null || note.items.isEmpty()) return false
        if (note.device.id == myDeviceId) return false
        if (note.savedAt <= myLastSaveAt) return false
        if (note.key == dismissedKey) return false
        return now - note.savedAt <= OFFER_MAX_AGE_MS
    }

    /** "2:14", the position the prompt names. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    // ── The JSON ────────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The note as compact JSON within [budget] UTF-8 bytes: the widest window that fits, thumbnails
     * dropped before songs are. Null when even the playing song alone doesn't fit (an absurd title).
     */
    fun encode(note: Note, budget: Int = DEFAULT_BUDGET): String? {
        for (radius in RADII) {
            for (thumbs in listOf(true, false)) {
                if (!thumbs && note.items.none { it.thumb != null }) continue
                val text = toJson(trim(note, radius), thumbs).toString()
                if (text.toByteArray(Charsets.UTF_8).size <= budget) return text
            }
        }
        return null
    }

    /** [note] narrowed to [radius] songs on each side of the playing one. */
    internal fun trim(note: Note, radius: Int): Note {
        if (note.items.isEmpty()) return note
        val at = note.index.coerceIn(note.items.indices)
        val from = (at - radius).coerceAtLeast(0)
        val to = (at + radius).coerceAtMost(note.items.lastIndex)
        return note.copy(items = note.items.subList(from, to + 1), index = at - from)
    }

    private fun toJson(note: Note, thumbs: Boolean): JsonObject = buildJsonObject {
        put("v", note.version)
        put("at", note.savedAt)
        put("d", buildJsonObject {
            put("i", note.device.id)
            put("n", note.device.name)
            put("k", note.device.kind)
        })
        put("x", note.index)
        put("p", note.positionMs)
        note.origin?.let { o ->
            put("s", buildJsonObject {
                o.name?.let { put("n", it) }
                o.type?.let { put("t", it) }
                o.id?.let { put("i", it) }
                o.radio?.let { put("r", it) }
            })
        }
        put("q", buildJsonArray {
            note.items.forEach { item ->
                add(buildJsonObject {
                    put("k", item.kind.code)
                    put("i", item.id)
                    put("t", item.title)
                    put("a", item.artist)
                    item.album?.let { put("l", it) }
                    item.durationSec?.let { put("d", it) }
                    if (thumbs) item.thumb?.let { put("th", it) }
                    item.coverId?.let { put("c", it) }
                    item.hint?.let { h ->
                        put("h", buildJsonObject {
                            put("k", h.source.kind)
                            h.source.baseHash?.let { put("b", it) }
                            h.source.label?.let { put("n", it) }
                            h.trackId?.let { put("t", it) }
                        })
                    }
                    if (item.pinned) put("o", 1)
                    if (item.isVideo) put("m", 1)
                    item.sourceQuality?.let { put("sq", it) }
                })
            }
        })
    }

    /** A note read back, or null when [text] is not one (another app's comment, a newer version's). */
    fun decode(text: String?): Note? = runCatching {
        if (text.isNullOrBlank()) return null
        val o = json.parseToJsonElement(text) as? JsonObject ?: return null
        val version = o.int("v") ?: return null
        if (version > VERSION) return null // written by a newer build: its fields may mean something else
        val device = o["d"] as? JsonObject ?: return null
        val items = (o["q"] as? JsonArray).orEmpty().mapNotNull { element ->
            val i = element as? JsonObject ?: return@mapNotNull null
            val hint = (i["h"] as? JsonObject)?.let { h ->
                Hint(SourceRef(h.str("k") ?: return@let null, h.str("b"), h.str("n")), h.str("t"))
            }
            Item(
                kind = Item.Kind.of(i.str("k")) ?: return@mapNotNull null,
                id = i.str("i") ?: return@mapNotNull null,
                title = i.str("t").orEmpty(),
                artist = i.str("a").orEmpty(),
                album = i.str("l"),
                durationSec = i.int("d"),
                thumb = i.str("th"),
                coverId = i.str("c"),
                hint = hint,
                pinned = i.int("o") == 1,
                isVideo = i.int("m") == 1,
                sourceQuality = i.str("sq"),
            )
        }
        Note(
            version = version,
            savedAt = o.long("at") ?: return null,
            device = Device(device.str("i") ?: return null, device.str("n").orEmpty(), device.str("k").orEmpty()),
            index = (o.int("x") ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            positionMs = (o.long("p") ?: 0L).coerceAtLeast(0L),
            origin = (o["s"] as? JsonObject)?.let { s -> Origin(s.str("n"), s.str("t"), s.str("i"), s.str("r")) },
            items = items,
        )
    }.getOrNull()

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull

    // ── When to save ────────────────────────────────────────────────────

    /**
     * When each app writes its note, the same rules on both: on a pause, on a song change, when the
     * app goes to the background or the tray, and at most every [TICK_MS] while playing. Each event
     * is one try ([save]); a failure backs off ([BACKOFF_MS], doubling up to [BACKOFF_MAX_MS]) and the
     * events in between are dropped, never queued and replayed.
     *
     * Nothing is written until this device has played in this run ([armed]): both apps reopen their
     * own last queue at start, paused, and that reload (a song change to the player) would otherwise
     * overwrite the other device's newer note before it could be offered. A song change or a pause
     * is written after [DEBOUNCE_MS], so a burst of skips is one write.
     *
     * [snapshot] reads the player (the phone runs it on the main thread through its scope); [write]
     * sends the note. [enabled] is the setting and the server being configured.
     */
    class Saver(
        private val scope: CoroutineScope,
        private val enabled: () -> Boolean,
        private val snapshot: suspend () -> Note?,
        private val write: suspend (Note) -> Boolean,
        private val clock: () -> Long = System::currentTimeMillis,
        private val log: (String) -> Unit = {},
    ) {
        @Volatile var armed = false
            private set
        @Volatile private var playing = false
        @Volatile private var lastWriteAt = 0L
        @Volatile private var backoffUntil = 0L
        @Volatile private var backoffMs = BACKOFF_MS
        private var pendingJob: Job? = null
        private var tickJob: Job? = null
        private val lock = Mutex()

        fun onPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
            if (isPlaying) {
                armed = true
                startTicker()
            } else {
                tickJob?.cancel()
                tickJob = null
                if (armed) schedule("pause")
            }
        }

        fun onSongChanged() {
            if (armed) schedule("song change")
        }

        /** The app is leaving the screen (phone background, PC hidden to the tray): written at once. */
        fun onBackground() {
            if (armed) schedule("background", delayMs = 0L)
        }

        private fun startTicker() {
            if (tickJob?.isActive == true) return
            tickJob = scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    if (playing && clock() - lastWriteAt >= TICK_MS) save("tick")
                }
            }
        }

        private fun schedule(why: String, delayMs: Long = DEBOUNCE_MS) {
            pendingJob?.cancel()
            pendingJob = scope.launch {
                if (delayMs > 0) delay(delayMs)
                save(why)
            }
        }

        /** One try. Not on a failure's back-off, not while switched off. */
        suspend fun save(why: String): Boolean = lock.withLock {
            if (!enabled()) return@withLock false
            val now = clock()
            if (now < backoffUntil) return@withLock false
            val note = try {
                snapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("handoff: snapshot failed (${e.javaClass.simpleName})")
                null
            } ?: return@withLock false
            lastWriteAt = now
            val ok = try {
                write(note)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("handoff: save failed (${e.javaClass.simpleName}: ${e.message})")
                false
            }
            if (ok) {
                backoffMs = BACKOFF_MS
                log("handoff: saved on $why, ${note.items.size} songs, at ${ForkHandoff.clock(note.positionMs)}")
            } else {
                backoffUntil = now + backoffMs
                log("handoff: not saved on $why; next try after ${backoffMs / 1000} s")
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
            ok
        }

        companion object {
            const val TICK_MS = 30_000L
            const val DEBOUNCE_MS = 1_500L
            const val BACKOFF_MS = 2 * 60_000L
            const val BACKOFF_MAX_MS = 30 * 60_000L
        }
    }
}
