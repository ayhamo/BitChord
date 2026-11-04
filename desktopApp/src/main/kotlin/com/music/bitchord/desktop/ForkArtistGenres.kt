package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/**
 * Fork: the Replay's genres on the PC, the way the phone's `ArtistFacts` works them out.
 *
 * ## Where the phone's genres come from
 *
 * Not from the music: YouTube Music's responses carry no genre, the PC's Navidrome client reads
 * none, and the phone reads no file tags for this either. The phone asks Last.fm's
 * `artist.getTopTags` for each lead artist it has heard, once, and keeps the answer on the device;
 * the genre chart is then each artist's listening, credited to their genres. This is the same, with
 * the same request, the same vocabulary (a tag only counts when it is a real genre, "seen live"
 * and "favourites" never do), the same two genres per artist, and the same 1.5 s between lookups.
 *
 * ## The gates
 *
 * - The phone's "Work out genres" switch (`replay_genres`, on by default), under Settings > Your
 *   data on both. Off: no request is made and the genre chart is not drawn; nothing else changes.
 *   It travels in the phone-format backup ([ForkBackup.MAIN]).
 * - A Last.fm API key: the PC's own scrobbling key ([DesktopScrobbling.apiKey], the one entered in
 *   Settings, else the build's), where the phone uses its build's key. No key, no genres.
 *
 * Only the artist's name is sent (no track, no time, no account), and only to Last.fm.
 *
 * ## When it asks
 *
 * From [DesktopListeningStats.record] (as the phone's `record` calls `ArtistFacts.noticed`), and for
 * the top artists whenever the Replay is drawn, so a Replay built before this existed fills in.
 * One try per artist per run: a failed request (offline, an error page) is not retried until the
 * next start, an answer (also "no genre") is kept and asked again only after 14 days. No retry loop.
 */
internal object ForkArtistGenres {

    private val preferences: Preferences = Preferences.userRoot().node("com/music/bitchord/replay_genres")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** artist (lowercased) → what Last.fm said. */
    private val known = ConcurrentHashMap<String, StoredArtist>()

    /** Names already asked about this run, so a song on repeat asks once. */
    private val queued = ConcurrentHashMap.newKeySet<String>()

    private val requests = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var dirty = false

    @Volatile
    private var started = false

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    private val _enabled = MutableStateFlow(DesktopPersistence().preferences.get(KEY_ENABLED, "true") != "false")

    /** The phone's "Work out genres". */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        runCatching { DesktopPersistence().preferences.put(KEY_ENABLED, on.toString()) }
        _revision.value++
    }

    private val _revision = MutableStateFlow(0)

    /**
     * Bumped when an answer lands (or the switch moves), so an open Replay recharts its genres
     * instead of waiting to be opened again.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Whether a genre chart can be drawn at all: the switch is on and there is a key to ask with. */
    val available: Boolean
        get() = _enabled.value && !DesktopScrobbling.apiKey.isNullOrBlank()

    /** What is known for [artist] (a lead artist's name); empty when off, unknown or not a genre. */
    fun genresFor(artist: String): List<String> {
        if (!available) return emptyList()
        ensureStarted()
        return known[key(artist)]?.genres.orEmpty()
    }

    /** [artist] was played or charted: asks about them if there is nothing recent on file. */
    fun noticed(artist: String) {
        if (!available) return
        val key = key(artist)
        if (key.isEmpty() || key.length > MAX_NAME_LENGTH) return
        ensureStarted()
        val entry = known[key]
        if (entry != null && !entry.wants()) return
        if (!queued.add(key)) return
        requests.trySend(artist.trim())
    }

    private fun StoredArtist.wants(): Boolean =
        genres.isEmpty() && System.currentTimeMillis() - genresAt > TimeUnit.DAYS.toMillis(RETRY_DAYS)

    @Synchronized
    private fun ensureStarted() {
        if (started) return
        started = true
        load()
        scope.launch { worker() }
        // On a timer rather than per answer: the store holds every artist ever asked about.
        scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                save()
            }
        }
    }

    // ── The lookup ──────────────────────────────────────────────────────

    private suspend fun worker() {
        for (name in requests) {
            if (available) {
                runCatching { fetch(name) }
                    .onFailure { DesktopTrackLog.log("Replay genres: lookup for $name failed (${it.javaClass.simpleName})") }
                _revision.value++
            } else {
                // Not asked (switched off, or no key yet): free the name, so switching back on asks.
                queued.remove(key(name))
                continue
            }
            delay(REQUEST_SPACING_MS)
        }
    }

    private fun fetch(name: String) {
        val apiKey = DesktopScrobbling.apiKey?.takeIf(String::isNotBlank) ?: return
        val url = "https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags" +
            "&artist=${encode(name)}&api_key=${encode(apiKey)}&autocorrect=1&format=json"
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "BitChord/${DesktopUpdateChecker.currentVersion}")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        // An error page is not an answer: nothing is filed, so the next run asks again.
        if (response.statusCode() !in 200..299) return
        val genres = genresFrom(parseTopTags(response.body()))
        known[key(name)] = StoredArtist(key = key(name), genres = genres, genresAt = System.currentTimeMillis())
        dirty = true
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** The tag names in a `toptags` response, most-used first; empty for anything else. */
    internal fun parseTopTags(body: String): List<String> = runCatching {
        Json.parseToJsonElement(body).jsonObject["toptags"]
            ?.jsonObject?.get("tag")?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
    }.getOrNull().orEmpty()

    /** The first two of [tags] that are genres, in the vocabulary's spelling. */
    internal fun genresFrom(tags: List<String>): List<String> =
        tags.asSequence().mapNotNull(::canonical).distinct().take(MAX_GENRES_PER_ARTIST).toList()

    /**
     * The genre chart: every artist's listening credited to each of their genres, ranked by time.
     * [artists] in chart order (the first artist of a genre gives it its picture, as on the phone,
     * though the rows drop it); [genresOf] answers for an artist row's title.
     */
    internal fun rank(artists: List<DesktopRankedEntry>, genresOf: (String) -> List<String>): List<DesktopRankedEntry> {
        val ms = LinkedHashMap<String, Long>()
        val plays = LinkedHashMap<String, Int>()
        val art = LinkedHashMap<String, String?>()
        artists.forEach { artist ->
            genresOf(artist.title).forEach { genre ->
                ms[genre] = (ms[genre] ?: 0L) + artist.ms
                plays[genre] = (plays[genre] ?: 0) + artist.plays
                if (art[genre] == null) art[genre] = artist.artworkUrl
            }
        }
        return ms.entries.sortedByDescending { it.value }.map {
            DesktopRankedEntry(title = it.key, subtitle = null, artworkUrl = art[it.key], browseId = null, ms = it.value, plays = plays[it.key] ?: 0)
        }
    }

    /**
     * The Replay's genre chart for [artists], asking about the top ones it doesn't know yet.
     * A PC artist row is the whole credit, so each is looked up by its lead artist, the name the
     * phone files listening under.
     */
    fun chart(artists: List<DesktopRankedEntry>): List<DesktopRankedEntry> {
        if (!available) return emptyList()
        artists.take(ARTISTS_TO_RESOLVE).forEach { noticed(DesktopListeningStats.primaryArtist(it.title)) }
        return rank(artists) { genresFor(DesktopListeningStats.primaryArtist(it)) }
    }

    /**
     * A tag as a genre, or null if it isn't one: punctuation and a few spellings of the same genre
     * normalised ("hip-hop"/"hip hop", "r&b"/"rnb"), then matched against [VOCABULARY].
     */
    internal fun canonical(tag: String): String? {
        val cleaned = tag.trim().lowercase(Locale.ROOT)
            .replace('-', ' ')
            .replace("&", "and")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return null
        return VOCABULARY[cleaned] ?: VOCABULARY[ALIASES[cleaned] ?: return null]
    }

    private fun key(artist: String): String = artist.trim().lowercase(Locale.ROOT)

    // ── Persistence ─────────────────────────────────────────────────────

    private fun load() {
        val text = DesktopPreferenceChunks.read(preferences, KEY_ARTISTS) ?: return
        runCatching { json.decodeFromString(Stored.serializer(), text) }
            .onSuccess { stored -> stored.artists.forEach { known[it.key] = it } }
            .onFailure { DesktopTrackLog.log("Replay genres: unreadable store dropped") }
    }

    private fun save() {
        if (!dirty) return
        dirty = false
        runCatching {
            val stored = Stored(artists = known.values.sortedByDescending { it.genresAt }.take(MAX_ARTISTS))
            DesktopPreferenceChunks.write(preferences, KEY_ARTISTS, json.encodeToString(Stored.serializer(), stored))
        }
    }

    @Serializable
    private data class Stored(val version: Int = 1, val artists: List<StoredArtist> = emptyList())

    @Serializable
    internal data class StoredArtist(val key: String, val genres: List<String> = emptyList(), val genresAt: Long = 0L)

    /** The phone's key for "Work out genres", in the PC's main settings node. */
    const val KEY_ENABLED = "replay_genres"
    private const val KEY_ARTISTS = "artists"
    private const val MAX_NAME_LENGTH = 120
    private const val MAX_GENRES_PER_ARTIST = 2
    private const val MAX_ARTISTS = 4_000
    private const val REQUEST_SPACING_MS = 1_500L
    private const val SAVE_INTERVAL_MS = 5_000L
    private const val RETRY_DAYS = 14L

    /** The phone's `ListeningStats.ARTISTS_TO_RESOLVE`. */
    private const val ARTISTS_TO_RESOLVE = 15

    // The phone's lists, word for word (`ArtistFacts.SPELLINGS`, `VOCABULARY`, `ALIASES`).
    // SPELLINGS first: VOCABULARY's initialiser reads it.

    private val SPELLINGS = mapOf(
        "randb" to "R&B",
        "edm" to "EDM",
        "lo fi" to "Lo-Fi",
        "hip hop" to "Hip-Hop",
        "k pop" to "K-Pop",
        "j pop" to "J-Pop",
        "j rock" to "J-Rock",
        "c pop" to "C-Pop",
        "drum and bass" to "Drum & Bass",
        "singer songwriter" to "Singer-Songwriter",
        "post punk" to "Post-Punk",
        "post rock" to "Post-Rock",
        "bossa nova" to "Bossa Nova",
    )

    private val VOCABULARY: Map<String, String> = listOf(
        "pop", "rock", "hip hop", "rap", "randb", "soul", "funk", "jazz", "blues",
        "country", "folk", "indie", "indie pop", "indie rock", "alternative",
        "alternative rock", "metal", "heavy metal", "punk", "punk rock", "hardcore",
        "electronic", "house", "deep house", "techno", "trance", "dubstep",
        "drum and bass", "edm", "ambient", "lo fi", "synthpop", "disco",
        "classical", "opera", "soundtrack", "instrumental", "acoustic",
        "reggae", "reggaeton", "dancehall", "ska", "latin", "salsa", "bossa nova",
        "afrobeats", "afrobeat", "k pop", "j pop", "j rock", "c pop",
        "bollywood", "punjabi", "desi", "bhangra", "hindi", "sufi", "ghazal",
        "singer songwriter", "emo", "grunge", "shoegaze", "psychedelic",
        "progressive rock", "hard rock", "garage rock", "post punk", "new wave",
        "gospel", "christian", "world", "experimental", "trap", "drill", "grime",
        "phonk", "hyperpop", "chillout", "downtempo", "jungle", "garage",
        "bluegrass", "americana", "swing", "big band", "motown", "britpop",
        "dream pop", "art pop", "noise", "industrial", "gothic", "doom metal",
        "black metal", "death metal", "thrash metal", "metalcore", "post rock",
        "math rock", "jam band", "surf rock", "rockabilly", "boom bap",
        "cloud rap", "conscious hip hop", "west coast rap", "east coast rap",
    ).associateWith { normalised ->
        SPELLINGS[normalised] ?: normalised.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }
    }

    private val ALIASES = mapOf(
        "rnb" to "randb",
        "r and b" to "randb",
        "rhythm and blues" to "randb",
        "contemporary randb" to "randb",
        "hiphop" to "hip hop",
        "hip hop rap" to "hip hop",
        "lofi" to "lo fi",
        "lo fi hip hop" to "lo fi",
        "chillhop" to "lo fi",
        "kpop" to "k pop",
        "jpop" to "j pop",
        "jrock" to "j rock",
        "cpop" to "c pop",
        "korean" to "k pop",
        "dnb" to "drum and bass",
        "drum n bass" to "drum and bass",
        "drumandbass" to "drum and bass",
        "electronica" to "electronic",
        "electro" to "electronic",
        "dance" to "electronic",
        "electropop" to "synthpop",
        "synth pop" to "synthpop",
        "indierock" to "indie rock",
        "indiepop" to "indie pop",
        "alt rock" to "alternative rock",
        "altrock" to "alternative rock",
        "singersongwriter" to "singer songwriter",
        "female vocalists" to "pop",
        "hindi pop" to "bollywood",
        "indian" to "desi",
        "filmi" to "bollywood",
        "afro beats" to "afrobeats",
        "afropop" to "afrobeats",
        "amapiano" to "afrobeats",
        "regueton" to "reggaeton",
        "latin pop" to "latin",
        "trip hop" to "downtempo",
        "nu metal" to "metal",
        "classic rock" to "rock",
        "soft rock" to "rock",
        "pop rock" to "rock",
        "pop punk" to "punk",
        "hardcore punk" to "hardcore",
        "orchestral" to "classical",
        "film score" to "soundtrack",
        "score" to "soundtrack",
        "ost" to "soundtrack",
        "chill" to "chillout",
        "chillwave" to "chillout",
        "worship" to "christian",
        "rap rock" to "rap",
        "gangsta rap" to "rap",
        "underground hip hop" to "hip hop",
    )
}

/**
 * Settings > Your data: the phone's "Work out genres" switch. Greyed (not hidden, owner rule) with
 * a note while there is no Last.fm API key, since the chart cannot be drawn without one.
 */
@Composable
internal fun ForkReplayGenresToggle() {
    val on by ForkArtistGenres.enabled.collectAsState()
    val typedKey by DesktopScrobbleSettings.lastfmApiKey.collectAsState() // read so a key typed in Integrations ungreys it
    val noKey = typedKey.isBlank() && DesktopScrobbling.apiKey.isNullOrBlank()
    ForkDisabledWhen(noKey) { SettingsToggle(
        DesktopStrings["work_out_genres", "Work out genres"],
        if (noKey) {
            DesktopStrings["replay_genres_no_key_subtitle", "Needs a Last.fm API key (Settings > Integrations > Last.fm)"]
        } else if (on) {
            DesktopStrings["replay_genres_enabled_subtitle", "Asks Last.fm for an artist’s genres. Only the artist name is sent."]
        } else {
            DesktopStrings["replay_genres_disabled_subtitle", "The Replay genre chart stays hidden while this is off"]
        },
        on,
        ForkArtistGenres::setEnabled,
    ) }
}
