package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.Properties

/**
 * Fork: the canvas rulebook's lasting cache (§2), per source, on disk, kept across restarts.
 *
 * Upstream keeps 64 answers in memory per run (`CanvasRepository`, `DesktopCanvasClient`), so
 * every song played after a restart pays for every provider's search again, and a song that has
 * no canvas, the common case, is asked about again on every launch. The rulebook keeps a found
 * clip at any age (it plays offline when it is a file: the music-video loops are), a miss for 24 h
 * ([ForkCanvasRules.MISS_MS]), and never a failed lookup (the network, a token, a 429): that is
 * asked again next time. Each source is keyed as the rulebook says: the music video by the song's
 * id, Spotify and Apple by title and artist ([ForkCanvasRules.videoKey] and the others).
 *
 * The in-memory caches stay above this, unchanged: they answer within a run and carry the fork's
 * "a miss without the album is provisional" rule. A source whose search uses the album (Apple)
 * saves a miss only when the album was known, so a miss reached before the album landed doesn't
 * stop the second, better search for a day.
 *
 * A properties file ("song.<key>" = "<ms>|<value>", the phone extension's layout), written beside
 * itself and moved over, so a kill mid-write leaves the old file whole.
 */
object ForkCanvasStore {

    private const val TAG = "ForkCanvasStore"
    private const val FILE = "fork-canvas.properties"
    /** Old entries go first past this many, so a long-lived install's file stays small. */
    private const val MAX_ENTRIES = 4000

    @Volatile private var file: File? = null
    private var props: Properties? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Where the file lives: the phone's files dir, the PC's settings folder. Before any lookup. */
    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, FILE)
        val p = Properties()
        if (f.exists()) runCatching { f.inputStream().use(p::load) }.onFailure { Log.w(TAG, "read failed: ${it.message}") }
        props = p
        file = f
    }

    /**
     * Forgets every saved answer. Not wired to a button yet: clearing the clip caches needs no call,
     * a saved loop whose file is gone is looked up again ([ForkCanvasRules.readSaved]).
     */
    @Synchronized
    fun clear() {
        props?.clear()
        save()
    }

    /** What is saved under [key] now, read by the rulebook's rule ([ForkCanvasRules.readSaved]). */
    @Synchronized
    fun read(key: String, nowMs: Long = System.currentTimeMillis()): ForkCanvasRules.Saved? =
        ForkCanvasRules.readSaved(props?.getProperty("song.$key"), ::playable, nowMs)

    /** A saved clip, back as the artwork it was ([read] answered [ForkCanvasRules.Saved.Clip]). */
    fun artworkOf(saved: ForkCanvasRules.Saved.Clip): CanvasArtwork? = decode(saved.value)

    @Synchronized
    fun put(key: String, artwork: CanvasArtwork?, nowMs: Long = System.currentTimeMillis()) {
        val p = props ?: return
        p.setProperty("song.$key", ForkCanvasRules.saved(artwork?.let(::encode), nowMs))
        if (p.size > MAX_ENTRIES) {
            p.entries.sortedBy { (it.value as String).substringBefore('|').toLongOrNull() ?: 0L }
                .take(p.size - MAX_ENTRIES).forEach { p.remove(it.key) }
        }
        save()
    }

    @Synchronized
    fun remove(key: String) {
        if (props?.remove("song.$key") != null) save()
    }

    /**
     * One source's answer through the store: a saved clip or miss is the answer; otherwise [lookUp]
     * runs, and its answer is saved unless the lookup failed (a provider reported it to the running
     * [ForkCanvasLookup.Scope], or it threw) or it is a miss [saveMiss] says not to keep.
     */
    suspend fun cached(key: String, saveMiss: Boolean = true, lookUp: suspend () -> CanvasArtwork?): CanvasArtwork? {
        when (val saved = read(key)) {
            is ForkCanvasRules.Saved.Clip -> decode(saved.value)?.let { return it }
            ForkCanvasRules.Saved.Miss -> return null
            null -> Unit
        }
        val failuresBefore = ForkCanvasLookup.failureCount()
        val found = lookUp() // a throw is a failure: nothing is saved, the caller's firstHit reports it
        val failed = ForkCanvasLookup.failureCount() != failuresBefore
        if (found != null) put(key, found)
        else if (!failed && saveMiss) put(key, null)
        return found
    }

    /**
     * A clip is still playable: its file is there (a local loop), or it is a remote clip that would
     * pass the trust a fresh answer passes. The file on disk is not a trusted input (an older build,
     * a hand edit, a copied settings folder can put anything there), and a saved clip skips every
     * provider check: without this a saved `http://` or off-host "Spotify" URL was handed to the
     * player for as long as the entry lived. So a Spotify clip must be on Spotify's media host
     * ([ForkSpotifyCanvasRules.trusted], rule 4.6) and any other remote clip https; each URL the
     * player may open (the clip, its fallback, the tall cut) is checked. An entry that fails reads
     * back as nothing, so the source is asked again and a good answer replaces it.
     */
    internal fun playable(value: String): Boolean {
        val art = decode(value) ?: return false
        val url = art.url
        if (isLocal(url)) return File(localPath(url)).length() > 0
        // An Apple clip is held to Apple's https hosts (audit 2026-11-26, ForkAppleCanvasRules.trustedMotion).
        val trust: (String) -> Boolean = when (art.source) {
            CanvasSource.SPOTIFY -> ForkSpotifyCanvasRules::trusted
            CanvasSource.APPLE -> ForkAppleCanvasRules::trustedMotion
            else -> ::https
        }
        return listOfNotNull(url, art.fallbackUrl, art.portraitUrl).all(trust)
    }

    private fun https(url: String): Boolean =
        runCatching { java.net.URI(url).let { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrEmpty() } }
            .getOrDefault(false)

    fun isLocal(url: String): Boolean = url.startsWith("/") || url.startsWith("file:") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(url)

    fun localPath(url: String): String = if (url.startsWith("file:")) java.net.URI(url).path else url

    private fun encode(a: CanvasArtwork): String = buildJsonObject {
        put("url", a.url)
        a.fallbackUrl?.let { put("fallbackUrl", it) }
        a.portraitUrl?.let { put("portraitUrl", it) }
        a.title?.let { put("title", it) }
        a.artist?.let { put("artist", it) }
        a.album?.let { put("album", it) }
        put("source", a.source.name)
    }.toString()

    private fun decode(value: String): CanvasArtwork? = runCatching {
        val o = json.parseToJsonElement(value) as? JsonObject ?: return null
        fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
        CanvasArtwork(
            url = text("url") ?: return null,
            fallbackUrl = text("fallbackUrl"),
            title = text("title"),
            artist = text("artist"),
            album = text("album"),
            source = appleOrigin(text("source")?.let { name -> CanvasSource.entries.firstOrNull { it.name == name } } ?: CanvasSource.OTHER, text("url")),
            portraitUrl = text("portraitUrl"),
        )
    }.getOrNull()

    private fun save() {
        val f = file ?: return
        val p = props ?: return
        val tmp = File(f.path + ".part")
        runCatching {
            tmp.outputStream().use { p.store(it, "BitChord fork: canvas lookups (ForkCanvasStore)") }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) error("could not replace $f")
            }
        }.onFailure {
            tmp.delete()
            Log.w(TAG, "write failed: ${it.message}")
        }
    }
}

/**
 * v1.10 tags Apple's clips [CanvasSource.APPLE] (its player and the lyrics share treat them as
 * Apple's); one kept from before that, or handed over as [CanvasSource.OTHER] (the PC's clips), is
 * read back as Apple's when its URL is on Apple's hosts.
 */
fun appleOrigin(source: CanvasSource, url: String?): CanvasSource {
    if (source != CanvasSource.OTHER || url == null) return source
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return source
    val apple = host == "apple.com" || host.endsWith(".apple.com") || host == "mzstatic.com" || host.endsWith(".mzstatic.com")
    return if (apple) CanvasSource.APPLE else source
}
