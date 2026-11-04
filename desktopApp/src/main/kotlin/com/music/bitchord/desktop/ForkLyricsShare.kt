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
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Undo
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
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bitchord.desktopapp.generated.resources.Res
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.graphics.paletteSwatches
import com.music.bitchord.ui.player.LyricsShareLine
import com.music.bitchord.ui.player.LyricsShareRequest
import com.music.bitchord.ui.player.PlayerDrawer
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
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
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Fork: the phone's lyrics share card on the PC. sharedUi already has the pick mode
 * (`LyricsShare.kt`: long-press a line or the header's share glyph, the 250-character budget,
 * the Cancel/Share bar) and only asks the platform for [PlayerHost.lyricsShareAvailable] and a
 * `LyricsShareSheet`; the PC left both at their defaults (no pick mode at all).
 *
 * The card is the phone's `renderLyricsShareCard` (`app/…/ui/player/LyricsShareCard.kt`, with
 * `ReplayPoster`'s backdrop, mark, sleeve and fonts) redrawn with Skia off-screen: 1080 wide,
 * only as tall as the passage (never past 1920), the size ladder 96…32 px, SF Pro Display Heavy
 * with the panel's tracking and line advance, "(...)" for skipped lines, the sleeve with title and
 * artist at the foot, "BitChord" and the mark at the top right; "Make it a story" pads it to the
 * full 1080×1920. Text goes through Skia's paragraph layout (wrapping, CJK breaks, RTL and font
 * fallback for scripts SF Pro lacks), where the phone hand-wraps with `Paint.breakText`.
 *
 * The sheet is the phone's `AndroidLyricsShareSheet` in the same `PlayerDrawer`; where the phone
 * has Save (gallery) and Share (the Android share sheet), the PC has Save (the system's file
 * dialog, opening in the user's Pictures folder) and Copy (the image onto the clipboard).
 * Neither PC fork has a lyrics card; nothing taken from them.
 */
internal class ForkLyricsCard(val image: ImageBitmap, val png: ByteArray) {
    val width: Int get() = image.width
    val height: Int get() = image.height
}

internal object ForkLyricsShare {

    const val CARD_W = 1080

    /** 9:16, a ceiling on the height and a story's whole frame. */
    const val MAX_CARD_H = 1920f

    private const val CARD_MARGIN = 72f
    private const val CARD_CONTENT_W = CARD_W - CARD_MARGIN * 2
    private const val LOGO_W = 66f
    private const val LOGO_H = 44f
    private const val LOGO_GAP = 20f
    private const val BRAND = "BitChord"
    private const val HEADER_BASELINE = 132f
    private const val HEADER_BOTTOM = 170f
    private const val GAP_ABOVE_BODY = 20f
    private const val GAP_BELOW_BODY = 20f
    private const val FOOTER_THUMB = 160f
    private const val FOOTER_PAD = 96f
    private const val SUB_FLOOR = 24f
    private const val GAP_MARKER_OFFSET = 0.18f
    val FIT_LADDER = floatArrayOf(96f, 88f, 80f, 72f, 64f, 56f, 50f, 44f, 38f, 32f)

    // The panel's type as ratios (phone): tracking -0.7sp over 34sp / 20sp, line advance 41/34 and
    // 25/20, the sub line 20/34 of the words in 85% white. Skia's letter spacing is in pixels.
    private const val TRACKING = -0.7f / 34f
    private const val SUB_TRACKING = -0.7f / 20f
    private const val LEAD = 41f / 34f
    private const val SUB_LEAD = 25f / 20f
    private const val SUB_RATIO = 20f / 34f
    private const val SUB_COLOR = 0xD9FFFFFF.toInt()

    /** The phone's gallery name (`saveToGallery`'s `prefix-label`), made safe for Windows. */
    fun fileName(title: String): String {
        val label = title.trim().replace(' ', '-').lowercase(Locale.ROOT)
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "")
            .trim('.', '-')
            .take(80)
        return if (label.isEmpty()) "bitchord-lyrics.png" else "bitchord-lyrics-$label.png"
    }

    /** The user's Pictures folder, else their home. */
    fun picturesDir(home: String = ForkPortable.userHome): File =
        File(home, "Pictures").takeIf(File::isDirectory) ?: File(home)

    /** The saved name always ends in .png, whatever was typed into the dialog. */
    fun withPngExtension(name: String): String = if (name.endsWith(".png", ignoreCase = true)) name else "$name.png"

    // ── Fonts ───────────────────────────────────────────────────────────────

    private class Type(val fonts: FontCollection)

    private const val HEAVY = "BitChord Heavy"
    private const val SEMIBOLD = "BitChord Semibold"
    private const val REGULAR = "BitChord Regular"

    @Volatile
    private var type: Type? = null

    /** SF Pro Display from the app's own resources, with the system's fonts as the fallback. */
    private suspend fun type(): Type {
        type?.let { return it }
        val provider = TypefaceFontProvider()
        for ((file, alias) in listOf(
            "sf_pro_display_heavy.otf" to HEAVY,
            "sf_pro_display_semibold.otf" to SEMIBOLD,
            "sf_pro_display_regular.otf" to REGULAR,
        )) {
            runCatching {
                val face = FontMgr.default.makeFromData(Data.makeFromBytes(Res.readBytes("font/$file")))
                if (face != null) provider.registerTypeface(face, alias)
            }
        }
        val fonts = FontCollection().apply {
            setAssetFontManager(provider)
            setDefaultFontManager(FontMgr.default)
        }
        return Type(fonts).also { type = it }
    }

    private fun style(family: String, size: Float, color: Int, tracking: Float = 0f, lead: Float? = null) =
        TextStyle().apply {
            fontFamilies = arrayOf(family)
            fontSize = size
            this.color = color
            letterSpacing = tracking * size
            if (lead != null) height = lead
        }

    private fun paragraph(type: Type, text: String, style: TextStyle, width: Float, oneLine: Boolean = false): Paragraph {
        val paragraphStyle = ParagraphStyle().apply {
            if (oneLine) {
                maxLinesCount = 1
                ellipsis = "…"
            }
        }
        return ParagraphBuilder(paragraphStyle, type.fonts)
            .pushStyle(style)
            .addText(text)
            .popStyle()
            .build()
            .apply { layout(width) }
    }

    // ── Fitting ─────────────────────────────────────────────────────────────

    private class Row(val paragraph: Paragraph, val gapBefore: Float, val markerOffset: Float = 0f) {
        val height: Float get() = paragraph.height
    }

    private class Plan(val rows: List<Row>) {
        val content: Float get() = rows.fold(0f) { total, row -> total + row.gapBefore + row.height }
    }

    /** The largest rung whose rows fit [room], else the bottom rung (a floor: the card grows instead). */
    private fun fittestPlan(type: Type, lines: List<LyricsShareLine>, room: Float): Plan {
        for (size in FIT_LADDER) {
            val plan = planAt(type, lines, size)
            if (plan.content <= room) return plan
        }
        return planAt(type, lines, FIT_LADDER.last())
    }

    private fun planAt(type: Type, lines: List<LyricsShareLine>, size: Float): Plan {
        val primary = style(HEAVY, size, 0xFFFFFFFF.toInt(), TRACKING, LEAD)
        val subSize = (size * SUB_RATIO).coerceAtLeast(SUB_FLOOR)
        val secondary = style(HEAVY, subSize, SUB_COLOR, SUB_TRACKING, SUB_LEAD)
        val rows = mutableListOf<Row>()
        lines.forEachIndexed { index, line ->
            if (line.isGap) {
                val marker = style(REGULAR, size * 0.65f, 0x66FFFFFF)
                rows += Row(paragraph(type, "(...)", marker, CARD_CONTENT_W), 0f, size * GAP_MARKER_OFFSET)
                return@forEachIndexed
            }
            // One verse line is one paragraph: the space goes above its first row, its wraps sit tight.
            val gap = if (rows.isEmpty() || index == 0) 0f else size * 0.55f
            rows += Row(paragraph(type, line.text.trim(), primary, CARD_CONTENT_W), gap)
            val sub = line.subText
            if (!sub.isNullOrBlank() && sub != line.text) {
                rows += Row(paragraph(type, sub.trim(), secondary, CARD_CONTENT_W), 0f)
            }
        }
        return Plan(rows)
    }

    // ── The card ────────────────────────────────────────────────────────────

    /** The phone's `renderLyricsShareCard`, on a Skia raster surface. */
    suspend fun render(request: LyricsShareRequest, story: Boolean): ForkLyricsCard {
        val cover = loadCover(request.artworkUrl)
        val logo = runCatching { Res.readBytes("drawable/logo_mark.svg") }.getOrNull()
        val type = type()
        return withContext(Dispatchers.Default) {
            val headTop = HEADER_BOTTOM + GAP_ABOVE_BODY
            val frameRoom = MAX_CARD_H - FOOTER_THUMB - FOOTER_PAD - GAP_BELOW_BODY - headTop
            val plan = fittestPlan(type, request.lines, frameRoom)
            val spare = if (story) (frameRoom - plan.content).coerceAtLeast(0f) else 0f
            val bodyTop = headTop + spare / 2
            val bodyBottom = bodyTop + plan.content
            val cardH = bodyBottom + spare / 2 + GAP_BELOW_BODY + FOOTER_THUMB + FOOTER_PAD

            val surface = Surface.makeRasterN32Premul(CARD_W, cardH.roundToInt())
            val canvas = surface.canvas
            drawBackdrop(canvas, cover, cardH)

            // The mark and the word at the top right, as the Replay carries them.
            val brand = paragraph(type, BRAND, style(HEAVY, 46f, 0xE6FFFFFF.toInt()), 2000f)
            val brandWidth = brand.maxIntrinsicWidth
            brand.paint(canvas, CARD_W - CARD_MARGIN - brandWidth, HEADER_BASELINE - brand.alphabeticBaseline)
            logo?.let { drawLogo(canvas, it, CARD_W - CARD_MARGIN - brandWidth - LOGO_GAP, HEADER_BASELINE) }

            var y = bodyTop
            for (row in plan.rows) {
                row.paragraph.paint(canvas, CARD_MARGIN, y + row.gapBefore + row.markerOffset)
                y += row.gapBefore + row.height
            }

            val thumbY = cardH - FOOTER_PAD - FOOTER_THUMB
            drawArtwork(canvas, cover?.image, request.song.title, CARD_MARGIN, thumbY, FOOTER_THUMB)
            val textX = CARD_MARGIN + FOOTER_THUMB + 34f
            val room = CARD_W - CARD_MARGIN - textX
            val title = paragraph(type, request.song.title, style(SEMIBOLD, 50f, 0xFFFFFFFF.toInt()), room, oneLine = true)
            title.paint(canvas, textX, thumbY + 74f - title.alphabeticBaseline)
            val artist = paragraph(type, request.song.artist, style(REGULAR, 42f, 0x99FFFFFF.toInt()), room, oneLine = true)
            artist.paint(canvas, textX, thumbY + 132f - artist.alphabeticBaseline)

            val snapshot = surface.makeImageSnapshot()
            val png = snapshot.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed")
            ForkLyricsCard(snapshot.toComposeImageBitmap(), png)
        }
    }

    /** The sleeve, and the four colours the backdrop takes from it. */
    private class Cover(val image: Image, val palette: List<Int>)

    private suspend fun loadCover(url: String?): Cover? {
        if (url.isNullOrBlank()) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    url.startsWith("http", ignoreCase = true) -> DesktopArtworkCache.load(url.artworkAt(CARD_ART_PX))
                    url.startsWith("file:", ignoreCase = true) -> File(URI(url)).readBytes().decodeToImageBitmap()
                    File(url).isFile -> File(url).readBytes().decodeToImageBitmap()
                    else -> DesktopArtworkCache.load(url)
                }
            }.getOrNull()
        } ?: return null
        return runCatching { Cover(Image.makeFromBitmap(bitmap.asSkiaBitmap()), paletteOf(bitmap)) }.getOrNull()
    }

    /**
     * The Replay's mesh, by hand: four wide radial gradients off the sleeve's colours over a dark
     * base, flattened under a vertical scrim weighted to the foot.
     */
    private fun drawBackdrop(canvas: Canvas, cover: Cover?, height: Float) {
        val colors = cover?.palette ?: FALLBACK_PALETTE
        canvas.clear(dimmed(colors.first()))
        val anchors = listOf(0.20f to 0.16f, 0.84f to 0.22f, 0.76f to 0.66f, 0.18f to 0.78f)
        colors.forEachIndexed { index, color ->
            val (fx, fy) = anchors[index]
            val cx = CARD_W * fx
            val cy = height * fy
            val radius = CARD_W * 0.95f
            val paint = Paint().apply {
                isAntiAlias = true
                shader = Shader.makeRadialGradient(
                    cx, cy, radius,
                    intArrayOf(withAlpha(color, 210), withAlpha(color, 0)),
                    floatArrayOf(0f, 1f),
                    GradientStyle.DEFAULT,
                )
            }
            canvas.drawCircle(cx, cy, radius, paint)
        }
        val ink = Paint().apply {
            shader = Shader.makeLinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(0x8C000000.toInt(), 0x59000000, 0xCC000000.toInt()),
                floatArrayOf(0f, 0.42f, 1f),
                GradientStyle.DEFAULT,
            )
        }
        canvas.drawRect(Rect.makeWH(CARD_W.toFloat(), height), ink)
    }

    /** The sleeve cropped square and rounded, or the lists' lettered stand-in colour. */
    private fun drawArtwork(canvas: Canvas, cover: Image?, fallback: String, x: Float, y: Float, size: Float) {
        val bounds = RRect.makeXYWH(x, y, size, size, size * 0.10f)
        if (cover != null) {
            val side = minOf(cover.width, cover.height).toFloat()
            val src = Rect.makeXYWH((cover.width - side) / 2f, (cover.height - side) / 2f, side, side)
            canvas.save()
            canvas.clipRRect(bounds, true)
            canvas.drawImageRect(
                cover, src, Rect.makeXYWH(x, y, size, size),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), Paint().apply { isAntiAlias = true }, true,
            )
            canvas.restore()
        } else {
            val hue = (fallback.hashCode().toFloat() % 360f + 360f) % 360f
            canvas.drawRRect(bounds, Paint().apply { isAntiAlias = true; color = hslToColor(hue, 0.55f, 0.45f) })
        }
    }

    /** The BitChord mark (730×484 in the vector) in 90% white, baseline-aligned with the word. */
    private fun drawLogo(canvas: Canvas, svg: ByteArray, right: Float, baseline: Float) {
        runCatching {
            val dom = SVGDOM(Data.makeFromBytes(svg))
            dom.setContainerSize(730f, 484f)
            val left = right - LOGO_W
            val top = baseline - LOGO_H
            canvas.saveLayer(
                Rect.makeXYWH(left, top, LOGO_W, LOGO_H),
                Paint().apply { colorFilter = ColorFilter.makeBlend(0xE6FFFFFF.toInt(), BlendMode.SRC_IN) },
            )
            canvas.translate(left, top)
            // The file declares 28×18 px; scale from that to the drawn size.
            val intrinsic = dom.root?.width?.value?.takeIf { it > 0f } ?: 730f
            val intrinsicH = dom.root?.height?.value?.takeIf { it > 0f } ?: 484f
            canvas.scale(LOGO_W / intrinsic, LOGO_H / intrinsicH)
            dom.render(canvas)
            canvas.restore()
        }
    }

    // ── Colour (androidx ColorUtils' HSL, as ReplayPoster uses it) ──────────

    val FALLBACK_PALETTE = listOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFF2B5876.toInt(), 0xFFFFAF7B.toInt())

    /** Four colours off the sleeve, saturated and held to a mid lightness (phone `paletteOf`). */
    private fun paletteOf(cover: ImageBitmap): List<Int> {
        val swatches = runCatching {
            paletteSwatches(cover, 24, false).sortedByDescending { it.population }.map { it.rgb }
        }.getOrNull().orEmpty()
        if (swatches.isEmpty()) return FALLBACK_PALETTE
        return (swatches + FALLBACK_PALETTE).take(4).map(::tuned)
    }

    fun tuned(color: Int): Int {
        val (h, s, l) = colorToHsl(color)
        return hslToColor(h, (s * 1.35f).coerceAtMost(1f), l.coerceIn(0.28f, 0.58f))
    }

    fun dimmed(color: Int): Int {
        val (h, s, _) = colorToHsl(color)
        return hslToColor(h, s, 0.10f)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun colorToHsl(color: Int): Triple<Float, Float, Float> {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)
        val h = when (max) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        val s = delta / (1f - kotlin.math.abs(2f * l - 1f))
        return Triple(((h * 60f) % 360f + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    fun hslToColor(h: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2 * l - 1f)) * s
        val m = l - 0.5f * c
        val x = c * (1f - kotlin.math.abs((h / 60f % 2f) - 1f))
        val (r, g, b) = when ((h.toInt() / 60).coerceIn(0, 5)) {
            0 -> Triple(c + m, x + m, m)
            1 -> Triple(x + m, c + m, m)
            2 -> Triple(m, c + m, x + m)
            3 -> Triple(m, x + m, c + m)
            4 -> Triple(x + m, m, c + m)
            else -> Triple(c + m, m, x + m)
        }
        fun channel(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    // ── Out of the app ──────────────────────────────────────────────────────

    /** The system's save dialog in Pictures; the file written, or null (cancelled or failed). */
    fun saveWithDialog(card: ForkLyricsCard, title: String): File? = runCatching {
        // The test door's path (ForkDebugBridge /pick), once, in place of the dialog it can't drive.
        ForkDebugBridge.takePick()?.let { return@runCatching it.toFile().also { file -> file.writeBytes(card.png) } }
        val dialog = FileDialog(null as java.awt.Frame?, DesktopStrings["save", "Save"], FileDialog.SAVE)
        dialog.directory = picturesDir().path
        dialog.file = fileName(title)
        dialog.isVisible = true
        val name = dialog.file ?: return null
        File(dialog.directory ?: picturesDir().path, withPngExtension(name)).also { it.writeBytes(card.png) }
    }.getOrNull()

    /**
     * The card onto the system clipboard as an image. Opaque RGB: the card has no transparency,
     * and Windows' clipboard DIB path turns an alpha channel black in some apps.
     */
    fun copyToClipboard(card: ForkLyricsCard): Boolean = runCatching {
        val decoded = ImageIO.read(ByteArrayInputStream(card.png)) ?: return false
        val rgb = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(decoded, 0, 0, null); dispose() }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransfer(rgb), null)
        true
    }.getOrDefault(false)

    private class ImageTransfer(private val image: java.awt.Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}

/**
 * The phone's `AndroidLyricsShareSheet` on the PC: the drawn card in the player's drawer, the
 * story toggle, then Save and Copy in place of Save and Share.
 */
@Composable
internal fun ForkLyricsShareSheet(hazeState: HazeState, request: LyricsShareRequest, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var card by remember(request) { mutableStateOf<ForkLyricsCard?>(null) }
    var failed by remember(request) { mutableStateOf(false) }
    var saved by remember(request) { mutableStateOf(false) }
    var copied by remember(request) { mutableStateOf(false) }
    var story by remember(request) { mutableStateOf(false) }

    LaunchedEffect(request, story) {
        card = null
        failed = false
        saved = false
        copied = false
        card = runCatching { ForkLyricsShare.render(request, story) }
            .onFailure {
                DesktopTrackLog.log("Lyrics card: ${it.javaClass.simpleName}: ${it.message}")
                failed = true
            }
            .getOrNull()
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = DesktopStrings["lyrics_share_title", "Share lyrics"],
        onDismiss = onDismiss,
        titleGap = 2.dp,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                DesktopStrings["lyrics_share_description", "The lines you picked, laid out on a card with the cover."],
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            )
            val drawn = card
            val ratio = drawn?.let { it.width.toFloat() / it.height } ?: (9f / 16f)
            // A window can be short: the preview is held to 420 dp tall as well as to the phone's share
            // of the width.
            val preview = Modifier
                .fillMaxWidth((0.72f * ratio).coerceIn(0.36f, 0.92f))
                .heightIn(max = 420.dp)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(18.dp))
            if (drawn == null) {
                Box(preview.background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    if (!failed) {
                        CircularProgressIndicator(
                            Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
            } else {
                Image(drawn.image, DesktopStrings["your_lyrics_card", "Your lyrics card"], preview)
            }

            ForkShareAction(
                label = if (story) {
                    DesktopStrings["lyrics_share_story_revert", "Back to the card"]
                } else {
                    DesktopStrings["lyrics_share_story", "Make it a story"]
                },
                icon = if (story) Icons.Rounded.Undo else Icons.Rounded.PhoneIphone,
                accent = false,
                enabled = drawn != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { story = !story },
            )

            if (failed) {
                Text(
                    DesktopStrings["couldnt_draw_picture", "Could not create the picture"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ForkShareAction(
                    label = DesktopStrings["save", "Save"],
                    icon = Icons.Rounded.Download,
                    accent = false,
                    enabled = drawn != null,
                    done = saved,
                    doneLabel = DesktopStrings["saved_bang", "Saved!"],
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val current = card ?: return@ForkShareAction
                        // The dialog is modal on the window's thread; the write is a few hundred KB.
                        saved = ForkLyricsShare.saveWithDialog(current, request.song.title) != null
                    },
                )
                ForkShareAction(
                    label = "Copy image",
                    icon = Icons.Rounded.ContentCopy,
                    accent = true,
                    enabled = drawn != null,
                    done = copied,
                    doneLabel = "Copied!",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val current = card ?: return@ForkShareAction
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { ForkLyricsShare.copyToClipboard(current) }
                            copied = ok
                            if (!ok) failed = true
                        }
                    },
                )
            }
            ForkLineMeaningSection(request.song, request.lines.filterNot { it.isGap }.map { it.text }) // fork: what Genius wrote over the picked lines, off by default (ForkGeniusMeanings)
        }
    }
}

/** The phone's `ShareAction` in the player's white alphas (as sharedUi's pick bar draws them). */
@Composable
private fun ForkShareAction(
    label: String,
    icon: ImageVector,
    accent: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    done: Boolean = false,
    doneLabel: String? = null,
    onClick: () -> Unit,
) {
    val background = if (accent) {
        Color.White.copy(alpha = if (enabled) 1f else 0.3f)
    } else {
        Color.White.copy(alpha = if (enabled) 0.10f else 0.05f)
    }
    val foreground = if (accent) {
        Color(0xFF121212).copy(alpha = if (enabled) 1f else 0.4f)
    } else {
        Color.White.copy(alpha = if (enabled) 0.85f else 0.4f)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(targetState = done, label = "forkShareAction") { isDone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isDone) Icons.Rounded.Check else icon, null, tint = foreground, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (isDone) doneLabel ?: label else label,
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
