package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.Http
import com.music.bitchord.data.lyrics.artistForLyricsSearch
import com.music.bitchord.data.lyrics.forLyricsSearch
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.ui.icons.BitChordIcons
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Fork: what Genius says a song means, on the PC, off by default ("Genius song meanings" in
 * Settings > Lyrics sources). From PC-Continued: the "about this track" paragraph (e16ed1f8,
 * "Genius about-track", their `DesktopGenius.about` shown as a stage in their Audio Pipeline
 * panel) and the per-line annotations (18b6b3ae, "lyric-line meanings", `DesktopGenius.lineMeaning`
 * and `DesktopLyricMeaningDialog`, opened by right-clicking a lyric line). Their matching rules
 * are kept: artist-and-title search on `genius.com/api/search/multi`, an exact title outranking
 * the top hit, and for a line the smallest annotated fragment holding it, else the longest one it
 * holds. Their scraper work for the lyrics themselves (6cfbed4e, fcd4b381) is already the shared
 * `Genius` lyrics source, so nothing of it is taken again here.
 *
 * Fitted to ours rather than copied:
 * - Where it shows. The PC draws the phone's player (`sharedUi`), which has no right-click on a
 *   lyric line and no pipeline stage to hang a paragraph in, and the phone is not changed for a PC
 *   feature. So the paragraph is a section of the PC's Song info dialog ([ForkAboutSection]), and a
 *   line's meaning is in the lyrics share sheet the PC already opens for picked lines
 *   ([ForkLineMeaningSection]): long-press a line (or the share glyph), pick it, and the sheet says
 *   what Genius wrote over it.
 * - Genius's own JSON instead of the song page's HTML. Measured 2026-11-04 with a plain
 *   User-Agent: `api/songs/<id>?text_format=plain` answers the description as plain text (`"?"`
 *   when there is none), and `api/referents?song_id=<id>&text_format=plain` every annotated
 *   fragment with its annotation in one call (50 a page), where PC-Continued parsed ~500 KB of the
 *   page's HTML and then asked `api/annotations/<id>` once per line. A page redesign no longer
 *   breaks it, only an API change would.
 * - The artist must match. Their search took the top hit when no title matched exactly, which for
 *   a song Genius lacks is often a translation page ("Genius Deutsche Übersetzungen") or a cover;
 *   a wrong song's meaning is worse than none.
 *
 * It scrapes a site that does not offer this as an API, so it is built to fail quietly: off unless
 * switched on, nothing asked offline or for a station, one request at a time ([lock]), no retries,
 * a failure remembered for [FAILURE_MS] so reopening the dialog doesn't ask again at once, and
 * every answer (a miss too) cached for the session. Everything runs on the IO dispatcher from the
 * dialog's own effect; the player never waits on it.
 */
internal object ForkGeniusMeanings {

    internal const val KEY_ENABLED = "fork_genius_meanings"

    /** What Genius wrote about a song, and its page. */
    data class About(val text: String, val url: String)

    /** One annotation: the annotated words, what was written over them, and its page. */
    data class Meaning(val fragment: String, val text: String, val url: String)

    /** A search hit taken to be the song. */
    internal data class Hit(val id: Long, val url: String)

    private val _enabled = MutableStateFlow(runCatching { DesktopPersistence().boolean(KEY_ENABLED, false) }.getOrDefault(false))
    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(on: Boolean) {
        DesktopPersistence().saveBoolean(KEY_ENABLED, on)
        _enabled.value = on
    }

    /**
     * Whether [song] may be asked about now: switched on, not in offline mode, a song rather than a
     * station. Offline mode as switched, not [ForkOffline.offline]: that also reads true while the
     * Navidrome server alone is unreachable, which says nothing about genius.com.
     */
    fun wanted(song: Song): Boolean =
        _enabled.value && !ForkOffline.switched.value && !NavidromeRadio.isRadio(song.videoId) &&
            song.title.isNotBlank() && song.artist.isNotBlank()

    /** The song's description, or null (none, off, or any failure). Never throws. */
    suspend fun about(song: Song): About? {
        if (!wanted(song)) return null
        val hit = hit(song) ?: return null
        return abouts.answer("${hit.id}") { get(songUrl(hit.id))?.let { aboutFrom(it, hit.url) } }
    }

    /**
     * What Genius wrote over [lines] (the picked lyric lines, in order): the passage as a whole
     * first, as an annotation often covers several lines, then each line in turn.
     */
    suspend fun meaning(song: Song, lines: List<String>): Meaning? {
        if (!wanted(song)) return null
        val asked = lines.map(String::trim).filter(String::isNotEmpty)
        if (asked.isEmpty()) return null
        val hit = hit(song) ?: return null
        val referents = referents.answer("${hit.id}") { fetchReferents(hit.id) } ?: return null
        val tries = (listOf(asked.joinToString("\n")) + asked).distinct()
        return tries.firstNotNullOfOrNull { bestReferent(referents, it) }
    }

    private suspend fun hit(song: Song): Hit? {
        val title = song.title.forLyricsSearch()
        val artist = song.artist.artistForLyricsSearch()
        return hits.answer("${canonical(artist)}|${canonical(title)}") {
            get(searchUrl("$artist $title"))?.let { pickHit(it, title, artist) }
        }
    }

    private fun fetchReferents(id: Long): List<Meaning>? {
        val out = mutableListOf<Meaning>()
        for (page in 1..MAX_REFERENT_PAGES) {
            val body = get(referentsUrl(id, page)) ?: break
            val found = referentsFrom(body)
            out += found.meanings
            if (found.count < REFERENTS_PER_PAGE) break
        }
        return out.takeIf { it.isNotEmpty() }
    }

    // ---- Parsing (tested against saved answers in ForkGeniusMeaningsTest) ----------------------

    /**
     * The `search/multi` hit that is this song: in the "song" section, by an artist matching
     * [artist] (either name holding the other), an exact title first, else one whose title holds
     * [title] or is held by it. Null when no hit is by the artist.
     */
    internal fun pickHit(body: String, title: String, artist: String): Hit? {
        val sections = runCatching {
            json.parseToJsonElement(body).obj("response")?.arr("sections")
        }.getOrNull() ?: return null
        val candidates = sections.mapNotNull { it as? JsonObject }
            .filter { it.text("type") == "song" }
            .flatMap { section -> section.arr("hits").orEmpty().mapNotNull { (it as? JsonObject)?.obj("result") } }
        val wantTitle = canonical(title)
        val wantArtist = canonical(artist)
        if (wantTitle.isEmpty() || wantArtist.isEmpty()) return null
        val byArtist = candidates.filter { result ->
            val names = canonical(result.text("primary_artist_names") ?: result.text("artist_names").orEmpty())
            names.isNotEmpty() && (names.contains(wantArtist) || wantArtist.contains(names))
        }
        val best = byArtist.firstOrNull { canonical(it.text("title").orEmpty()) == wantTitle }
            ?: byArtist.firstOrNull {
                val got = canonical(it.text("title").orEmpty())
                got.length >= 3 && (got.contains(wantTitle) || wantTitle.contains(got))
            }
            ?: return null
        val id = (best["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val url = best.text("url")?.takeIf { it.startsWith("https://") } ?: return null
        return Hit(id, url)
    }

    /** `api/songs/<id>?text_format=plain`'s description, cut to [MAX_ABOUT_CHARS]; `"?"` is none. */
    internal fun aboutFrom(body: String, fallbackUrl: String): About? {
        val song = runCatching { json.parseToJsonElement(body).obj("response")?.obj("song") }.getOrNull() ?: return null
        val text = song.obj("description")?.text("plain")?.let(::tidy)?.takeIf { it.isNotEmpty() && it != "?" } ?: return null
        return About(clip(text, MAX_ABOUT_CHARS), song.text("url") ?: fallbackUrl)
    }

    /** One page of `api/referents`: how many it held, and the annotated lyric fragments in it. */
    internal class ReferentPage(val count: Int, val meanings: List<Meaning>)

    /**
     * One page of `api/referents?text_format=plain`. The song's description is a referent too
     * (`is_description`), left out: it annotates no line.
     */
    internal fun referentsFrom(body: String): ReferentPage {
        val list = runCatching { json.parseToJsonElement(body).obj("response")?.arr("referents") }.getOrNull()
            ?: return ReferentPage(0, emptyList())
        val meanings = list.mapNotNull { element ->
            val referent = element as? JsonObject ?: return@mapNotNull null
            if ((referent["is_description"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val fragment = referent.text("fragment")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val annotation = referent.arr("annotations")?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            val text = annotation.obj("body")?.text("plain")?.let(::tidy)?.takeIf { it.isNotEmpty() && it != "?" }
                ?: return@mapNotNull null
            Meaning(fragment, clip(text, MAX_MEANING_CHARS), referent.text("url") ?: annotation.text("url").orEmpty())
        }
        return ReferentPage(list.size, meanings)
    }

    /**
     * The annotated fragment [line] falls inside (PC-Continued 18b6b3ae's rule): an equal one, else
     * the smallest that holds it, else the longest it holds. Compared without case, spaces or
     * punctuation, as a lyric service and Genius split and punctuate the same words differently.
     */
    internal fun bestReferent(referents: List<Meaning>, line: String): Meaning? {
        val want = canonical(line)
        if (want.length < 3) return null
        referents.firstOrNull { canonical(it.fragment) == want }?.let { return it }
        referents.filter { canonical(it.fragment).contains(want) }
            .minByOrNull { canonical(it.fragment).length }
            ?.let { return it }
        return referents.filter { canonical(it.fragment).length >= 3 && want.contains(canonical(it.fragment)) }
            .maxByOrNull { canonical(it.fragment).length }
    }

    // ---- Network and cache ------------------------------------------------------------------

    /**
     * Answers by key, a miss as well as a find, for the session ([MAX_CACHED] each, oldest out).
     * A failure (no answer at all: timeout, 403, a changed shape that threw) is kept [FAILURE_MS],
     * then the key may be asked again; that is the only "retry" there is.
     */
    private class Answers<V : Any> {
        private class Entry<V>(val value: V?, val failedAt: Long?)

        private val map = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>) = size > MAX_CACHED
        }

        suspend fun answer(key: String, fetch: () -> V?): V? = lock.withLock {
            map[key]?.let { entry ->
                val failedAt = entry.failedAt
                if (failedAt == null || System.currentTimeMillis() - failedAt < FAILURE_MS) return@withLock entry.value
            }
            val result = withContext(Dispatchers.IO) { runCatching(fetch) }
            result.onFailure { DesktopTrackLog.log("Genius meanings: ${it.javaClass.simpleName}") }
            map[key] = Entry(result.getOrNull(), if (result.isFailure) System.currentTimeMillis() else null)
            result.getOrNull()
        }
    }

    /** One Genius request at a time across all three caches: a burst is what Cloudflare blocks. */
    private val lock = Mutex()
    private val hits = Answers<Hit>()
    private val abouts = Answers<About>()
    private val referents = Answers<List<Meaning>>()

    private fun searchUrl(query: String) =
        "https://genius.com/api/search/multi".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString()

    private fun songUrl(id: Long) = "https://genius.com/api/songs/$id?text_format=plain"

    private fun referentsUrl(id: Long, page: Int) =
        "https://genius.com/api/referents?song_id=$id&text_format=plain&per_page=$REFERENTS_PER_PAGE&page=$page"

    /**
     * A plain User-Agent, as the shared `Genius` lyrics source sends: Cloudflare answers it and
     * challenges one claiming to be a browser (measured there, September 2026). A non-2xx answer
     * throws, so it is remembered as a failure rather than as "Genius has nothing".
     */
    private fun get(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "BitChord")
            .header("Accept", "application/json")
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body?.string()
        }
    }

    private val http by lazy {
        Http.client.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val MAX_CACHED = 128
    private const val FAILURE_MS = 10L * 60_000L
    private const val REFERENTS_PER_PAGE = 50
    private const val MAX_REFERENT_PAGES = 3

    /** PC-Continued's 900: a whole description, short enough that the dialog stays a dialog. */
    private const val MAX_ABOUT_CHARS = 900
    private const val MAX_MEANING_CHARS = 1_200

    private fun tidy(raw: String): String = raw.replace(' ', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max).substringBeforeLast(' ').trimEnd('.', ',', ' ') + "…"

    private fun canonical(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** The switch, in Settings > Lyrics sources under the PaxSenix key row. */
@Composable
internal fun ForkGeniusMeaningsRow() {
    val on by ForkGeniusMeanings.enabled.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .desktopRowClickable { ForkGeniusMeanings.setEnabled(!on) }
            .padding(horizontal = panelInset(18.dp), vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                DesktopStrings["fork_genius_meanings", "Genius song meanings"],
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Text(
                DesktopStrings[
                    "fork_genius_meanings_subtitle",
                    "What Genius says a song and its lines mean, in Song info and the lyrics share sheet. Read from genius.com's pages, so it can stop working",
                ],
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (on) {
            Icon(BitChordIcons.Check, DesktopStrings["enabled", "Enabled"], tint = DesktopAccent, modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.size(18.dp))
        }
    }
}

/** Song info's "About this song" section; nothing at all while the switch is off. */
@Composable
internal fun ForkAboutSection(song: Song) {
    val on by ForkGeniusMeanings.enabled.collectAsState()
    if (!on || !ForkGeniusMeanings.wanted(song)) return
    val state by produceState<Pair<Boolean, ForkGeniusMeanings.About?>>(false to null, song.videoId, song.title, song.artist) {
        value = true to ForkGeniusMeanings.about(song)
    }
    val (answered, about) = state
    DesktopCardRule()
    ForkGeniusBlock(
        heading = DesktopStrings["fork_about_song", "About this song"],
        answered = answered,
        text = about?.text,
        missing = DesktopStrings["fork_about_song_missing", "Genius has nothing written about this song."],
        url = about?.url,
        horizontal = 22.dp,
    )
}

/** The share sheet's "Meaning" section for the picked lines; nothing while the switch is off. */
@Composable
internal fun ForkLineMeaningSection(song: Song, lines: List<String>) {
    val on by ForkGeniusMeanings.enabled.collectAsState()
    if (!on || !ForkGeniusMeanings.wanted(song) || lines.none { it.isNotBlank() }) return
    val state by produceState<Pair<Boolean, ForkGeniusMeanings.Meaning?>>(false to null, song.videoId, lines) {
        value = true to ForkGeniusMeanings.meaning(song, lines)
    }
    val (answered, meaning) = state
    ForkGeniusBlock(
        heading = DesktopStrings["fork_line_meaning", "Meaning, annotated on Genius"],
        answered = answered,
        text = meaning?.text,
        missing = DesktopStrings["fork_line_meaning_missing", "Genius has no annotation for these lines."],
        url = meaning?.url,
        horizontal = 4.dp,
    )
}

@Composable
private fun ForkGeniusBlock(
    heading: String,
    answered: Boolean,
    text: String?,
    missing: String,
    url: String?,
    horizontal: androidx.compose.ui.unit.Dp,
) {
    Column(Modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, top = 12.dp, bottom = 4.dp)) {
        Text(heading, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
        when {
            !answered -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), color = DesktopSecondary, strokeWidth = 1.75.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    DesktopStrings["fork_genius_looking", "Asking Genius…"],
                    style = MaterialTheme.typography.bodySmall,
                    color = DesktopSecondary,
                )
            }
            text == null -> Text(
                missing,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
            )
            else -> {
                // Its own scroll, held short: the dialogs around it have fixed rows to keep on screen.
                Column(Modifier.padding(top = 6.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                }
                if (!url.isNullOrBlank()) {
                    TextButton(onClick = { DesktopExternalLinks.open(url) }) {
                        Text(DesktopStrings["fork_open_on_genius", "Open on Genius"], color = DesktopAccent)
                    }
                }
            }
        }
    }
}
