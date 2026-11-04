package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.ForkAppleArtistArt
import com.music.bitchord.data.Http
import com.music.bitchord.data.navidrome.NavidromeArtistInfo
import com.music.bitchord.ui.graphics.argbPixels
import com.music.bitchord.ui.graphics.imageBitmapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength
import org.jetbrains.skia.svg.SVGLengthUnit
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fork: the phone's "Artist signatures" on the PC: a handwritten signature from the listener's
 * own artist-signature API (https://github.com/itsmeadarsh2008/artist-signature), drawn on the
 * artist page with "Signed by …" under it.
 *
 * Behaviour, wording and defaults are the phone's (`app/…/data/canvas/ArtistSignatures.kt`,
 * `SignatureInk.kt`, `ui/screens/DetailScreen.kt` `ArtistHeader` / `AboutSignature`,
 * `SettingsSheet`'s two rows, `SignatureApiEditor`): off by default, no hosted default address,
 * `GET <base>/v1/signatures?artist=<name>`, the best signature picked by licence, then SVG, then
 * verification, then a panoramic bonus; saved only once `GET <base>/v1/health` answers 2xx. In the
 * header in place of the name when Apple Music has no title logo, else at the foot of the About
 * section.
 *
 * Both PC forks have a `DesktopArtistSignatures.kt` (identical: PC-Continued 927428b0, Desktop
 * Revived fd030227). Used only as an implementation aid: the idea of decoding the asset with
 * Skia rather than Coil, and kotlinx JSON for the answer. Not taken: their `{artist}` URL
 * templates, `type=handwritten`, SVGs swapped for Wikimedia PNG thumbnails, JPEGs refused (the
 * phone keeps all three behaviours the other way round; SVGs are drawn here with Skia's SVGDOM).
 */
internal data class ForkArtistSignature(
    val imageUrl: String,
    /** Width over height, for sizing before the image has decoded. */
    val aspect: Float,
    /** Lowercased asset format (`svg`, `png`, …); "" when unknown. */
    val format: String = "",
) {
    val isSvg: Boolean get() = format == "svg"
}

internal object ForkArtistSignatures {

    private const val KEY_ENABLED = "signature_api_enabled"
    private const val KEY_URL = "signature_api_url"
    private const val CACHE_SIZE = 64
    const val MAX_BASE_URL_LENGTH = 500

    /** Phone's `SVG_DECODE_WIDTH`: vectors are drawn large so they stay crisp laid out large. */
    const val SVG_RENDER_WIDTH = 1600

    /** Wikimedia (which serves most signature art) 403s clients that don't say who they are. */
    private const val USER_AGENT = "BitChord/desktop (+https://github.com/kushagrasinghx/BitChord)"

    private val persistence by lazy { DesktopPersistence() }

    // Phone: off, and blank (unconfigured, lookups skipped; there is no hosted default).
    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _url = MutableStateFlow(normalizeBaseUrl(persistence.string(KEY_URL, "")))
    val url: StateFlow<String> = _url.asStateFlow()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        persistence.saveBoolean(KEY_ENABLED, value)
    }

    fun setUrl(value: String) {
        val normalized = normalizeBaseUrl(value)
        _url.value = normalized
        persistence.saveString(KEY_URL, normalized)
        clear()
    }

    /** Trimmed base without trailing slash, or "" when blank. */
    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/').take(MAX_BASE_URL_LENGTH)

    /** Only http(s) URLs are ever requested. Blank never is. */
    fun isValidBaseUrl(baseUrl: String): Boolean {
        val base = normalizeBaseUrl(baseUrl)
        if (base.isEmpty()) return false
        return base.startsWith("http://") || base.startsWith("https://")
    }

    /** Effective base, or null when the provider is off/unconfigured. */
    fun effectiveBaseUrl(enabled: Boolean, baseUrl: String): String? {
        if (!enabled) return null
        return normalizeBaseUrl(baseUrl).takeIf(::isValidBaseUrl)
    }

    private class Entry(val signature: ForkArtistSignature?)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }

    private val _updates = MutableStateFlow(0)

    /** Ticks whenever a lookup settles or the cache is cleared, so pages re-read [cached]. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    fun cached(artistName: String, baseUrl: String?): ForkArtistSignature? {
        if (baseUrl.isNullOrEmpty()) return null
        val key = cacheKey(artistName, baseUrl) ?: return null
        return synchronized(cache) { cache[key] }?.signature
    }

    /** The best signature for [artistName], or null. Never throws; network failures aren't kept. */
    suspend fun signatureFor(artistName: String, baseUrl: String?): ForkArtistSignature? {
        if (baseUrl.isNullOrEmpty()) return null
        val key = cacheKey(artistName, baseUrl) ?: return null
        synchronized(cache) { cache[key] }?.let { return it.signature }
        val entry = withContext(Dispatchers.IO) {
            runCatching { lookup(artistName, baseUrl) }
                .onFailure { DesktopTrackLog.log("Artist signature: lookup failed for \"$artistName\" ($it)") }
                .getOrNull()
        } ?: return null
        DesktopTrackLog.log("Artist signature: \"$artistName\" -> ${entry.signature?.imageUrl ?: "nothing"}")
        synchronized(cache) { cache[key] = entry }
        _updates.value++
        return entry.signature
    }

    /** Forgets everything, e.g. after the base URL changes. */
    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(images) { images.clear() }
        _updates.value++
    }

    /** `true` when `GET base/v1/health` answers 2xx. Never throws. */
    suspend fun checkHealth(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalizeBaseUrl(baseUrl)
            if (!isValidBaseUrl(base)) return@runCatching false
            Http.client.newCall(request("$base/v1/health")).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun cacheKey(artistName: String, baseUrl: String): String? {
        val name = artistName.normalizedName()
        if (name.isEmpty()) return null
        return "${normalizeBaseUrl(baseUrl)}\n$name"
    }

    private fun lookup(artistName: String, baseUrl: String): Entry? {
        val base = normalizeBaseUrl(baseUrl)
        if (!isValidBaseUrl(base)) return Entry(null)
        val url = "$base/v1/signatures?artist=${URLEncoder.encode(artistName, "UTF-8")}"
        val body = Http.client.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        } ?: return null
        return Entry(parse(body))
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The phone's `ArtistSignatureRepository.parse`, in kotlinx JSON: `asset` as an object with
     * `url` or as a plain string, legacy top-level `url` / `original_url`; a `matches[]` answer
     * (ambiguous name) is null. Several signatures: licence, then format, then verification,
     * then the panoramic bonus; resolution only breaks ties.
     */
    fun parse(body: String): ForkArtistSignature? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (root["signatures"] == null && root["matches"] != null) return null
        val signatures = root["signatures"] as? JsonArray ?: return null
        var best: ForkArtistSignature? = null
        var bestScore = Int.MIN_VALUE
        var bestArea = -1L
        for (element in signatures) {
            val sig = element as? JsonObject ?: continue
            val url = extractUrl(sig) ?: continue
            val score = rank(sig, url)
            val area = extractArea(sig)
            if (best == null || score > bestScore || (score == bestScore && area > bestArea)) {
                bestScore = score
                bestArea = area
                best = ForkArtistSignature(url, extractAspect(sig), extractFormat(sig, url))
            }
        }
        return best
    }

    private fun extractFormat(sig: JsonObject, url: String): String {
        sig.obj("asset")?.text("format")?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }?.let { return it }
        sig.text("format")?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }?.let { return it }
        return url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    private fun rank(sig: JsonObject, url: String): Int {
        val license = when (sig.obj("license")?.text("status").orEmpty().lowercase(Locale.ROOT)) {
            "known" -> 2
            "requires_review" -> 1
            else -> 0
        }
        val formatScore = when (extractFormat(sig, url)) {
            "svg" -> 2
            "png" -> 1
            else -> 0
        }
        val verification = when (sig.text("verification").orEmpty().lowercase(Locale.ROOT)) {
            "artist_verified" -> 3
            "verified" -> 2
            "unverified" -> 1
            else -> 0
        }
        val panoramic = if (extractAspect(sig) >= 1.4f) 1 else 0
        return license * 1000 + formatScore * 100 + verification * 10 + panoramic
    }

    private fun extractUrl(sig: JsonObject): String? {
        sig.obj("asset")?.text("url")?.takeIf { it.startsWith("http") }?.let { return it }
        (sig["asset"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }?.let { return it }
        for (key in listOf("url", "original_url", "originalUrl")) {
            sig.text(key)?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return null
    }

    private fun size(sig: JsonObject): Pair<Int, Int>? {
        val asset = sig.obj("asset")
        val w = sig.int("width")?.takeIf { it > 0 } ?: asset?.int("width")?.takeIf { it > 0 } ?: return null
        val h = sig.int("height")?.takeIf { it > 0 } ?: asset?.int("height")?.takeIf { it > 0 } ?: return null
        return w to h
    }

    private fun extractAspect(sig: JsonObject): Float = size(sig)?.let { (w, h) -> w.toFloat() / h } ?: 2.5f

    private fun extractArea(sig: JsonObject): Long = size(sig)?.let { (w, h) -> w.toLong() * h } ?: 0L

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /** Same folding as Apple's lookup: case, accents and punctuation don't identify. */
    internal fun String.normalizedName(): String =
        Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }

    // ── The picture ─────────────────────────────────────────────────────────

    private val images = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 16
    }

    /**
     * The signature drawn: an SVG rendered by Skia at [SVG_RENDER_WIDTH] (the phone's Coil SVG
     * decoder at 1600 px; tinted white where it is drawn), a raster through [inkPixels] (the
     * phone's `SignatureInkTransformation`). Null on any failure.
     */
    suspend fun imageFor(signature: ForkArtistSignature): ImageBitmap? {
        synchronized(images) { images[signature.imageUrl] }?.let { return it }
        val image = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Http.client.newCall(request(signature.imageUrl)).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.bytes()
                } ?: return@runCatching null
                if (signature.isSvg || bytes.looksLikeSvg()) renderSvg(bytes, signature.aspect) else inkRaster(bytes)
            }.onFailure { DesktopTrackLog.log("Artist signature: the image did not draw ($it)") }.getOrNull()
        } ?: return null
        synchronized(images) { images[signature.imageUrl] = image }
        return image
    }

    private fun ByteArray.looksLikeSvg(): Boolean =
        String(this, 0, minOf(size, 512), Charsets.UTF_8).let { it.contains("<svg", ignoreCase = true) }

    private fun renderSvg(bytes: ByteArray, fallbackAspect: Float): ImageBitmap? {
        val dom = SVGDOM(Data.makeFromBytes(bytes))
        val root = dom.root ?: return null
        val box = root.viewBox?.takeIf { it.width > 0f && it.height > 0f }
        val outW = SVG_RENDER_WIDTH
        if (box != null) {
            // The viewBox is the drawing; its width/height (often in mm or pt on Wikimedia) only
            // say how big to show it. Filling the container maps the viewBox onto our pixels.
            root.width = SVGLength(100f, SVGLengthUnit.PERCENTAGE)
            root.height = SVGLength(100f, SVGLengthUnit.PERCENTAGE)
            val outH = (outW * box.height / box.width).roundToInt().coerceIn(1, outW * 4)
            dom.setContainerSize(outW.toFloat(), outH.toFloat())
            val surface = Surface.makeRasterN32Premul(outW, outH)
            dom.render(surface.canvas)
            return surface.makeImageSnapshot().toComposeImageBitmap()
        }
        // No viewBox: user units, so the declared size is the drawing's own (scaled up to ours).
        val width = root.width.takeIf { it.unit != SVGLengthUnit.PERCENTAGE && it.value > 0f }?.value ?: (100f * fallbackAspect)
        val height = root.height.takeIf { it.unit != SVGLengthUnit.PERCENTAGE && it.value > 0f }?.value ?: 100f
        dom.setContainerSize(width, height)
        val outH = (outW * height / width).roundToInt().coerceIn(1, outW * 4)
        val surface = Surface.makeRasterN32Premul(outW, outH)
        surface.canvas.scale(outW / width, outH / height)
        dom.render(surface.canvas)
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }

    private fun inkRaster(bytes: ByteArray): ImageBitmap {
        val decoded = bytes.decodeToImageBitmap()
        val pixels = decoded.argbPixels()
        return if (inkPixels(pixels)) imageBitmapOf(pixels, decoded.width, decoded.height) else decoded
    }

    /** Luminance above this counts as paper rather than ink (0..1). */
    private const val PAPER_LUMINANCE = 0.92f

    /** Share of paper pixels required before treating the image as a scan. */
    private const val SCAN_WHITE_FRACTION = 0.55f

    /**
     * The phone's `SignatureInkTransformation`, on packed ARGB in place: a scan (dark ink on white
     * paper) becomes white strokes on transparency, alpha from darkness. False, and [pixels]
     * untouched, when fewer than 55% of pixels read as paper (an autograph photograph).
     */
    fun inkPixels(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        val paper = pixels.count { luminance(it) >= PAPER_LUMINANCE }
        if (paper.toFloat() / pixels.size < SCAN_WHITE_FRACTION) return false
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val inAlpha = (pixel ushr 24) / 255f
            if (inAlpha <= 0f) continue
            val darkness = 1f - luminance(pixel)
            if (darkness <= 0f) {
                pixels[i] = 0
                continue
            }
            val outAlpha = (darkness * inAlpha * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (outAlpha shl 24) or 0x00FFFFFF
        }
        return true
    }

    private fun luminance(pixel: Int): Float {
        val r = ((pixel ushr 16) and 0xFF) / 255f
        val g = ((pixel ushr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** Phone: the signature takes the header only when Apple has no title logo; else the About foot. */
    fun inHeader(apple: ForkAppleArtistArt?): Boolean = apple?.logoUrl == null
}

/**
 * The signature for the artist page [key] named [name], or null: off, unconfigured, a miss, or
 * still asking. The phone's bounded retries (0, 5, 15, 30 s): a sleeping self-hosted API takes
 * 30-60 s to wake, and a miss isn't kept.
 */
@Composable
internal fun rememberForkArtistSignature(key: String, name: String): ForkArtistSignature? {
    val enabled by ForkArtistSignatures.enabled.collectAsState()
    val baseUrl by ForkArtistSignatures.url.collectAsState()
    val updates by ForkArtistSignatures.updates.collectAsState()
    val base = ForkArtistSignatures.effectiveBaseUrl(enabled, baseUrl)
    val signature = androidx.compose.runtime.key(key) {
        produceState(ForkArtistSignatures.cached(name, base), name, base, updates) {
            if (base == null) {
                value = null
                return@produceState
            }
            for (wait in listOf(0L, 5_000L, 15_000L, 30_000L)) {
                if (wait > 0) delay(wait)
                value = ForkArtistSignatures.cached(name, base)
                    ?: ForkArtistSignatures.signatureFor(name, base)
                    ?: value
                if (value != null) return@produceState
            }
        }.value
    }
    return if (base == null) null else signature
}

/** The decoded signature, with the phone's two retries 800 ms apart for a dropped fetch. */
@Composable
private fun rememberForkSignatureImage(signature: ForkArtistSignature?): ImageBitmap? =
    produceState<ImageBitmap?>(null, signature?.imageUrl) {
        val sig = signature ?: return@produceState
        repeat(3) { attempt ->
            if (attempt > 0) delay(800L)
            ForkArtistSignatures.imageFor(sig)?.let {
                value = it
                return@produceState
            }
        }
    }.value

/** "Signed by **name**", the phone's `SignedByCaption`. */
@Composable
private fun ForkSignedByCaption(name: String, modifier: Modifier = Modifier) {
    val signed = DesktopStrings.format("signed_by", name, fallback = "Signed by %1\$s")
    val nameAt = signed.indexOf(name)
    Text(
        text = buildAnnotatedString {
            append(signed)
            if (nameAt >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), nameAt, nameAt + name.length)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.8f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun ForkSignatureImage(signature: ForkArtistSignature, image: ImageBitmap, name: String, modifier: Modifier) {
    Image(
        BitmapPainter(image),
        contentDescription = name,
        contentScale = ContentScale.Fit,
        // Vectors are dark ink on transparency: tinted white over the photo, as the phone's. Scans
        // already went white in the ink pass and keep their pixels.
        colorFilter = if (signature.isSvg) ColorFilter.tint(Color.White) else null,
        modifier = modifier,
    )
}

/**
 * Inside an artist banner: the signature with its caption in place of the name, once it has
 * drawn (the name stands in until then, and on any miss). [fallback] is the banner's own name.
 * Sized as the phone's: 0.7 of a title logo's room (the PC's logo room: 60% of the width, 30%
 * of the banner's height).
 */
@Composable
internal fun BoxScope.ForkSignedName(
    signature: ForkArtistSignature?,
    name: String,
    bannerHeight: Dp,
    fallback: @Composable BoxScope.() -> Unit,
) {
    val image = rememberForkSignatureImage(signature)
    if (signature == null || image == null) {
        fallback()
        return
    }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val bannerWidth = maxWidth
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = DesktopPageGutter, end = DesktopPageGutter, bottom = 18.dp),
        ) {
            ForkSignatureImage(
                signature,
                image,
                name,
                Modifier
                    .padding(bottom = 6.dp)
                    .widthIn(max = bannerWidth * (0.6f * SIGNATURE_SCALE))
                    .heightIn(max = bannerHeight * (0.3f * SIGNATURE_SCALE))
                    .aspectRatio(signatureAspect(signature, image)),
            )
            ForkSignedByCaption(name)
        }
    }
}

private const val SIGNATURE_SCALE = 0.7f

private fun signatureAspect(signature: ForkArtistSignature, image: ImageBitmap): Float =
    (if (image.height > 0) image.width.toFloat() / image.height else signature.aspect).coerceIn(0.4f, 6f)

/**
 * The signature closing an About section: 56 dp tall, caption under it (phone `AboutSignature`).
 * [gutter] 0 on the collection page, whose list has its own.
 */
@Composable
private fun ForkAboutSignature(signature: ForkArtistSignature, name: String, gutter: Dp = DesktopPageGutter) {
    val image = rememberForkSignatureImage(signature) ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        ForkSignatureImage(
            signature,
            image,
            name,
            Modifier.padding(top = 20.dp, bottom = 6.dp).height(56.dp).aspectRatio(signatureAspect(signature, image)),
        )
        ForkSignedByCaption(name)
    }
}

/**
 * On the YouTube artist page, after its About item: the signature at the About foot when Apple's
 * title logo has the header. With no blurb the phone still opens an About section for it, so the
 * heading comes along then.
 */
internal fun LazyListScope.forkAboutSignature(
    signature: ForkArtistSignature?,
    apple: ForkAppleArtistArt?,
    name: String,
    description: String?,
) {
    if (signature == null || ForkArtistSignatures.inHeader(apple)) return
    item(key = "fork-about-signature") {
        Column(Modifier.padding(top = if (description.isNullOrBlank()) 16.dp else 0.dp)) {
            if (description.isNullOrBlank()) {
                Text(
                    DesktopStrings["d_about_artist", "About artist"],
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = DesktopPageGutter),
                )
            }
            ForkAboutSignature(signature, name)
        }
    }
}

/**
 * A Navidrome artist page (a collection page) has no About: the phone's Navidrome artist page
 * (its `DetailScreen`) opens one at the foot for the signature, so this does too, after the
 * similar artists. Not when Apple's banner (no title logo) already carries it.
 */
internal fun LazyListScope.forkNavidromeArtistSignature(browseId: String, songs: List<com.music.bitchord.data.model.Song>) {
    val name = NavidromeArtistInfo.nameOf(browseId) ?: return
    item(key = "fork-navidrome-signature") {
        val signature = rememberForkArtistSignature(browseId, name) ?: return@item
        val apple = rememberForkAppleArt(browseId, name, songs.size) { songs.map { it.title } }
        if (apple != null && ForkArtistSignatures.inHeader(apple)) return@item
        if (rememberForkSignatureImage(signature) == null) return@item
        Column(Modifier.padding(top = 32.dp)) {
            Text(DesktopStrings["d_about_artist", "About artist"], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ForkAboutSignature(signature, name, gutter = 0.dp)
        }
    }
}

// ── Settings ────────────────────────────────────────────────────────────────

/**
 * The phone's two rows (Settings, after the animated-cover rows): the "Artist signatures"
 * switch, and the "Signature API URL" row (the address, or "Not configured…"), greyed and closed
 * while the switch is off ([ForkDisabledWhen]; never hidden). Its editor opens in place, as the
 * PaxSenix key's does: Test, Save (only once `/v1/health` answers), Remove (when one is stored).
 */
@Composable
internal fun ForkArtistSignatureRows() {
    val enabled by ForkArtistSignatures.enabled.collectAsState()
    val stored by ForkArtistSignatures.url.collectAsState()
    SettingsToggle(
        DesktopStrings["artist_signatures", "Artist signatures"],
        DesktopStrings["artist_signatures_subtitle", "Shows a handwritten signature when Apple Music has no title logo"],
        enabled,
        ForkArtistSignatures::setEnabled,
    )
    var editing by remember { mutableStateOf(false) }
    // A draft of the stored address, seeded when the editor opens.
    var input by remember(editing) { mutableStateOf(stored) }
    var status by remember(editing) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val connected = DesktopStrings["connected", "Connected"]
    val failed = DesktopStrings["artist_signatures_test_failed", "Could not reach the API at that URL"]
    val test: (onGood: () -> Unit) -> Unit = { onGood ->
        val normalized = ForkArtistSignatures.normalizeBaseUrl(input)
        if (!ForkArtistSignatures.isValidBaseUrl(normalized)) {
            status = failed to false
        } else {
            busy = true
            status = null
            scope.launch {
                val ok = ForkArtistSignatures.checkHealth(normalized)
                busy = false
                status = (if (ok) connected else failed) to ok
                if (ok) onGood()
            }
        }
    }
    // Now always drawn, so it answers the settings search like the switch above it.
    if (settingsRowVisible(DesktopStrings["artist_signatures_url", "Signature API URL"], stored)) ForkDisabledWhen(!enabled) { Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().desktopRowClickable { editing = !editing }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(DesktopStrings["artist_signatures_url", "Signature API URL"], style = MaterialTheme.typography.bodyLarge)
                Text(
                    stored.ifBlank { DesktopStrings["artist_signatures_unconfigured", "Not configured — tap to set the API URL"] },
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (editing && enabled) {
            OutlinedTextField(
                input,
                {
                    // A result describes the address it was run against.
                    input = it
                    status = null
                },
                Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text(DesktopStrings["artist_signatures_url_hint", "https://signatures.example.com"]) },
                singleLine = true,
            )
            status?.let { (text, good) ->
                Text(
                    text,
                    color = if (good) Color(0xFF34C759) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (stored.isNotBlank()) {
                    TextButton(onClick = {
                        ForkArtistSignatures.setUrl("")
                        editing = false
                    }) { Text(DesktopStrings["remove", "Remove"], color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editing = false }) {
                    Text(DesktopStrings["cancel", "Cancel"], color = DesktopSecondary)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(enabled = !busy && input.isNotBlank(), onClick = { test {} }) {
                    Text(if (busy) DesktopStrings["testing", "Testing…"] else DesktopStrings["test", "Test"], color = DesktopSecondary)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(enabled = !busy && input.isNotBlank(), onClick = {
                    test {
                        ForkArtistSignatures.setUrl(input)
                        editing = false
                    }
                }) { Text(DesktopStrings["save", "Save"], color = DesktopAccent) }
            }
        }
    } }
}
