package com.music.bitchord.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.ArtistCredit
import com.music.bitchord.data.model.CreditSegment
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.creditSegments
import com.music.bitchord.data.model.isExactArtistMatch
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.ui.player.artistCredits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fork: the top bar's now-playing block as upstream's web player draws it (`shell/Lcd.tsx`,
 * `shell/nowPlayingLinks.ts`, `shell/useScrubber.ts`). The title opens its album, each credited
 * artist their own page, and the thin line under them is a scrubber: hovering shows the elapsed
 * and remaining time over its ends (the names fade back so the times read), pressing or dragging
 * seeks on release. Anywhere else in the block still opens Now Playing.
 *
 * Where a name goes is [ForkTopBarRoutes]; a Navidrome song's links are the library's own album
 * and artist pages (which `openAlbum` / `openArtist` route to the Navidrome page through
 * `ForkNavidrome.ownsPage`), never a YouTube lookup.
 */
internal class ForkTopBarLinks(internal val scope: CoroutineScope) {
    internal var durationMs: () -> Long = { 0L }
    internal var seekFraction: (Float) -> Unit = {}
    internal var openAlbum: (String) -> Unit = {}
    internal var openArtist: (String, String) -> Unit = { _, _ -> }
    internal var search: (String) -> Unit = {}

    /** True while the pointer is on the scrubber or dragging it: the names fade back. */
    internal val scrubbing: MutableState<Boolean> = mutableStateOf(false)

    /** One lookup at a time: a second click while the first is out does nothing. */
    private val busy = AtomicBoolean(false)

    fun go(target: ForkLinkTarget) {
        when (target) {
            ForkLinkTarget.None -> Unit
            is ForkLinkTarget.Album -> openAlbum(target.browseId)
            is ForkLinkTarget.Artist -> openArtist(target.browseId, target.name)
            is ForkLinkTarget.Search -> search(target.query)
            is ForkLinkTarget.FindAlbum -> lookUp {
                val album = DesktopSearchClient.trackLinks(target.videoId).getOrNull()?.albumId?.takeIf(String::isNotBlank)
                if (album != null) openAlbum(album) else search(target.query)
            }
            is ForkLinkTarget.FindArtist -> lookUp {
                // As the phone resolves a credit: the channel the track's own watch entry links
                // under that exact name, else the artist search's answer to exactly that name.
                // Nothing near enough is ever opened; with neither, the search page opens.
                val linked = target.videoId?.let { DesktopSearchClient.trackLinks(it).getOrNull() }
                    ?.artists?.firstOrNull { !it.browseId.isNullOrBlank() && isExactArtistMatch(it.name, target.name) }
                    ?.browseId
                val id = linked ?: YtMusicRepository.findArtistPageId(target.name).getOrNull()
                if (id != null) openArtist(id, target.name) else search(target.name)
            }
        }
    }

    private fun lookUp(block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                block()
            } finally {
                busy.set(false)
            }
        }
    }
}

/** Where a click in the top bar's track info goes. */
internal sealed interface ForkLinkTarget {
    data object None : ForkLinkTarget
    data class Album(val browseId: String) : ForkLinkTarget
    data class Artist(val browseId: String, val name: String) : ForkLinkTarget
    /** A YouTube track whose row didn't carry its album: its watch entry is asked. */
    data class FindAlbum(val videoId: String, val query: String) : ForkLinkTarget
    /** A credit with no channel: found by its exact name (and the track's watch entry, when it has one). */
    data class FindArtist(val videoId: String?, val name: String) : ForkLinkTarget
    data class Search(val query: String) : ForkLinkTarget
}

internal object ForkTopBarRoutes {

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    private fun isRadio(song: Song) = NavidromeRadio.isRadio(song.videoId)
    private fun isServer(song: Song) = NavidromeSettings.isNavidromeId(song.videoId)
    private fun isYouTube(song: Song) = YOUTUBE_ID.matches(song.videoId)
    private fun query(song: Song) = listOf(song.title, song.artist).filter(String::isNotBlank).joinToString(" ")

    /** Where the title goes: the song's album. */
    fun album(song: Song): ForkLinkTarget {
        if (isRadio(song) || song.title.isBlank()) return ForkLinkTarget.None
        song.albumId?.takeIf(String::isNotBlank)?.let { return ForkLinkTarget.Album(it) }
        if (isServer(song)) {
            return song.albumName?.takeIf(String::isNotBlank)
                ?.let { ForkLinkTarget.Album(NavidromeSettings.albumPageId(it)) }
                ?: ForkLinkTarget.Search(query(song))
        }
        return if (isYouTube(song)) ForkLinkTarget.FindAlbum(song.videoId, query(song)) else ForkLinkTarget.Search(query(song))
    }

    /** Where a credited name goes. */
    fun artist(song: Song, credit: ArtistCredit): ForkLinkTarget {
        if (isRadio(song) || credit.name.isBlank()) return ForkLinkTarget.None
        credit.browseId?.takeIf(String::isNotBlank)?.let { return ForkLinkTarget.Artist(it, credit.name) }
        // The library's page for that one name: the server's songs credited to it.
        if (isServer(song)) return ForkLinkTarget.Artist(NavidromeSettings.artistPageId(credit.name), credit.name)
        return ForkLinkTarget.FindArtist(song.videoId.takeIf { isYouTube(song) }, credit.name)
    }

    /** The byline cut into its names and the text between them, as the player's credit line draws it. */
    fun credits(song: Song): List<CreditSegment> {
        if (isRadio(song)) return listOf(CreditSegment(song.artist))
        return creditSegments(song.artist, artistCredits(song.artist, song.artists, song.artistId))
    }

    /** `m:ss`, or `h:mm:ss` past an hour. */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}

@Composable
internal fun rememberForkTopBarLinks(
    durationMs: () -> Long,
    seekFraction: (Float) -> Unit,
    openAlbum: (String) -> Unit,
    openArtist: (String, String) -> Unit,
    search: (String) -> Unit,
): ForkTopBarLinks {
    val scope = rememberCoroutineScope()
    val links = remember(scope) { ForkTopBarLinks(scope) }
    links.durationMs = durationMs
    links.seekFraction = seekFraction
    links.openAlbum = openAlbum
    links.openArtist = openArtist
    links.search = search
    return links
}

private val linkStyles = TextLinkStyles(hoveredStyle = SpanStyle(textDecoration = TextDecoration.Underline))

/** The title for the top bar's `Text`: a link to its album when there is somewhere to go. */
@Composable
internal fun forkTopBarTitle(song: Song, links: ForkTopBarLinks?): AnnotatedString = remember(song, links) {
    val target = if (links == null) ForkLinkTarget.None else ForkTopBarRoutes.album(song)
    if (target == ForkLinkTarget.None) {
        AnnotatedString(song.title)
    } else {
        buildAnnotatedString {
            withLink(LinkAnnotation.Clickable("album", linkStyles) { links?.go(target) }) { append(song.title) }
        }
    }
}

/** The byline for the top bar's `Text`, each credited name its own link. */
@Composable
internal fun forkTopBarCredits(song: Song, links: ForkTopBarLinks?): AnnotatedString = remember(song, links) {
    if (links == null) return@remember AnnotatedString(song.artist)
    buildAnnotatedString {
        ForkTopBarRoutes.credits(song).forEachIndexed { index, segment ->
            val target = segment.credit?.let { ForkTopBarRoutes.artist(song, it) } ?: ForkLinkTarget.None
            if (target == ForkLinkTarget.None) {
                append(segment.text)
            } else {
                withLink(LinkAnnotation.Clickable("artist-$index", linkStyles) { links.go(target) }) { append(segment.text) }
            }
        }
    }
}

/** On the names' column: they fade back while the scrubber shows its times. */
internal fun Modifier.forkDimWhileScrubbing(links: ForkTopBarLinks?): Modifier =
    if (links == null) this else graphicsLayer { alpha = if (links.scrubbing.value) 0.3f else 1f }

/**
 * Over the top bar's 2 dp progress line: a 9 dp strip that takes the pointer, thickens the line
 * while hovered or dragged, shows the elapsed and remaining time above its two ends, and seeks
 * where the press is released.
 */
@Composable
internal fun ForkTopBarScrubber(links: ForkTopBarLinks, progress: () -> Float, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val currentLinks by rememberUpdatedState(links)
    val active = hovered || dragging != null
    SideEffect { links.scrubbing.value = active }
    val fraction = { (dragging ?: progress()).coerceIn(0f, 1f) }
    Box(modifier.fillMaxWidth().height(24.dp)) {
        if (active) {
            val duration = links.durationMs()
            val at = (fraction() * duration).toLong()
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(ForkTopBarRoutes.clock(at), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text("-" + ForkTopBarRoutes.clock(duration - at), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(9.dp)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    fun at(x: Float) = if (size.width > 0) (x / size.width).coerceIn(0f, 1f) else 0f
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var last = at(down.position.x)
                        dragging = last
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            last = at(change.position.x)
                            dragging = last
                        }
                        currentLinks.seekFraction(last)
                        dragging = null
                    }
                }
                .drawBehind {
                    if (!active) return@drawBehind
                    val h = 4.dp.toPx()
                    val top = size.height - h
                    val radius = CornerRadius(h / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, top), Size(size.width, h), radius)
                    drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(0f, top), Size(size.width * fraction(), h), radius)
                },
        )
    }
}
