package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * "Accent colour" (Appearance): white as always, a picked colour, or the playing song's own (fork,
 * from PC-Continued be257dad's `DesktopAccent` / `DesktopArtworkPalette`).
 *
 * Our `DesktopAccent` was a constant `Color.White` (the phone's monochrome look), read in about eighty
 * places: the liked heart, shuffle and repeat, the selected sidebar row, primary buttons, text cursors.
 * It is now a getter over [current], which sits in snapshot state, so every one of those reads
 * recolours itself when the choice changes without any of them being touched. [DEFAULT] keeps the
 * current look, and it is where a new install starts.
 *
 * Unlike PC-Continued, ours draws black ink on the accent (`onPrimary`, the primary buttons), and
 * most reads tint an icon or a word on the dark page. So the swatches are Apple's system colours,
 * which all carry black ink, and a song's colour is lifted bright rather than dimmed under white ink
 * as theirs is. Switches and chips stay white: they were never the accent here.
 */
internal object ForkAccent {

    /** White: the look before this setting existed. */
    const val DEFAULT = "default"

    /** The colour comes from the playing song's cover. */
    const val SONG = "song"

    private const val KEY = "fork_accent_colour"

    /** The swatches, as PC-Continued's: Apple's dark-mode system colours. */
    val swatches = listOf(
        Triple("pink", "Pink", Color(0xFFFA586A)),
        Triple("orange", "Orange", Color(0xFFFF9F0A)),
        Triple("yellow", "Yellow", Color(0xFFFFD60A)),
        Triple("green", "Green", Color(0xFF30D158)),
        Triple("teal", "Teal", Color(0xFF64D2FF)),
        Triple("blue", "Blue", Color(0xFF0A84FF)),
        Triple("purple", "Purple", Color(0xFFBF5AF2)),
    )

    private val persistence = DesktopPersistence()

    private val _choice = MutableStateFlow(validated(persistence.string(KEY, DEFAULT)))

    /** [DEFAULT], [SONG] or a swatch key. */
    val choice: StateFlow<String> = _choice

    /** What every `DesktopAccent` read resolves to. */
    var current: Color by mutableStateOf(colourFor(_choice.value))
        private set

    fun setChoice(key: String) {
        val choice = validated(key)
        persistence.saveString(KEY, choice)
        _choice.value = choice
        // Following the song keeps the colour on screen until the cover has been read, rather than
        // flashing white for a frame.
        if (choice != SONG) current = colourFor(choice)
    }

    internal fun showSongColour(colour: Color) {
        if (_choice.value == SONG) current = colour
    }

    private fun validated(key: String): String =
        if (key == SONG || key == DEFAULT || swatches.any { it.first == key }) key else DEFAULT

    private fun colourFor(key: String): Color = swatches.firstOrNull { it.first == key }?.third ?: Color.White
}

/**
 * Keeps [ForkAccent.current] on the playing cover while "From the song" is chosen. Called once at the
 * top of the window, above the theme that reads it; does nothing (and reads no cover) otherwise.
 */
@Composable
internal fun ForkAccentSource(artworkUrl: String?) {
    val choice by ForkAccent.choice.collectAsState()
    val url = artworkUrl.takeIf { choice == ForkAccent.SONG }
    // While the song's cover video plays, its sampled frames instead of the still (ForkClipColours):
    // every sample so far counted together, so the colour settles on the clip's rather than
    // following one frame of it. Null (no clip, not sampled yet) is the cover, as before.
    val clip = if (url == null) null else ForkClipColours.samplesFor(url)
    LaunchedEffect(url, clip) {
        if (url == null) return@LaunchedEffect
        val colour = withContext(Dispatchers.IO) {
            if (clip != null) forkSongAccent(clip.map { it.pixels }, step = 1)
            else forkSongAccent(DesktopArtworkCache.load(url))
        }
        // A cover that could not be read leaves the accent it had: no flicker between tracks.
        if (colour != null) ForkAccent.showSongColour(colour)
    }
}

/**
 * The cover's accent, or null when it cannot be read. PC-Continued be257dad's read: 4-bit colour
 * cells rather than one average (the mean of a red cover and its black corner is a colour the cover
 * never had), the biggest cell winning with a thumb on the scale for saturation, so a magenta label
 * on a black sleeve reads as magenta. A cover with no hue at all gives white, the default look.
 */
private fun forkSongAccent(bitmap: ImageBitmap?): Color? {
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.readPixels(pixels)
    return forkSongAccent(listOf(pixels), step = 16)
}

/** The read itself, over every [step]th pixel of each of [images] (ARGB) as one picture. */
private fun forkSongAccent(images: List<IntArray>, step: Int): Color? {
    val cells = HashMap<Int, IntArray>()
    for (pixels in images) for (index in pixels.indices step step) {
        val pixel = pixels[index]
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        val key = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
        val cell = cells.getOrPut(key) { IntArray(4) }
        cell[0]++
        cell[1] += r
        cell[2] += g
        cell[3] += b
    }
    val dominant = cells.values.maxByOrNull { cell ->
        val size = cell[0].toFloat()
        val r = cell[1] / size / 255f
        val g = cell[2] / size / 255f
        val b = cell[3] / size / 255f
        size * (1f + 3f * (maxOf(r, g, b) - minOf(r, g, b)).coerceAtLeast(0f))
    } ?: return null
    val size = dominant[0].toFloat()
    return fitAccent(dominant[1] / size / 255f, dominant[2] / size / 255f, dominant[3] / size / 255f)
}

/**
 * A cover colour made fit for our accent: the hue kept, the saturation floored so it still reads as
 * a colour, the brightness lifted so it carries black ink and stands out as a tint on the dark page.
 */
private fun fitAccent(red: Float, green: Float, blue: Float): Color {
    val high = maxOf(red, green, blue)
    val low = minOf(red, green, blue)
    val delta = high - low
    if (high <= 0f || delta / high < 0.12f) return Color.White
    val hue = when (high) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return Color.hsv(
        hue = if (hue < 0f) hue + 360f else hue,
        saturation = (delta / high).coerceIn(0.45f, 0.85f),
        value = high.coerceIn(0.88f, 1f),
    )
}

/** Appearance, after Font: Default, From the song, then the swatches. */
@Composable
internal fun ForkAccentRow() {
    val title = DesktopStrings["fork_accent_colour", "Accent colour"]
    val subtitle = DesktopStrings[
        "fork_accent_colour_subtitle",
        "Colours the liked heart, shuffle and repeat, the selected sidebar row and the main buttons",
    ]
    if (!settingsRowVisible(title, subtitle)) return
    val choice by ForkAccent.choice.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                colors = desktopChipColors(),
                selected = choice == ForkAccent.DEFAULT,
                onClick = { ForkAccent.setChoice(ForkAccent.DEFAULT) },
                label = { Text(DesktopStrings["fork_accent_default", "Default"]) },
            )
            FilterChip(
                colors = desktopChipColors(),
                selected = choice == ForkAccent.SONG,
                onClick = { ForkAccent.setChoice(ForkAccent.SONG) },
                label = { Text(DesktopStrings["fork_accent_song", "From the song"]) },
            )
            Spacer(Modifier.size(4.dp))
            ForkAccent.swatches.forEach { (key, _, colour) ->
                val selected = choice == key
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colour)
                        .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                        .clickable { ForkAccent.setChoice(key) },
                )
            }
        }
    }
}
