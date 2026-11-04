package com.music.bitchord.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.logo_mark
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MeshPalette
import com.music.bitchord.ui.player.rememberArtworkColors
import com.music.bitchord.ui.replay.InitialTile
import com.music.bitchord.ui.replay.RankBadge
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Fork: the phone's Replay stories (`ReplayStories.kt`) on the PC — the run of full-screen cards,
 * one fact each, eight of them (an empty album or genre chart has no card), every card the same
 * three bands: the sentence with its number in bold, the thing it is about, the share button.
 *
 * ## How it moves on a PC
 *
 * The phone's tap zones become clicks: the left 32% of the card goes back, the rest goes on; a
 * press held past the long-press timeout stops the clock, as a held finger does. The arrow keys
 * (and Space) step, Esc closes. Each card holds 14 s; the last one runs out its clock and closes,
 * and a step forward on it closes too, as on the phone. The clock stops while the share dialog is
 * up ([paused]), so the card being sent is the card on screen.
 *
 * No pager: the phone's pager is there for the swipe, which a mouse doesn't make, and its
 * mid-scroll cancellation is the source of the two bugs the phone's comments record. A plain index
 * with a sliding [AnimatedContent] has neither: every step is a whole page.
 *
 * ## The frame
 *
 * The card is a fixed 9:16 canvas fitted into the window and letterboxed in black (phone
 * `StoryFrame`), so a wide window shows the same card a phone does, and the picture the share
 * button draws ([ForkReplayPoster]) is that shape too.
 */
@Composable
internal fun ForkReplayStories(
    summary: DesktopReplaySummary,
    genres: List<DesktopRankedEntry>,
    start: ForkStoryPage,
    onClose: () -> Unit,
    onShare: (ForkStoryPage) -> Unit,
    paused: Boolean,
) {
    val pages = remember(summary, genres) { ForkReplayText.pages(summary, genres) }
    var current by remember { mutableIntStateOf(pages.indexOf(start).coerceAtLeast(0)) }
    var forward by remember { mutableStateOf(true) }
    var held by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    if (current > pages.lastIndex) current = pages.lastIndex.coerceAtLeast(0)

    fun goTo(target: Int) {
        val next = target.coerceIn(0, pages.lastIndex)
        forward = next >= current
        scope.launch { progress.snapTo(0f) }
        current = next
    }

    fun step(ahead: Boolean) {
        if (ahead && current == pages.lastIndex) {
            onClose()
            return
        }
        goTo(current + if (ahead) 1 else -1)
    }

    // Taken back when the share dialog closes, so the keys keep working after it.
    LaunchedEffect(paused) { if (!paused) runCatching { focus.requestFocus() } }
    LaunchedEffect(current) { progress.snapTo(0f) }
    LaunchedEffect(current, held, paused) {
        if (held || paused) return@LaunchedEffect
        // Resumed from where a hold left it, so letting go doesn't hand back a nearly finished card.
        val remaining = ((1f - progress.value) * PAGE_MILLIS).toInt().coerceAtLeast(0)
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (current == pages.lastIndex) onClose() else goTo(current + 1)
    }

    val page = pages.getOrElse(current) { ForkStoryPage.INTRO }
    val artwork = ForkReplayText.artwork(summary, page)
    val palette = rememberArtworkColors(artwork).rotated(ForkReplayText.hue(page))
    val label = ForkReplayText.label(summary.period)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallows clicks on the letterbox, so nothing behind the story is reached through it.
            .pointerInput(Unit) { detectTapGestures { } }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (paused) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.Spacebar -> {
                        if (event.type == KeyEventType.KeyDown) step(true)
                        true
                    }
                    Key.DirectionLeft -> {
                        if (event.type == KeyEventType.KeyDown) step(false)
                        true
                    }
                    Key.Escape -> {
                        // On the release: the app's own Esc handler reads releases, and one that
                        // reached it after the story had gone would close something else too.
                        if (event.type == KeyEventType.KeyUp) onClose()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        StoryFrame {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF17171A))
                    .pointerInput(pages) {
                        detectTapGestures(
                            onPress = {
                                held = true
                                tryAwaitRelease()
                                held = false
                            },
                            // Supplied, and empty: without it every press-and-release is a tap, and
                            // holding to pause would turn the page on release (phone note).
                            onLongPress = {},
                            onTap = { offset -> step(offset.x >= size.width * BACK_ZONE) },
                        )
                    },
            ) {
                MeshGradientBackground(palette = palette, trackKey = page.name, animated = false)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.45f),
                            0.35f to Color.Black.copy(alpha = 0.10f),
                            1.0f to Color.Black.copy(alpha = 0.34f),
                        ),
                    ),
                )
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val sign = if (forward) 1 else -1
                        slideInHorizontally(tween(260)) { it * sign } togetherWith slideOutHorizontally(tween(260)) { -it * sign }
                    },
                    label = "forkReplayStory",
                    modifier = Modifier.fillMaxSize(),
                ) { shown ->
                    StoryPage(shown, summary, genres, onShare)
                }
                StoryChrome(label, pages.size, current, progress.value, onClose)
            }
        }
    }
}

/** Holds a story to 9:16 whatever shape the window is: fitted, letterboxed in black. */
@Composable
private fun StoryFrame(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitsByWidth = maxWidth / maxHeight < STORY_ASPECT
        Box(
            Modifier
                .then(if (fitsByWidth) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                .aspectRatio(STORY_ASPECT),
        ) { content() }
    }
}

/** The cross, the progress segments and "Replay 26 … BitChord": fixed while the cards slide. */
@Composable
private fun StoryChrome(label: String, count: Int, current: Int, progress: Float, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = ForkReplayText.t("close_replay", "Close Replay"),
                tint = Color.White,
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(count) { index ->
                Segment(
                    fraction = when {
                        index < current -> 1f
                        index > current -> 0f
                        else -> progress
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ForkReplayText.title(label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(Res.drawable.logo_mark),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(width = 26.dp, height = 17.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text("BitChord", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun Segment(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.28f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(2.5.dp).background(Color.White))
    }
}

// ── One card ────────────────────────────────────────────────────────────────

@Composable
private fun StoryPage(
    page: ForkStoryPage,
    summary: DesktopReplaySummary,
    genres: List<DesktopRankedEntry>,
    onShare: (ForkStoryPage) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = CHROME_HEIGHT, bottom = 18.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                val headline = ForkReplayText.headline(summary, page)
                when (page) {
                    ForkStoryPage.INTRO -> Intro(summary, headline)
                    ForkStoryPage.MINUTES -> Minutes(summary, headline)
                    ForkStoryPage.SONGS -> Leaderboard(headline, summary.songRows(STORY_ROWS), circular = false)
                    ForkStoryPage.ARTISTS -> Leaderboard(headline, summary.artistRows(STORY_ROWS), circular = true)
                    ForkStoryPage.ALBUMS -> Leaderboard(headline, summary.albumRows(STORY_ROWS), circular = false)
                    ForkStoryPage.GENRES -> Genres(ForkReplayText.genreRows(genres, STORY_ROWS), headline)
                    ForkStoryPage.HABITS -> Habits(summary, headline)
                    ForkStoryPage.SUMMARY -> Recap(summary, genres, headline)
                }
            }
        }
        // On every card: it sends this card, the one that prompted the click (phone note).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable { onShare(page) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.IosShare, ForkReplayText.t("share_my_replay", "Share my Replay"), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The sentence at the top of every card, the number in the heavier weight. */
@Composable
private fun Headline(parts: List<ForkHeadlineRun>) {
    Text(
        buildAnnotatedString {
            parts.forEach { run ->
                withStyle(
                    SpanStyle(
                        fontWeight = if (run.bold) FontWeight.W800 else FontWeight.W600,
                        color = if (run.bold) Color.White else Color.White.copy(alpha = 0.62f),
                    ),
                ) { append(run.text) }
            }
        },
        style = MaterialTheme.typography.displayLarge,
        fontSize = 30.sp,
        lineHeight = 37.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.Intro(summary: DesktopReplaySummary, headline: List<ForkHeadlineRun>) {
    Headline(headline)
    Spacer(Modifier.weight(1f))
    ArtworkCollage(summary)
    Spacer(Modifier.weight(1f))
    Text(
        ForkReplayText.t("fork_replay_private_counting", "Built privately from your listening history on this device."),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.5f),
    )
}

@Composable
private fun ColumnScope.Minutes(summary: DesktopReplaySummary, headline: List<ForkHeadlineRun>) {
    Headline(headline)
    Spacer(Modifier.weight(1f))
    ArtworkCollage(summary)
    Spacer(Modifier.weight(1f))
    Text(ForkReplayText.minutesLine(summary), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.62f))
}

/** A number one with its runners-up: the same card for songs, artists and albums. */
@Composable
private fun ColumnScope.Leaderboard(headline: List<ForkHeadlineRun>, rows: List<DesktopReplayRow>, circular: Boolean) {
    val lead = rows.firstOrNull() ?: return
    val shape = if (circular) CircleShape else RoundedCornerShape(10.dp)
    Headline(headline)
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Cover(lead.artworkUrl, lead.title, 116.dp, shape, HEADER_ART_PX, elevated = true)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                lead.title,
                style = MaterialTheme.typography.headlineMedium,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.W800,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            lead.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${ForkReplayText.listening(lead.ms)} · ${ForkReplayText.plays(lead.plays)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
    Spacer(Modifier.weight(1f))
    rows.drop(1).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RankBadge(row.rank, ForkReplayAccent)
            Cover(row.artworkUrl, row.title, 36.dp, shape, ROW_ART_PX)
            Spacer(Modifier.width(12.dp))
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.W600,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(ForkReplayText.listening(row.ms), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun ColumnScope.Genres(rows: List<DesktopReplayRow>, headline: List<ForkHeadlineRun>) {
    val lead = rows.firstOrNull() ?: return
    Headline(headline)
    Spacer(Modifier.weight(1f))
    Text(
        lead.title,
        style = MaterialTheme.typography.displayLarge,
        fontSize = 60.sp,
        lineHeight = 62.sp,
        fontWeight = FontWeight.W800,
        color = Color.White,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(ForkReplayText.listening(lead.ms), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.6f))
    Spacer(Modifier.height(22.dp))
    rows.drop(1).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            RankBadge(row.rank, ForkReplayAccent)
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W600,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
            Text(ForkReplayText.listening(row.ms), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.45f))
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ColumnScope.Habits(summary: DesktopReplaySummary, headline: List<ForkHeadlineRun>) {
    Headline(headline)
    Spacer(Modifier.weight(1f))
    if (summary.distinctAlbums > 0) {
        BigStat(ForkReplayText.grouped(summary.distinctAlbums.toLong()), ForkReplayText.t("different_albums", "Different albums"))
    }
    summary.busiestDay?.let {
        BigStat(
            ForkReplayText.day(it),
            DesktopStrings.format("your_biggest_day", ForkReplayText.listening(summary.busiestDayMs), fallback = "Your biggest day: %1\$s"),
        )
    }
    summary.peakHour?.let {
        BigStat(ForkReplayText.hour(it), ForkReplayText.t("when_you_listen_the_most", "When you listen the most"))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BigStat(value: String, label: String) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(value, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.W800, color = Color.White)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.58f))
    }
}

@Composable
private fun ColumnScope.Recap(summary: DesktopReplaySummary, genres: List<DesktopRankedEntry>, headline: List<ForkHeadlineRun>) {
    Headline(headline)
    Spacer(Modifier.height(20.dp))
    RecapLine(ForkReplayText.t("minutes", "Minutes"), ForkReplayText.minutes(summary.totalMs))
    summary.songs.firstOrNull()?.let { RecapLine(ForkReplayText.t("top_song", "Top song"), it.song.title) }
    summary.artists.firstOrNull()?.let { RecapLine(ForkReplayText.t("top_artist", "Top artist"), it.title) }
    summary.albums.firstOrNull()?.let { RecapLine(ForkReplayText.t("top_album", "Top album"), it.title) }
    genres.firstOrNull()?.let { RecapLine(ForkReplayText.t("top_genre", "Top genre"), it.title) }
    Spacer(Modifier.weight(1f))
    Text(
        ForkReplayText.t("fork_tap_share_for_picture", "Tap Share to create a picture."),
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.55f),
    )
}

@Composable
private fun RecapLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(96.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W700,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────────

@Composable
private fun Cover(
    url: String?,
    fallbackText: String,
    size: Dp,
    shape: Shape,
    px: Int,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    val base = modifier
        .size(size)
        .let { if (elevated) it.shadow(18.dp, shape, clip = false) else it }
        .clip(shape)
    when {
        !url.isNullOrBlank() -> DesktopArtwork(url, base, px = px)
        fallbackText.isNotBlank() -> Box(base) { InitialTile(fallbackText, size, shape) }
        else -> Box(base.background(Color.White.copy(alpha = 0.10f)))
    }
}

/** The hand-placed scatter of covers (squares) and faces (circles) on the first two cards. */
@Composable
private fun ArtworkCollage(summary: DesktopReplaySummary, modifier: Modifier = Modifier) {
    val covers = remember(summary) { summary.songs.mapNotNull { it.song.thumbnailUrl?.takeIf(String::isNotBlank) }.distinct().take(3) }
    val faces = remember(summary) {
        summary.artists.mapNotNull { it.artworkUrl?.takeIf(String::isNotBlank) }.distinct().filterNot { it in covers }.take(3)
    }
    if (covers.isEmpty() && faces.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth().height(300.dp)) {
        val w = maxWidth
        val h = maxHeight
        val squares = listOf(Triple(0.26f to 0.34f, 168.dp, -3f), Triple(0.05f to 0.10f, 88.dp, -9f), Triple(0.62f to 0.04f, 72.dp, 7f))
        val circles = listOf(Triple(0.02f to 0.62f, 62.dp, 0f), Triple(0.70f to 0.34f, 76.dp, 0f), Triple(0.44f to 0.78f, 66.dp, 0f))
        covers.forEachIndexed { index, url ->
            val (position, size, angle) = squares[index]
            Cover(
                url, "", size, RoundedCornerShape(4.dp), CARD_ART_PX,
                Modifier.offset(x = w * position.first, y = h * position.second).rotate(angle),
                elevated = true,
            )
        }
        faces.forEachIndexed { index, url ->
            val (position, size, _) = circles[index]
            Cover(url, "", size, CircleShape, CARD_ART_PX, Modifier.offset(x = w * position.first, y = h * position.second), elevated = true)
        }
    }
}

/** [this] with every colour turned [degrees] round the wheel, tone untouched (phone `rotated`). */
private fun MeshPalette.rotated(degrees: Float): MeshPalette =
    if (degrees == 0f) this else MeshPalette(colors.map { Color(ForkReplayText.rotated(it.toArgb(), degrees)) })

/** The phone's `AccentRed`, which the Replay's rank badges use. */
internal val ForkReplayAccent = Color(0xFFFA2D48)

/** Room the chrome takes above a card's content (phone `CHROME_HEIGHT`). */
private val CHROME_HEIGHT = 96.dp
private const val STORY_ROWS = 5
private const val STORY_ASPECT = 9f / 16f
private const val PAGE_MILLIS = 14_000f
private const val BACK_ZONE = 0.32f
