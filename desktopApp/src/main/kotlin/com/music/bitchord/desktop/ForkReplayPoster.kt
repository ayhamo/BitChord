package com.music.bitchord.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bitchord.desktopapp.generated.resources.Res
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.graphics.paletteSwatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.GradientStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import org.jetbrains.skia.svg.SVGDOM
import java.awt.FileDialog
import java.io.File
import java.util.Locale

/**
 * Fork: the phone's Replay share picture (`ReplayPoster.renderReplayPoster`) on the PC, drawn with
 * Skia off-screen the way [ForkLyricsShare] draws the lyrics card.
 *
 * The same 1080 × 1920 picture, band for band: the header ("Replay 26" / "Replay · November 2026",
 * the mark and "BitChord", the holder and "Member since"), then either the whole Replay (minutes,
 * the top five songs and artists side by side, three albums, up to four genre chips) or one story
 * card (its sentence, then the card's own body: the collage, a leaderboard, the genre list, the
 * habits or the recap), and the "Counted on this device with BitChord" footer. The backdrop is the
 * card's cover and hue, as on screen. Same coordinates, sizes and alphas as the phone; text goes
 * through Skia paragraphs, so a long name ellipsises and a headline wraps the way the phone's
 * hand-wrapping does, with font fallback for scripts SF Pro lacks.
 *
 * The sheet ([ForkReplayShareSheet]) is the phone's `ReplayShareSheet`; where the phone has Save
 * (gallery) and Share (Android's sheet), the PC has Save (the system's file dialog in Pictures,
 * `bitchord-replay-<label>.png`) and Copy image (the clipboard), as the lyrics card does.
 */
internal object ForkReplayPoster {

    private const val W = 1080
    private const val H = 1920
    private const val MARGIN = 72f
    private const val CONTENT_W = W - MARGIN * 2
    private const val LOGO_W = 66f
    private const val LOGO_H = 44f
    private const val LOGO_GAP = 20f
    private const val HEADLINE_SIZE = 88f
    private const val HEADLINE_LEADING = 108f
    private const val CARD_ROWS = 5
    private const val COLUMN_GAP = 36f
    private const val ROW_HEIGHT = 116f
    private const val ART = 84f
    private const val ACCENT = 0xFFFA2D48.toInt()
    private const val POSTER_ROWS = 5
    private const val POSTER_ALBUMS = 3
    private const val POSTER_GENRES = 4

    /** The phone's `saveToGallery` name (`bitchord-replay-<label>`), made safe for Windows. */
    fun fileName(label: String): String {
        val safe = label.trim().replace(' ', '-').lowercase(Locale.ROOT)
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F·]"""), "")
            .replace(Regex("-{2,}"), "-")
            .trim('.', '-')
            .take(80)
        return if (safe.isEmpty()) "bitchord-replay.png" else "bitchord-replay-$safe.png"
    }

    // ── Type ────────────────────────────────────────────────────────────

    private const val HEAVY = "BitChord Heavy"
    private const val SEMIBOLD = "BitChord Semibold"
    private const val REGULAR = "BitChord Regular"

    @Volatile
    private var fonts: FontCollection? = null

    /** SF Pro Display from the app's resources (the phone's `Fonts`), the system's as fallback. */
    private suspend fun fonts(): FontCollection {
        fonts?.let { return it }
        val provider = TypefaceFontProvider()
        for ((file, alias) in listOf(
            "sf_pro_display_heavy.otf" to HEAVY,
            "sf_pro_display_semibold.otf" to SEMIBOLD,
            "sf_pro_display_regular.otf" to REGULAR,
        )) {
            runCatching {
                FontMgr.default.makeFromData(Data.makeFromBytes(Res.readBytes("font/$file")))?.let { provider.registerTypeface(it, alias) }
            }
        }
        return FontCollection().apply {
            setAssetFontManager(provider)
            setDefaultFontManager(FontMgr.default)
        }.also { fonts = it }
    }

    /** One of the phone's three paints: `heading` (heavy), `body` (regular or semibold), `label` (tracked semibold). */
    private class Face(val family: String, val size: Float, val color: Int, val tracking: Float = 0f) {
        fun style(): TextStyle = TextStyle().apply {
            fontFamilies = arrayOf(family)
            fontSize = size
            this.color = this@Face.color
            letterSpacing = tracking * size
        }
    }

    private fun heading(size: Float, color: Int) = Face(HEAVY, size, color)
    private fun body(size: Float, color: Int, bold: Boolean = false) = Face(if (bold) SEMIBOLD else REGULAR, size, color)
    private fun label(size: Float, color: Int, tracking: Float = 0.10f) = Face(SEMIBOLD, size, color, tracking)

    private class Ink(val canvas: Canvas, val fonts: FontCollection) {
        fun line(text: String, face: Face, maxWidth: Float = 4_000f): Paragraph =
            ParagraphBuilder(ParagraphStyle().apply { maxLinesCount = 1; ellipsis = "…" }, fonts)
                .pushStyle(face.style()).addText(text).popStyle().build().apply { layout(maxWidth) }

        /** Android's `drawText(text, x, baseline)`, one line, ellipsised to [maxWidth]. */
        fun text(text: String, x: Float, baseline: Float, face: Face, maxWidth: Float = 4_000f, alignRight: Boolean = false) {
            val p = line(text, face, maxWidth)
            val width = minOf(p.maxIntrinsicWidth, maxWidth)
            p.paint(canvas, if (alignRight) x - width else x, baseline - p.alphabeticBaseline)
        }

        fun width(text: String, face: Face): Float = line(text, face).maxIntrinsicWidth
    }

    // ── The picture ─────────────────────────────────────────────────────

    /** The phone's `renderReplayPoster`: the whole Replay when [page] is null, else that story card. */
    suspend fun render(
        summary: DesktopReplaySummary,
        genres: List<DesktopRankedEntry>,
        holder: String,
        memberSince: String?,
        page: ForkStoryPage?,
    ): ForkLyricsCard = coroutineScope {
        val songs = summary.songRows(POSTER_ROWS)
        val artists = summary.artistRows(POSTER_ROWS)
        val albums = summary.albumRows(POSTER_ALBUMS)
        val lit = page?.let { ForkReplayText.artwork(summary, it) } ?: songs.firstOrNull()?.artworkUrl
        val urls = (songs + artists + albums).mapNotNull { it.artworkUrl } + listOfNotNull(lit)
        // Started together, so a cold cache is one round trip rather than a dozen.
        val loaded = urls.filter(String::isNotBlank).distinct()
            .associateWith { url -> async(Dispatchers.IO) { loadBitmap(url) } }
            .mapValues { it.value.await() }
        val covers = loaded.mapValues { (_, bitmap) -> bitmap?.let { runCatching { Image.makeFromBitmap(it.asSkiaBitmap()) }.getOrNull() } }
        val palette = lit?.let { loaded[it] }?.let(::paletteOf) ?: ForkLyricsShare.FALLBACK_PALETTE
        val logo = runCatching { Res.readBytes("drawable/logo_mark.svg") }.getOrNull()
        val fonts = fonts()

        withContext(Dispatchers.Default) {
            val surface = Surface.makeRasterN32Premul(W, H)
            val ink = Ink(surface.canvas, fonts)
            drawBackdrop(ink.canvas, palette, page?.let(ForkReplayText::hue) ?: 0f)
            var y = drawHeader(ink, logo, summary, holder, memberSince)
            if (page == null) {
                y = drawTotals(ink, summary, y)
                y = drawColumns(ink, songs, artists, covers, y)
                y = drawAlbums(ink, albums, covers, y)
                drawGenres(ink, genres, y)
            } else {
                y = drawRuns(ink, ForkReplayText.headline(summary, page), MARGIN, y, CONTENT_W)
                drawCard(ink, summary, genres, page, covers, y + 56f)
            }
            ink.text(
                ForkReplayText.t("counted_on_device_with_bitchord", "Counted on this device with BitChord"),
                MARGIN, H - 64f, label(24f, 0x73FFFFFF, tracking = 0.08f),
            )
            val snapshot = surface.makeImageSnapshot()
            val png = snapshot.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed")
            ForkLyricsCard(snapshot.toComposeImageBitmap(), png)
        }
    }

    private suspend fun loadBitmap(url: String): ImageBitmap? = runCatching {
        DesktopArtworkCache.load(url.artworkAt(CARD_ART_PX) ?: url)
    }.getOrNull()

    private fun paletteOf(bitmap: ImageBitmap): List<Int> {
        val swatches = runCatching { paletteSwatches(bitmap, 24, false).sortedByDescending { it.population }.map { it.rgb } }
            .getOrNull().orEmpty()
        if (swatches.isEmpty()) return ForkLyricsShare.FALLBACK_PALETTE
        return (swatches + ForkLyricsShare.FALLBACK_PALETTE).take(4).map(ForkLyricsShare::tuned)
    }

    // ── One story card ──────────────────────────────────────────────────

    private fun drawCard(ink: Ink, summary: DesktopReplaySummary, genres: List<DesktopRankedEntry>, page: ForkStoryPage, covers: Map<String, Image?>, top: Float) {
        when (page) {
            ForkStoryPage.INTRO, ForkStoryPage.MINUTES -> drawCollage(ink.canvas, summary, covers, top)
            ForkStoryPage.SONGS -> drawLeaderboard(ink, summary.songRows(CARD_ROWS), covers, top, false)
            ForkStoryPage.ARTISTS -> drawLeaderboard(ink, summary.artistRows(CARD_ROWS), covers, top, true)
            ForkStoryPage.ALBUMS -> drawLeaderboard(ink, summary.albumRows(CARD_ROWS), covers, top, false)
            ForkStoryPage.GENRES -> drawBigList(ink, ForkReplayText.genreRows(genres, CARD_ROWS), top)
            ForkStoryPage.HABITS -> drawHabits(ink, summary, top)
            ForkStoryPage.SUMMARY -> drawRecap(ink, summary, genres, top)
        }
    }

    private fun drawLeaderboard(ink: Ink, rows: List<DesktopReplayRow>, covers: Map<String, Image?>, top: Float, circular: Boolean) {
        val lead = rows.firstOrNull() ?: return
        val hero = 348f
        drawArtwork(ink.canvas, cover(covers, lead.artworkUrl), lead.title, MARGIN, top, hero, circular)
        val textX = MARGIN + hero + 48f
        val textWidth = W - MARGIN - textX
        ink.text(lead.title, textX, top + 86f, heading(78f, 0xFFFFFFFF.toInt()), textWidth)
        var y = top + 86f
        lead.subtitle?.let {
            y += 62f
            ink.text(it, textX, y, body(50f, 0xB3FFFFFF.toInt()), textWidth)
        }
        ink.text("${ForkReplayText.listening(lead.ms)} · ${ForkReplayText.plays(lead.plays)}", textX, y + 62f, body(44f, 0x8CFFFFFF.toInt()), textWidth)

        var rowY = top + hero + 90f
        rows.drop(1).forEach { row ->
            ink.text(row.rank.toString(), MARGIN, rowY + 72f, heading(44f, 0x73FFFFFF))
            val artX = MARGIN + 62f
            drawArtwork(ink.canvas, cover(covers, row.artworkUrl), row.title, artX, rowY, 108f, circular)
            val nameX = artX + 108f + 28f
            val stat = body(38f, 0x80FFFFFF.toInt())
            val statText = ForkReplayText.listening(row.ms)
            val statWidth = ink.width(statText, stat)
            ink.text(row.title, nameX, rowY + 72f, body(48f, 0xFFFFFFFF.toInt(), bold = true), W - MARGIN - nameX - statWidth - 32f)
            ink.text(statText, W - MARGIN, rowY + 72f, stat, alignRight = true)
            rowY += 148f
        }
    }

    private fun drawBigList(ink: Ink, rows: List<DesktopReplayRow>, top: Float) {
        val lead = rows.firstOrNull() ?: return
        ink.text(lead.title, MARGIN, top + 120f, heading(150f, 0xFFFFFFFF.toInt()), CONTENT_W)
        ink.text(ForkReplayText.listening(lead.ms), MARGIN, top + 186f, body(46f, 0x99FFFFFF.toInt()))
        var y = top + 300f
        rows.drop(1).forEach { row ->
            ink.text(row.rank.toString(), MARGIN, y, heading(48f, 0x73FFFFFF))
            val stat = body(38f, 0x80FFFFFF.toInt())
            val statText = ForkReplayText.listening(row.ms)
            ink.text(row.title, MARGIN + 80f, y, body(52f, 0xFFFFFFFF.toInt(), bold = true), CONTENT_W - 80f - ink.width(statText, stat) - 32f)
            ink.text(statText, W - MARGIN, y, stat, alignRight = true)
            y += 106f
        }
    }

    private fun drawHabits(ink: Ink, summary: DesktopReplaySummary, top: Float) {
        var y = top + 40f
        fun stat(value: String, caption: String) {
            ink.text(value, MARGIN, y, heading(84f, 0xFFFFFFFF.toInt()), CONTENT_W)
            ink.text(caption, MARGIN, y + 56f, body(42f, 0x99FFFFFF.toInt()), CONTENT_W)
            y += 176f
        }
        if (summary.distinctAlbums > 0) {
            stat(ForkReplayText.grouped(summary.distinctAlbums.toLong()), ForkReplayText.t("different_albums", "Different albums"))
        }
        summary.busiestDay?.let {
            stat(ForkReplayText.day(it), DesktopStrings.format("your_biggest_day", ForkReplayText.listening(summary.busiestDayMs), fallback = "Your biggest day: %1\$s"))
        }
        summary.peakHour?.let { stat(ForkReplayText.hour(it), ForkReplayText.t("when_you_listen_the_most", "When you listen the most")) }
    }

    private fun drawRecap(ink: Ink, summary: DesktopReplaySummary, genres: List<DesktopRankedEntry>, top: Float) {
        var y = top + 40f
        fun line(caption: String, value: String) {
            ink.text(caption, MARGIN, y, body(42f, 0x80FFFFFF.toInt()), 300f)
            ink.text(value, MARGIN + 320f, y, body(52f, 0xFFFFFFFF.toInt(), bold = true), CONTENT_W - 320f)
            y += 96f
        }
        line(ForkReplayText.t("minutes", "Minutes"), ForkReplayText.minutes(summary.totalMs))
        summary.songs.firstOrNull()?.let { line(ForkReplayText.t("top_song", "Top song"), it.song.title) }
        summary.artists.firstOrNull()?.let { line(ForkReplayText.t("top_artist", "Top artist"), it.title) }
        summary.albums.firstOrNull()?.let { line(ForkReplayText.t("top_album", "Top album"), it.title) }
        genres.firstOrNull()?.let { line(ForkReplayText.t("top_genre", "Top genre"), it.title) }
    }

    private fun drawCollage(canvas: Canvas, summary: DesktopReplaySummary, covers: Map<String, Image?>, top: Float) {
        val squares = summary.songs.mapNotNull { it.song.thumbnailUrl?.takeIf(String::isNotBlank) }.distinct().take(3)
        val faces = summary.artists.mapNotNull { it.artworkUrl?.takeIf(String::isNotBlank) }.distinct().filterNot { it in squares }.take(3)
        val squareAt = listOf(Triple(0.22f, 0.30f, 500f), Triple(0.02f, 0.06f, 260f), Triple(0.66f, 0.00f, 215f))
        val faceAt = listOf(Triple(0.00f, 0.62f, 185f), Triple(0.72f, 0.32f, 225f), Triple(0.46f, 0.76f, 200f))
        val height = 900f
        squares.forEachIndexed { index, url ->
            val (fx, fy, size) = squareAt[index]
            drawArtwork(canvas, covers[url], "", MARGIN + CONTENT_W * fx, top + height * fy, size, false)
        }
        faces.forEachIndexed { index, url ->
            val (fx, fy, size) = faceAt[index]
            drawArtwork(canvas, covers[url], "", MARGIN + CONTENT_W * fx, top + height * fy, size, true)
        }
    }

    // ── The whole Replay ────────────────────────────────────────────────

    private fun drawHeader(ink: Ink, logo: ByteArray?, summary: DesktopReplaySummary, holder: String, memberSince: String?): Float {
        ink.text(ForkReplayText.title(ForkReplayText.label(summary.period)), MARGIN, 132f, heading(46f, 0xFFFFFFFF.toInt()))
        val brand = heading(46f, 0xE6FFFFFF.toInt())
        ink.text("BitChord", W - MARGIN, 132f, brand, alignRight = true)
        logo?.let { drawLogo(ink.canvas, it, W - MARGIN - ink.width("BitChord", brand) - LOGO_GAP, 132f) }
        val credit = listOfNotNull(
            ForkReplayText.holder(holder),
            memberSince?.let { DesktopStrings.format("member_since_value", it, fallback = "Member since %1\$s") },
        ).joinToString(" · ")
        ink.text(credit.uppercase(Locale.ROOT), MARGIN, 182f, label(24f, 0x8CFFFFFF.toInt()), CONTENT_W)
        return 300f
    }

    private fun drawTotals(ink: Ink, summary: DesktopReplaySummary, top: Float): Float {
        ink.text(ForkReplayText.minutes(summary.totalMs), MARGIN, top + 120f, heading(148f, 0xFFFFFFFF.toInt()))
        ink.text(
            ForkReplayText.t("minutes_listened", "Minutes listened").uppercase(Locale.getDefault()),
            MARGIN, top + 176f, label(30f, 0xB3FFFFFF.toInt(), tracking = 0.14f),
        )
        ink.text(
            "${ForkReplayText.plays(summary.totalPlays)} · ${ForkReplayText.songs(summary.distinctSongs)} · ${ForkReplayText.artists(summary.distinctArtists)}",
            MARGIN, top + 232f, body(30f, 0x99FFFFFF.toInt()), CONTENT_W,
        )
        return top + 320f
    }

    private fun drawColumns(ink: Ink, songs: List<DesktopReplayRow>, artists: List<DesktopReplayRow>, covers: Map<String, Image?>, top: Float): Float {
        val columnWidth = (W - MARGIN * 2 - COLUMN_GAP) / 2f
        val right = MARGIN + columnWidth + COLUMN_GAP
        val heading = label(28f, 0xB3FFFFFF.toInt(), tracking = 0.16f)
        ink.text(ForkReplayText.t("top_songs", "Top songs").uppercase(Locale.getDefault()), MARGIN, top, heading, columnWidth)
        ink.text(ForkReplayText.t("top_artists", "Top artists").uppercase(Locale.getDefault()), right, top, heading, columnWidth)
        var y = top + 54f
        repeat(maxOf(songs.size, artists.size)) { index ->
            songs.getOrNull(index)?.let { drawRow(ink, it, covers, MARGIN, y, columnWidth, false) }
            artists.getOrNull(index)?.let { drawRow(ink, it, covers, right, y, columnWidth, true) }
            y += ROW_HEIGHT
        }
        return y + 40f
    }

    private fun drawRow(ink: Ink, row: DesktopReplayRow, covers: Map<String, Image?>, x: Float, y: Float, width: Float, circular: Boolean) {
        ink.text(row.rank.toString(), x, y + ART * 0.68f, heading(34f, if (row.rank == 1) ACCENT else 0x73FFFFFF))
        val artX = x + 44f
        drawArtwork(ink.canvas, cover(covers, row.artworkUrl), row.title, artX, y, ART, circular)
        val textX = artX + ART + 20f
        val textWidth = width - (textX - x)
        ink.text(row.title, textX, y + 36f, body(30f, 0xFFFFFFFF.toInt(), bold = true), textWidth)
        val detail = listOfNotNull(row.subtitle, ForkReplayText.listening(row.ms)).joinToString(" · ")
        ink.text(detail, textX, y + 72f, body(25f, 0x99FFFFFF.toInt()), textWidth)
    }

    private fun drawAlbums(ink: Ink, albums: List<DesktopReplayRow>, covers: Map<String, Image?>, top: Float): Float {
        if (albums.isEmpty()) return top
        ink.text(ForkReplayText.t("top_albums", "Top albums").uppercase(Locale.getDefault()), MARGIN, top, label(28f, 0xB3FFFFFF.toInt(), tracking = 0.16f))
        val size = 200f
        val gap = (W - MARGIN * 2 - size * POSTER_ALBUMS) / (POSTER_ALBUMS - 1)
        val y = top + 40f
        albums.take(POSTER_ALBUMS).forEachIndexed { index, album ->
            val x = MARGIN + index * (size + gap)
            drawArtwork(ink.canvas, cover(covers, album.artworkUrl), album.title, x, y, size, false)
            ink.text(album.title, x, y + size + 38f, body(25f, 0xFFFFFFFF.toInt(), bold = true), size)
            ink.text(album.subtitle ?: ForkReplayText.listening(album.ms), x, y + size + 70f, body(22f, 0x8CFFFFFF.toInt()), size)
        }
        return y + size + 130f
    }

    private fun drawGenres(ink: Ink, genres: List<DesktopRankedEntry>, top: Float) {
        val rows = ForkReplayText.genreRows(genres, POSTER_GENRES)
        if (rows.isEmpty()) return
        ink.text(ForkReplayText.t("top_genres", "Top genres").uppercase(Locale.getDefault()), MARGIN, top, label(28f, 0xB3FFFFFF.toInt(), tracking = 0.16f))
        val face = body(32f, 0xFFFFFFFF.toInt(), bold = true)
        val chip = Paint().apply { isAntiAlias = true; color = 0x26FFFFFF }
        var x = MARGIN
        val y = top + 44f
        for (genre in rows) {
            val width = ink.width(genre.title, face) + 44f
            if (x + width > W - MARGIN) return
            ink.canvas.drawRRect(RRect.makeXYWH(x, y, width, 62f, 31f), chip)
            ink.text(genre.title, x + 22f, y + 42f, face)
            x += width + 14f
        }
    }

    /**
     * A card's sentence, wrapped, the emphasised runs in the heavy face (phone `drawRuns`): one
     * paragraph with a style per run, which breaks inside the bold part the way the phone's
     * word-by-word layout does. The first baseline sits [HEADLINE_SIZE] below [top]; returns the
     * last baseline plus 24, as the phone does.
     */
    private fun drawRuns(ink: Ink, runs: List<ForkHeadlineRun>, x: Float, top: Float, maxWidth: Float): Float {
        val builder = ParagraphBuilder(ParagraphStyle(), ink.fonts)
        runs.forEach { run ->
            val face = if (run.bold) heading(HEADLINE_SIZE, 0xFFFFFFFF.toInt()) else body(HEADLINE_SIZE, 0x9EFFFFFF.toInt(), bold = true)
            builder.pushStyle(face.style().apply { height = HEADLINE_LEADING / HEADLINE_SIZE }).addText(run.text).popStyle()
        }
        val paragraph = builder.build().apply { layout(maxWidth) }
        val firstBaseline = top + HEADLINE_SIZE
        paragraph.paint(ink.canvas, x, firstBaseline - paragraph.alphabeticBaseline)
        val lines = paragraph.lineNumber.toInt().coerceAtLeast(1)
        return firstBaseline + (lines - 1) * HEADLINE_LEADING + 24f
    }

    // ── Pieces ──────────────────────────────────────────────────────────

    private fun cover(covers: Map<String, Image?>, url: String?): Image? = url?.let { covers[it] }

    /** Four wide radial gradients off the cover's colours, [hue]-turned, under a vertical scrim (phone `drawBackdrop`). */
    private fun drawBackdrop(canvas: Canvas, palette: List<Int>, hue: Float) {
        val colors = palette.map { ForkReplayText.rotated(it, hue) }
        canvas.clear(ForkLyricsShare.dimmed(colors.first()))
        val anchors = listOf(0.20f to 0.16f, 0.84f to 0.22f, 0.76f to 0.66f, 0.18f to 0.78f)
        colors.forEachIndexed { index, color ->
            val (fx, fy) = anchors[index]
            val cx = W * fx
            val cy = H * fy
            val radius = W * 0.95f
            val paint = Paint().apply {
                isAntiAlias = true
                shader = Shader.makeRadialGradient(cx, cy, radius, intArrayOf(withAlpha(color, 210), withAlpha(color, 0)), floatArrayOf(0f, 1f), GradientStyle.DEFAULT)
            }
            canvas.drawCircle(cx, cy, radius, paint)
        }
        val ink = Paint().apply {
            shader = Shader.makeLinearGradient(
                0f, 0f, 0f, H.toFloat(),
                intArrayOf(0x8C000000.toInt(), 0x59000000, 0xCC000000.toInt()),
                floatArrayOf(0f, 0.42f, 1f),
                GradientStyle.DEFAULT,
            )
        }
        canvas.drawRect(Rect.makeWH(W.toFloat(), H.toFloat()), ink)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** A cover cropped square and rounded (a circle for an artist), or the lettered stand-in's colour. */
    private fun drawArtwork(canvas: Canvas, cover: Image?, fallback: String, x: Float, y: Float, size: Float, circular: Boolean) {
        val radius = if (circular) size / 2f else size * 0.10f
        val bounds = RRect.makeXYWH(x, y, size, size, radius)
        if (cover != null) {
            val side = minOf(cover.width, cover.height).toFloat()
            val src = Rect.makeXYWH((cover.width - side) / 2f, (cover.height - side) / 2f, side, side)
            canvas.save()
            canvas.clipRRect(bounds, true)
            canvas.drawImageRect(cover, src, Rect.makeXYWH(x, y, size, size), FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), Paint().apply { isAntiAlias = true }, true)
            canvas.restore()
        } else {
            val hue = (fallback.hashCode().toFloat() % 360f + 360f) % 360f
            canvas.drawRRect(bounds, Paint().apply { isAntiAlias = true; color = ForkLyricsShare.hslToColor(hue, 0.55f, 0.45f) })
        }
    }

    /** The BitChord mark in 90% white, baseline-aligned with the word beside it. */
    private fun drawLogo(canvas: Canvas, svg: ByteArray, right: Float, baseline: Float) {
        runCatching {
            val dom = SVGDOM(Data.makeFromBytes(svg))
            dom.setContainerSize(730f, 484f)
            val left = right - LOGO_W
            val top = baseline - LOGO_H
            canvas.saveLayer(Rect.makeXYWH(left, top, LOGO_W, LOGO_H), Paint().apply { colorFilter = ColorFilter.makeBlend(0xE6FFFFFF.toInt(), BlendMode.SRC_IN) })
            canvas.translate(left, top)
            val intrinsic = dom.root?.width?.value?.takeIf { it > 0f } ?: 730f
            val intrinsicH = dom.root?.height?.value?.takeIf { it > 0f } ?: 484f
            canvas.scale(LOGO_W / intrinsic, LOGO_H / intrinsicH)
            dom.render(canvas)
            canvas.restore()
        }
    }

    // ── Out of the app ──────────────────────────────────────────────────

    /** The system's save dialog in Pictures; the file written, or null (cancelled or failed). */
    fun saveWithDialog(card: ForkLyricsCard, label: String): File? = runCatching {
        // The test door's path (ForkDebugBridge /pick), once, in place of the dialog it can't drive.
        ForkDebugBridge.takePick()?.let { return@runCatching it.toFile().also { file -> file.writeBytes(card.png) } }
        val dialog = FileDialog(null as java.awt.Frame?, DesktopStrings["save", "Save"], FileDialog.SAVE)
        dialog.directory = ForkLyricsShare.picturesDir().path
        dialog.file = fileName(label)
        dialog.isVisible = true
        val name = dialog.file ?: return null
        File(dialog.directory ?: ForkLyricsShare.picturesDir().path, ForkLyricsShare.withPngExtension(name)).also { it.writeBytes(card.png) }
    }.getOrNull()
}

/**
 * The phone's `ReplayShareSheet` on the PC: the picture full size, then Save and Copy image (the
 * phone's Save and Share). A card's share button sends that card; the page's "Share my Replay"
 * sends the whole Replay.
 */
@Composable
internal fun ForkReplayShareSheet(
    liveSummary: DesktopReplaySummary,
    liveGenres: List<DesktopRankedEntry>,
    holder: String,
    page: ForkStoryPage?,
    onDismiss: () -> Unit,
) {
    // Held as the sheet opened: the app recounts the Replay every 5 s while music plays, and
    // drawing the picture again on each would flash the spinner, disable the buttons and re-arm
    // Save under the cursor. The genres are taken once more when the first ones land (the overlay's
    // chart starts empty for a frame).
    val summary = remember(page) { liveSummary }
    val genres = remember(page, liveGenres.isNotEmpty()) { liveGenres }
    val scope = rememberCoroutineScope()
    var card by remember(summary, page) { mutableStateOf<ForkLyricsCard?>(null) }
    var failed by remember(summary, page) { mutableStateOf(false) }
    var saved by remember(summary, page) { mutableStateOf(false) }
    var copied by remember(summary, page) { mutableStateOf(false) }

    LaunchedEffect(summary, genres, page) {
        card = runCatching { ForkReplayPoster.render(summary, genres, holder, summary.memberSince(), page) }
            .onFailure {
                DesktopTrackLog.log("Replay poster: ${it.javaClass.simpleName}: ${it.message}")
                failed = true
            }
            .getOrNull()
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 460, popup = true) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                ForkReplayText.t("share_my_replay", "Share my Replay"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.W800,
                color = Color.White,
            )
            Text(
                if (page == null) {
                    ForkReplayText.t("replay_share_summary_description", "A full picture with songs, artists, albums, genres, and habits.")
                } else {
                    ForkReplayText.t("replay_share_card_description", "A polished card with your main listening highlights.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = DesktopSecondary,
            )
            // Small enough that the buttons stay on screen with it in a short window.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(0.62f)
                    .heightIn(max = 460.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                val drawn = card
                when {
                    drawn != null -> Image(drawn.image, ForkReplayText.t("your_replay", "Your Replay"), Modifier.fillMaxWidth())
                    failed -> Text(
                        ForkReplayText.t("couldnt_draw_picture", "Could not create the picture"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DesktopSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp, color = Color.White.copy(alpha = 0.75f))
                }
            }
            // Disabled rather than hidden while the picture draws: buttons that appear a second
            // late get clicked at the moment they move (phone note).
            val ready = card != null
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReplayShareAction(
                    label = ForkReplayText.t("save", "Save"),
                    doneLabel = ForkReplayText.t("saved", "Saved"),
                    icon = Icons.Rounded.Download,
                    accent = false,
                    enabled = ready && !saved,
                    done = saved,
                    modifier = Modifier.weight(1f),
                ) {
                    val current = card ?: return@ReplayShareAction
                    // The dialog is modal on the window's thread; the write is a few hundred KB.
                    saved = ForkReplayPoster.saveWithDialog(current, ForkReplayText.label(summary.period)) != null
                }
                ReplayShareAction(
                    label = "Copy image",
                    doneLabel = "Copied!",
                    icon = Icons.Rounded.ContentCopy,
                    accent = true,
                    enabled = ready,
                    done = copied,
                    modifier = Modifier.weight(1f),
                ) {
                    val current = card ?: return@ReplayShareAction
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ForkLyricsShare.copyToClipboard(current) }
                        copied = ok
                        if (!ok) failed = true
                    }
                }
            }
        }
    }
}

/** The phone's `ShareAction`, in the PC's white alphas (as the lyrics card's sheet draws it). */
@Composable
private fun ReplayShareAction(
    label: String,
    doneLabel: String,
    icon: ImageVector,
    accent: Boolean,
    enabled: Boolean,
    done: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = if (accent) Color.White.copy(alpha = if (enabled) 1f else 0.3f) else Color.White.copy(alpha = if (enabled) 0.10f else 0.05f)
    val foreground = if (accent) Color(0xFF121212).copy(alpha = if (enabled) 1f else 0.4f) else Color.White.copy(alpha = if (enabled || done) 0.85f else 0.4f)
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(targetState = done, label = "forkReplayShareAction") { isDone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isDone) Icons.Rounded.Check else icon, null, tint = foreground, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (isDone) doneLabel else label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W700,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
