package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.ForkAppleArtistArt
import com.music.bitchord.data.ForkAppleArtistArtRepository
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeArtistInfo
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Fork: Apple Music's artist header on the PC's artist page, as upstream's web player and the
 * phone draw it (web `pages/ArtistPage.tsx`, `data/apple/artistArt.ts`; phone `DetailScreen`'s
 * `ArtistHeader`): Apple's hero photograph, the artist's title logo in place of the name when
 * Apple has one, the page tinted with Apple's colour and the Play button filled with Apple's.
 *
 * The art is [ForkAppleArtistArtRepository]'s (shared): found by the artist's name on
 * itunes.apple.com / music.apple.com only, confirmed by a title both catalogues share, cached per
 * page. A Navidrome artist's page asks the same way with the server's songs as the evidence (the
 * server itself is never asked for this). Until the art arrives, and on any miss, today's banner
 * is drawn; with the "Artist Art" switch off nothing is asked.
 */
internal object ForkArtistHeader {

    const val TITLE = "Artist Art"
    const val SUBTITLE = "Artist pages take Apple Music’s photo, logo and colour"

    private const val KEY = "fork_apple_artist_art"

    private val persistence by lazy { DesktopPersistence() }

    // On by default, as on the web and the phone (which has no switch: always on).
    private val _enabled = MutableStateFlow(persistence.boolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        _enabled.value = on
        persistence.saveBoolean(KEY, on)
    }

    /** Share of Apple's colour mixed into the page: enough to read as Apple's, never so light white text fades. */
    private const val TINT = 0.35f

    /** The page colour Apple's `bgColor` makes, over the app's own surface. */
    fun pageTint(art: ForkAppleArtistArt): Color = lerp(DesktopSurface, Color(art.background), TINT)

    /** Apple's Play fill, and a glyph that reads on it: Apple's page colour, else black or white. */
    fun playColors(art: ForkAppleArtistArt): Pair<Color, Color> {
        val fill = art.keyColor?.let(::Color) ?: Color.White
        val page = Color(art.background)
        val glyph = when {
            abs(fill.luminance() - page.luminance()) >= 0.3f -> page
            fill.luminance() > 0.5f -> Color.Black
            else -> Color.White
        }
        return fill to glyph
    }
}

/** The "Artist Art" switch, in Settings beside the other artwork switches. */
@Composable
internal fun ForkArtistArtSwitch() {
    val on by ForkArtistHeader.enabled.collectAsState()
    SettingsToggle(ForkArtistHeader.TITLE, ForkArtistHeader.SUBTITLE, on, ForkArtistHeader::set)
}

/**
 * Apple's art for the artist [name] on page [key], or null (switch off, not looked up yet, or a
 * miss). [catalogue] is the page's own titles; [revision] changes when they do (a page whose songs
 * arrive later asks again then: with no titles nothing is asked or remembered).
 */
@Composable
internal fun rememberForkAppleArt(key: String, name: String, revision: Any?, catalogue: () -> Collection<String>): ForkAppleArtistArt? {
    val on by ForkArtistHeader.enabled.collectAsState()
    val titles by rememberUpdatedState(catalogue)
    // Grouped by the page: produceState keeps its value across a key change, and Back from one
    // Navidrome artist to another (the collection page stays composed) showed the other's art.
    val art = androidx.compose.runtime.key(key) {
        produceState(ForkAppleArtistArtRepository.cached(key), key, name, on, revision) {
            if (!on) return@produceState
            value = ForkAppleArtistArtRepository.cached(key)
                ?: ForkAppleArtistArtRepository.artFor(key, name) { titles() }
                ?: value
        }.value
    }
    return if (on) art else null
}

/** On the artist page's root: the page in Apple's colour. */
internal fun Modifier.forkAppleTint(art: ForkAppleArtistArt?): Modifier =
    if (art == null) this else background(ForkArtistHeader.pageTint(art))

/** An image with nothing drawn until it has loaded, so what is under it shows meanwhile. */
@Composable
private fun ForkLoadedImage(url: String?, modifier: Modifier, contentScale: ContentScale, contentDescription: String? = null): Boolean {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) { DesktopArtworkCache.load(url) }
    }
    bitmap?.let { Image(BitmapPainter(it), contentDescription, modifier, contentScale = contentScale) }
    return bitmap != null
}

/**
 * The banner with Apple's art: the photograph over YouTube's (which shows until it loads), melting
 * into the page's tint at the foot, and the title logo, or the name until the logo has drawn.
 */
@Composable
internal fun ForkAppleArtistBanner(
    art: ForkAppleArtistArt,
    name: String,
    height: Dp,
    fallbackUrl: String?,
    modifier: Modifier = Modifier,
) {
    val tint = ForkArtistHeader.pageTint(art)
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val logoMaxWidth = maxWidth * 0.6f
        DesktopArtwork(fallbackUrl, Modifier.matchParentSize(), px = HEADER_ART_PX)
        ForkLoadedImage(art.heroUrl, Modifier.matchParentSize(), ContentScale.Crop)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.25f),
                    0.45f to Color.Transparent,
                    0.8f to tint.copy(alpha = 0.7f),
                    1f to tint,
                ),
            ),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = DesktopPageGutter, end = DesktopPageGutter, bottom = 18.dp),
        ) {
            // Capped in height as well as width, as the phone's: a logo set on two or three lines is
            // nearly square and by width alone would swallow the photograph.
            val logoShown = art.logoUrl?.let { logo ->
                ForkLoadedImage(
                    logo,
                    Modifier
                        .widthIn(max = logoMaxWidth)
                        .heightIn(max = height * 0.3f)
                        .aspectRatio(art.logoAspect.coerceIn(0.4f, 6f)),
                    ContentScale.Fit,
                    contentDescription = name,
                )
            } ?: false
            if (!logoShown) {
                Text(
                    name,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The artist page's Play pill, filled with Apple's colour when the page has Apple's art. */
@Composable
internal fun ForkArtistPlayPill(art: ForkAppleArtistArt?, label: String, onClick: () -> Unit) {
    if (art == null) {
        DesktopPlayPill(label = label, onClick = onClick)
        return
    }
    val (fill, glyph) = ForkArtistHeader.playColors(art)
    Row(
        Modifier
            .height(50.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BitChordIcons.Play, contentDescription = null, tint = glyph, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = glyph)
    }
}

/**
 * A Navidrome artist's page (a collection page): Apple's banner above its header, once Apple has
 * answered for the artist's name with the server's song titles as evidence. Nothing on any other
 * page, and nothing on a miss.
 */
internal fun LazyListScope.forkNavidromeArtistBanner(browseId: String, songs: List<Song>) {
    val name = NavidromeArtistInfo.nameOf(browseId) ?: return
    item(key = "fork-apple-artist-banner") {
        val art = rememberForkAppleArt(browseId, name, songs.size) { songs.map(Song::title) } ?: return@item
        ForkAppleArtistBanner(
            art,
            name,
            height = 300.dp,
            fallbackUrl = null,
            modifier = Modifier.padding(bottom = 28.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
}
