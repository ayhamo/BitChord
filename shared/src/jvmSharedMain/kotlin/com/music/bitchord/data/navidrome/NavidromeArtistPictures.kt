package com.music.bitchord.data.navidrome

import com.music.bitchord.data.AppleCatalogue
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.ForkAppleArtistArtRepository
import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.canvas.normalizeForMatch
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * A picture for every Navidrome artist (fork-only), phone and PC alike.
 *
 * ## Why
 *
 * A Navidrome artist page (`local:group:artist:navidrome:<name>`) is a tag grouping: it has no
 * picture of its own, so it opened on the grey person placeholder, while the Replay's Top artists
 * row had a photograph of the same artist (from `ArtistFacts`' YouTube artist search). The server
 * does keep artist pictures (`getCoverArt` with `ar-<id>`, from its agents or an `artist.*` file),
 * but for an artist it found none for it sends its own generic placeholder instead, which is no
 * better than ours.
 *
 * ## Which picture
 *
 * In this order, the first that is the artist:
 *  1. **The server's own**, when it is a real one. No Subsonic field says whether it is: every
 *     artist gets the same kind of `coverArt` id and `/share/img` links, picture or not. What does
 *     tell is the picture itself: the placeholder is one file, so at one size its bytes are the
 *     same for every artist that has it, and two different artists' photographs never are. So the
 *     picture is read once per artist at [PROBE_SIZE] px (a few kilobytes, [NavidromeClient.coverHash])
 *     and the hash two unrelated artists share is the placeholder ([PlaceholderLearner]), kept per server.
 *  2. A picture the app already holds for the name ([known]: the phone's `ArtistFacts`, the one the
 *     Replay shows), so the page and the Replay agree and nothing is asked.
 *  3. **YouTube Music's artist search**, as `ArtistFacts` asks it: an artist hit whose name is the
 *     same once case, accents and punctuation are folded away ([pickYouTube]).
 *  4. **Apple Music's portrait**, as Top Picks finds it (`AppleArtistPhotos`): an iTunes artist of
 *     the same name that also shares a song or album title with the server's songs of the artist.
 *
 * Never a near name: a stranger's face filed under the artist is worse than no picture.
 *
 * ## Kept
 *
 * Every answer goes into [NavidromePlatform.Store] (phone: plain prefs `bitchord_navidrome_artist_pictures`;
 * PC: its own preferences node; neither is in Export data), keyed by the server and the folded name,
 * so an artist is looked up once, not on every visit. "None of them has a picture" is kept for
 * [MISS_DAYS] (a picture may turn up later). A lookup that failed (network, HTTP error, a service
 * that didn't answer) is not kept at all; the same artist is left alone for [FAILED_COOLDOWN_MS]
 * so a list redrawing doesn't ask again at once, and is asked on a later visit.
 *
 * ## Asked
 *
 * One lookup at a time ([lock]), at least [MIN_GAP_MS] apart: a page's header, a list of artist
 * rows and the pass all queue on the same lock, and a row scrolled away cancels its wait. The
 * server is only asked through `/rest/…` with the existing client ([NavidromeClient]), never retried.
 * [startPass] works through the library's artists in the background after the library loads, one
 * every [PASS_SPACING_MS], and stops when [passAllowed] says no (phone: offline, metered, battery
 * saver), on three failures in a row, or on [stopPass]; what it found is kept, so the next library
 * load carries on where it stopped.
 */
object NavidromeArtistPictures {

    private const val TAG = "BitChordArtistPics"

    const val MISS_DAYS = 14L
    private const val MISS_MS = MISS_DAYS * 24 * 60 * 60 * 1000L
    private const val FAILED_COOLDOWN_MS = 60_000L
    private const val MIN_GAP_MS = 1_000L
    private const val PASS_SPACING_MS = 5_000L
    private const val PASS_FAILURES_TO_STOP = 3

    /** The size the server's picture is read at to tell a photograph from the placeholder. */
    const val PROBE_SIZE = 48

    /** Other artists read once per session, when no placeholder is known yet, to have something to compare with. */
    private const val BOOTSTRAP_SAMPLES = 3

    /** YouTube's artist hits looked at; the first of them with the same name is taken. */
    private const val YOUTUBE_HITS = 3

    /** Apple's same-named artists that share a title, tried for a portrait. */
    private const val APPLE_TRIES = 2

    /** Apple portraits, asked square at this size (the last path segment of mzstatic's address is the size). */
    private const val APPLE_PX = 1200

    private const val APPLE_HEAD_LIMIT = 64 * 1024

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"

    /** What is known of one artist. [at] is when it was settled. */
    sealed interface Entry {
        val at: Long

        /** The server's own picture is a real one: [url] is its `getCoverArt` address (no login). */
        data class Server(val url: String, val hash: String, val nameKey: String, override val at: Long) : Entry

        /** A picture from elsewhere ([source]: `known`, `youtube`, `apple`). */
        data class Picture(val url: String, val source: String, override val at: Long) : Entry

        /** Nobody had a picture of them; asked again after [MISS_DAYS]. */
        data class None(override val at: Long) : Entry
    }

    /** What one lookup came to: [entry] to keep, or null when it failed and nothing may be kept. */
    private class Outcome(val entry: Entry?)

    private lateinit var store: NavidromePlatform.Store

    /** A picture the app already has for an artist's name (the phone's `ArtistFacts`), or null. */
    @Volatile var known: (String) -> String? = { null }

    /** Whether the background pass may run now, beyond being online (phone: unmetered, no battery saver). */
    @Volatile var passAllowed: () -> Boolean = { true }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val lock = Mutex()
    @Volatile private var lastLookupAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var passJob: Job? = null

    private val learner = PlaceholderLearner()
    @Volatile private var learnerServer: String? = null
    @Volatile private var bootstrapped = false
    @Volatile private var artistList: Pair<String, List<NavidromeClient.ArtistRow>>? = null

    private val _updates = MutableStateFlow(0)

    /** Ticks when an answer lands or the placeholder is learned, so what is drawn reads [cached] again. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    /** [store] holds the answers (pictures' addresses, "none", the placeholder's hashes); nothing secret. */
    fun init(store: NavidromePlatform.Store) {
        this.store = store
        store.keys().filter { it.startsWith(ENTRY_PREFIX) }.forEach { key ->
            store.string(key)?.let(::decode)?.let { entries[key] = it }
        }
    }

    private val ready: Boolean get() = this::store.isInitialized

    // ── Reading, never asking ───────────────────────────────────────────────

    /** The picture known for the artist [name] on the saved server, or null (none, or not looked up yet). No request. */
    fun cached(name: String): String? = when (val entry = entryFor(name)) {
        is Entry.Server -> entry.url
        is Entry.Picture -> entry.url
        else -> null
    }

    /** Whether [name] has an answer that still stands (a picture, or a miss younger than [MISS_DAYS]). */
    fun settled(name: String): Boolean = entryFor(name) != null

    private fun entryFor(name: String, now: Long = System.currentTimeMillis()): Entry? {
        if (!ready) return null
        val key = storeKey(name) ?: return null
        return entries[key]?.takeIf { stands(it, now) }
    }

    // ── Asking ──────────────────────────────────────────────────────────────

    /**
     * The picture for the artist [name], looked up when nothing is known yet; null when there is
     * none, the lookup failed, or the app is offline. [titles] is the artist's song and album titles
     * on the server (Apple's proof), asked only when Apple is; without it (or empty) one `search3`
     * reads them. [id] and [coverArt] are the server's,
     * when the caller has them; else the id is remembered from the songs or found with one `search3`.
     */
    suspend fun pictureFor(
        name: String,
        id: String? = null,
        coverArt: String? = null,
        titles: (suspend () -> Collection<String>)? = null,
    ): String? {
        if (!ready || nameKey(name).isEmpty()) return null
        if (settled(name)) return cached(name)
        if (!NavidromeSettings.isConfigured() || NavidromePlatform.offline.value) return null
        val key = storeKey(name) ?: return null
        failedAt[key]?.let { if (System.currentTimeMillis() - it < FAILED_COOLDOWN_MS) return null }
        // Off the caller's thread: the PC's store writes the preferences through on every answer.
        return withContext(Dispatchers.IO) { resolve(name, key, id, coverArt, titles) }
    }

    private suspend fun resolve(name: String, key: String, id: String?, coverArt: String?, titles: (suspend () -> Collection<String>)?): String? {
        val outcome = lock.withLock {
            // Asked meanwhile by whoever held the lock (a header and its row, the pass).
            if (settled(name)) return cached(name)
            val wait = lastLookupAt + MIN_GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                lookup(name, id, coverArt, titles)
            } finally {
                lastLookupAt = System.currentTimeMillis()
            }
        }
        val entry = outcome.entry
        if (entry == null) {
            failedAt[key] = System.currentTimeMillis()
            return null
        }
        keep(key, entry)
        return cached(name)
    }

    private suspend fun lookup(name: String, id: String?, coverArt: String?, titles: (suspend () -> Collection<String>)?): Outcome {
        val wanted = nameKey(name)
        return try {
            val client = NavidromeRepository.client()
            serverPicture(client, name, wanted, id, coverArt)?.let { return Outcome(it) }
            known(name)?.takeIf { it.isNotBlank() }?.let { return Outcome(Entry.Picture(it, "known", now())) }
            // A failed search is no answer: getOrThrow, so it isn't kept as "none".
            pickYouTube(name, YtMusicRepository.search(name, SearchFilter.ARTISTS).getOrThrow())
                ?.let { return Outcome(Entry.Picture(it, "youtube", now())) }
            // The page's own songs when it has them, else the server's for the name (one `search3`).
            val proof = AppleCatalogue.keys(titles?.invoke()?.takeIf { it.isNotEmpty() } ?: titlesOf(client, name))
            if (proof.isNotEmpty()) {
                withContext(Dispatchers.IO) { applePortrait(name, proof) }?.let { return Outcome(Entry.Picture(it, "apple", now())) }
            }
            Log.d(TAG, "no picture anywhere for $name")
            Outcome(Entry.None(now()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "lookup for $name failed, not kept: ${e.javaClass.simpleName}: ${e.message}")
            Outcome(null)
        }
    }

    /**
     * The server's own picture of the artist when it is a real one, else null (the placeholder,
     * none at all, or an artist the server has no id for under that exact name).
     */
    private suspend fun serverPicture(client: NavidromeClient, name: String, wanted: String, id: String?, coverArt: String?): Entry? {
        val artistId = id ?: NavidromeArtistInfo.artistId(name, client) ?: return null
        val art = coverArt ?: "ar-$artistId"
        val hash = client.coverHash(art, PROBE_SIZE) ?: return null
        val learned = learnerFor()
        if (learned.known().isEmpty()) bootstrap(client, learned, wanted)
        val placeholder = learned.note(hash, wanted)
        if (learned.known().size > savedPlaceholders().size) learnedPlaceholder(learned.known())
        return if (placeholder) null else Entry.Server(client.coverUrl(art), hash, wanted, now())
    }

    /**
     * Before anything can be compared, a few other artists of the library are read once per session
     * (spread over the alphabet), so the very first artist page can already tell the placeholder.
     */
    private suspend fun bootstrap(client: NavidromeClient, learned: PlaceholderLearner, wanted: String) {
        if (bootstrapped) return
        bootstrapped = true
        val others = artists(client).filter { nameKey(it.name) != wanted }
        if (others.isEmpty()) return
        val picks = (1..BOOTSTRAP_SAMPLES).map { others[(others.size * it / (BOOTSTRAP_SAMPLES + 1)).coerceAtMost(others.size - 1)] }.distinct()
        for (row in picks) {
            delay(MIN_GAP_MS / 2)
            client.coverHash(row.coverArt ?: "ar-${row.id}", PROBE_SIZE)?.let { learned.note(it, nameKey(row.name)) }
        }
    }

    /** The library's artists, read once per server and session. */
    private suspend fun artists(client: NavidromeClient): List<NavidromeClient.ArtistRow> {
        val server = serverKey()
        artistList?.takeIf { it.first == server }?.let { return it.second }
        return client.artists().also { artistList = server to it }
    }

    /** The learner for the saved server, seeded with its placeholders and the real pictures kept so far. */
    private fun learnerFor(): PlaceholderLearner {
        val server = serverKey()
        if (learnerServer != server) {
            learnerServer = server
            bootstrapped = false
            val prefix = entryPrefix(server)
            learner.reset(
                placeholders = savedPlaceholders(),
                pictures = entries.filterKeys { it.startsWith(prefix) }.values.filterIsInstance<Entry.Server>().map { it.hash to it.nameKey },
            )
        }
        return learner
    }

    private fun savedPlaceholders(): Set<String> =
        store.string(placeholderKey())?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /**
     * A hash turned out to be the placeholder: kept, and every "real" server picture with that hash
     * (one taken before there was anything to compare it with) is dropped, so it is looked up again.
     */
    private fun learnedPlaceholder(hashes: Set<String>) {
        store.putStrings(mapOf(placeholderKey() to hashes.sorted().joinToString(",")))
        val prefix = entryPrefix(serverKey())
        val stale = entries.filter { (key, entry) -> key.startsWith(prefix) && entry is Entry.Server && entry.hash in hashes }.keys
        if (stale.isNotEmpty()) {
            stale.forEach { entries.remove(it) }
            store.remove(stale)
        }
        Log.d(TAG, "placeholder learned (${hashes.size} known), ${stale.size} pictures to look up again")
        _updates.value++
    }

    private fun keep(key: String, entry: Entry) {
        entries[key] = entry
        failedAt.remove(key)
        store.putStrings(mapOf(key to encode(entry)))
        _updates.value++
    }

    // ── The pass over the library ───────────────────────────────────────────

    /**
     * Looks up every artist of the library that has no answer yet, one every [PASS_SPACING_MS], in
     * the background. Called after the library loads; a pass already running is left alone. It
     * stops by itself (offline, [passAllowed] says no, three failures in a row, all done), and the
     * next call carries on: what was found is kept, so only the artists still unsettled are asked.
     */
    fun startPass() {
        if (!ready) return
        synchronized(this) {
            if (passJob?.isActive == true) return
            passJob = scope.launch { runPass() }
        }
    }

    /** Stops the pass (the server changed, or the caller wants it quiet); [startPass] resumes it. */
    fun stopPass() {
        synchronized(this) {
            passJob?.cancel()
            passJob = null
        }
    }

    private fun mayPass(): Boolean =
        NavidromeSettings.isConfigured() && !NavidromePlatform.offline.value && runCatching { passAllowed() }.getOrDefault(false)

    private suspend fun runPass() {
        if (!mayPass()) return
        val client = runCatching { NavidromeRepository.client() }.getOrNull() ?: return
        val rows = try {
            artists(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "pass: artist list failed: ${e.message}")
            return
        }
        val todo = rows.filter { nameKey(it.name).isNotEmpty() && !settled(it.name) }
        Log.d(TAG, "pass: ${todo.size} of ${rows.size} artists to look up")
        var failures = 0
        var found = 0
        for (row in todo) {
            if (!mayPass()) {
                Log.d(TAG, "pass: paused (offline, metered or battery saver); carries on next time")
                return
            }
            if (settled(row.name)) continue // a page asked meanwhile
            NavidromeArtistInfo.noteId(row.name, row.id)
            val picture = pictureFor(row.name, row.id, row.coverArt)
            if (settled(row.name)) {
                failures = 0
                if (picture != null) found++
            } else if (++failures >= PASS_FAILURES_TO_STOP) {
                Log.d(TAG, "pass: $failures failures in a row, stopped; carries on next time")
                return
            }
            delay(PASS_SPACING_MS)
        }
        Log.d(TAG, "pass: done, $found pictures found")
    }

    /** The server's song and album titles for the artist [name] (one `search3`), Apple's proof. */
    private suspend fun titlesOf(client: NavidromeClient, name: String): List<String> =
        client.searchSongs(name, 100).filter { it.artist == name }.flatMap { listOfNotNull(it.title, it.album) }

    // ── Apple ───────────────────────────────────────────────────────────────

    /**
     * Apple Music's square portrait of [name], the way `AppleArtistPhotos` finds Top Picks' faces,
     * except that a failed request throws instead of reading as "no": the iTunes search, the
     * same-named candidates ([ForkAppleArtistArtRepository.candidates]), each proved by a shared
     * title ([sharesTitles]), then the artist page's `og:image`, read off its head only.
     */
    private fun applePortrait(name: String, proof: Set<String>): String? {
        val search = get("https://itunes.apple.com/search?term=${URLEncoder.encode(name, "UTF-8")}&entity=musicArtist&limit=5")
        var tried = 0
        for ((id, page) in ForkAppleArtistArtRepository.candidates(search, AppleCatalogue.matchKey(name))) {
            if (!sharesTitles(id, proof)) {
                Log.d(TAG, "$name: Apple artist $id shares no titles, skipped")
                continue
            }
            ogImage(page)?.let { return squarePortrait(it) }
            if (++tried >= APPLE_TRIES) break
        }
        return null
    }

    /** `AppleCatalogue.sharesTitles`, but a failed request throws rather than counting as "no". */
    private fun sharesTitles(artistId: Long, proof: Set<String>): Boolean {
        val body = get("https://itunes.apple.com/lookup?id=$artistId&entity=song&limit=200")
        val rows = Json.parseToJsonElement(body)
            .let { (it as? JsonObject)?.get("results") as? JsonArray }
            ?: return false
        return rows.any { row ->
            val o = row as? JsonObject ?: return@any false
            listOf("trackName", "collectionName").any { field ->
                (o[field] as? JsonPrimitive)?.content?.let(AppleCatalogue::titleKey) in proof
            }
        }
    }

    private val OG_IMAGE = Regex("""property="og:image"\s+content="([^"]+)"""")

    private fun ogImage(pageUrl: String): String? {
        val request = Request.Builder().url(pageUrl).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Apple page HTTP ${response.code}")
            val source = response.body.source()
            val buffer = Buffer()
            val head = StringBuilder()
            while (head.length < APPLE_HEAD_LIMIT && source.read(buffer, 8_192) != -1L) {
                head.append(buffer.readUtf8())
                OG_IMAGE.find(head)?.let { return it.groupValues[1].takeIf(::isApplePortrait) }
                if ("</head>" in head) return null
            }
            return null
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Apple HTTP ${response.code}")
            return response.body.string()
        }
    }

    // ── Rules (public for the tests) ────────────────────────────────────────

    /** An artist's name as compared: case, accents and punctuation folded away ("Beyoncé" = "beyonce"). */
    fun nameKey(name: String): String = name.normalizeForMatch()

    /**
     * YouTube's picture for [name] from an artist search's [hits]: among the first [YOUTUBE_HITS]
     * artist hits, the first whose name folds to the same as [name] and that has a picture. A
     * search returns *someone* for any name; anyone not named the same is somebody else.
     */
    fun pickYouTube(name: String, hits: List<SearchResult>): String? {
        val wanted = nameKey(name).ifEmpty { return null }
        return hits.asSequence()
            .filterIsInstance<SearchResult.Browse>()
            .map { it.item }
            .filter { it.type == BrowseType.ARTIST }
            .take(YOUTUBE_HITS)
            .firstOrNull { nameKey(it.title) == wanted && !it.thumbnailUrl.isNullOrBlank() }
            ?.thumbnailUrl
    }

    /**
     * A portrait rather than a stand-in (as `AppleArtistPhotos.isArtistPhoto`): Apple's artist photos
     * live under `Features…` or `AMCArtistImages…`; an artist without one gets a record cover (`Music…`).
     */
    fun isApplePortrait(url: String): Boolean {
        val bucket = url.substringAfter("mzstatic.com/image/thumb/", "").substringBefore('/')
        return bucket.startsWith("Features") || bucket.startsWith("AMCArtistImages")
    }

    /** Apple's `og:image` (a wide 1200x630 crop) as the square portrait it is cut from. */
    fun squarePortrait(url: String): String = url.substringBeforeLast('/') + "/${APPLE_PX}x${APPLE_PX}cc.jpg"

    /** Whether [entry] still stands at [now]: a picture always, a miss for [MISS_DAYS]. */
    fun stands(entry: Entry, now: Long): Boolean = entry !is Entry.None || now - entry.at in 0 until MISS_MS

    /** One line per field: kind, address, detail, time, name. Nothing in it is secret. */
    fun encode(entry: Entry): String = when (entry) {
        is Entry.Server -> listOf("server", entry.url, entry.hash, entry.at.toString(), entry.nameKey)
        is Entry.Picture -> listOf("picture", entry.url, entry.source, entry.at.toString(), "")
        is Entry.None -> listOf("none", "", "", entry.at.toString(), "")
    }.joinToString("\n")

    /** [encode] read back; null for anything else (an older or damaged value is asked again). */
    fun decode(value: String): Entry? {
        val parts = value.split('\n')
        if (parts.size != 5) return null
        val at = parts[3].toLongOrNull() ?: return null
        return when (parts[0]) {
            "server" -> if (parts[1].isNotBlank() && parts[2].isNotBlank()) Entry.Server(parts[1], parts[2], parts[4], at) else null
            "picture" -> if (parts[1].isNotBlank()) Entry.Picture(parts[1], parts[2], at) else null
            "none" -> Entry.None(at)
            else -> null
        }
    }

    // ── Keys ────────────────────────────────────────────────────────────────

    private const val ENTRY_PREFIX = "p:"

    /** The saved server, as the keys tell servers apart: its address only, never the login. */
    private fun serverKey(): String = NavidromeSettings.url.value.trim().trimEnd('/').lowercase(java.util.Locale.ROOT)

    private fun entryPrefix(server: String): String = ENTRY_PREFIX + NavidromeClient.md5(server).take(8) + ":"

    /** Hashed: Java Preferences keys (the PC's store) are at most 80 characters, names aren't. */
    private fun storeKey(name: String): String? {
        val key = nameKey(name).ifEmpty { return null }
        return entryPrefix(serverKey()) + NavidromeClient.md5(key)
    }

    private fun placeholderKey(): String = "ph:" + NavidromeClient.md5(serverKey()).take(8)

    private fun now() = System.currentTimeMillis()
}

/**
 * Tells Navidrome's placeholder from real artist photographs by their bytes (see
 * [NavidromeArtistPictures]): a hash two unrelated artists share is the placeholder. Two names
 * where one holds the other ("Levent Geiger", "Levent Geiger & Band") may well carry the same
 * photograph, so they don't count. Not thread-safe: used under the lookups' lock.
 */
class PlaceholderLearner {

    private val placeholders = HashSet<String>()

    /** hash → the folded name of the first artist seen with it. */
    private val seen = HashMap<String, String>()

    fun reset(placeholders: Collection<String>, pictures: Collection<Pair<String, String>>) {
        this.placeholders.clear()
        this.placeholders += placeholders
        seen.clear()
        pictures.forEach { (hash, name) -> seen.putIfAbsent(hash, name) }
    }

    fun known(): Set<String> = HashSet(placeholders)

    /** Notes that the artist [nameKey]'s picture hashes to [hash]; true when that is the placeholder. */
    fun note(hash: String, nameKey: String): Boolean {
        if (hash in placeholders) return true
        val other = seen.putIfAbsent(hash, nameKey) ?: return false
        if (related(other, nameKey)) return false
        placeholders += hash
        return true
    }

    /** The same artist, or one credit holding the other, word for word. */
    fun related(a: String, b: String): Boolean = a == b || " $a ".contains(" $b ") || " $b ".contains(" $a ")
}
