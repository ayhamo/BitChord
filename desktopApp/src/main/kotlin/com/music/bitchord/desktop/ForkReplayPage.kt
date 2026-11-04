package com.music.bitchord.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.replay.InitialTile
import com.music.bitchord.ui.replay.RankBadge
import com.music.bitchord.ui.replay.ReplayCreditCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fork: the phone's Replay page (`ReplayScreen.kt`) on the PC, in place of [DesktopReplayPage].
 *
 * What it adds to the PC's page, all as on the phone: the "Weekly / Monthly / Yearly / Lifetime"
 * picker (the week from [ForkReplayWeek]); the cards in the phone's order, each opening the stories
 * at its own card; "Play your Replay" under them; a Top genres chart after the albums
 * ([ForkArtistGenres]), or the "genres will appear" note while none are known yet; the phone's
 * habit lines; "Share my Replay" at the foot. The stories and the share sheet are drawn over the
 * whole window by [ForkReplayOverlay], hooked into the app's overlay slot, because the page itself
 * sits between the top bar and the player bar.
 *
 * The PC page's own pieces stay: the mesh behind it is the frame's (the top song's colours), the
 * rows open a song or an artist as before, and an album row opens nothing (the PC page takes no
 * album callback). The picker is held to 520 dp: the phone's four equal shares of a phone's width
 * would be a bar across a wide window.
 */
@Composable
internal fun ForkReplayPage(
    summary: DesktopReplaySummary,
    period: DesktopReplayPeriod,
    holder: String,
    onPeriodChange: (DesktopReplayPeriod) -> Unit,
    onPlaySong: (Song) -> Unit,
    onOpenArtist: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val genres = rememberForkReplayGenres(summary)
    val genresOn by ForkArtistGenres.enabled.collectAsState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item("heading") {
                Column(Modifier.padding(horizontal = GUTTER)) {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        ForkReplayText.t("replay", "Replay"),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.W800,
                        color = Color.White,
                    )
                    Text(
                        ForkReplayText.label(period),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(14.dp))
                    PeriodPicker(period, onPeriodChange)
                    Spacer(Modifier.height(18.dp))
                }
            }

            if (summary.isEmpty) {
                item("empty") { EmptyReplay(period) }
                return@LazyColumn
            }

            item("cards") {
                DesktopScrollableRow(gutter = GUTTER, spacing = 12.dp) {
                    items(ForkReplayText.cards(summary), key = { it.label }) { card ->
                        ReplayCreditCard(
                            label = card.label,
                            value = card.value,
                            detail = card.detail,
                            artworkUrl = card.artworkUrl,
                            holder = holder,
                            memberSince = summary.memberSince(),
                            onClick = { ForkReplayUi.openStory(card.page) },
                            modifier = Modifier.width(300.dp),
                        )
                    }
                }
            }
            item("open") {
                ActionRow(Icons.Rounded.PlayArrow, ForkReplayText.t("play_your_replay", "Play your Replay")) {
                    ForkReplayUi.openStory(ForkStoryPage.INTRO)
                }
            }

            chart("songs", ForkReplayText.t("top_songs", "Top songs"), summary.songRows(CHART_LENGTH)) { index ->
                summary.songs.getOrNull(index)?.let { onPlaySong(it.song) }
            }
            chart("artists", ForkReplayText.t("top_artists", "Top artists"), summary.artistRows(CHART_LENGTH), circular = true) { index ->
                summary.artists.getOrNull(index)?.let { onOpenArtist(it.title) }
            }
            chart("albums", ForkReplayText.t("top_albums", "Top albums"), summary.albumRows(CHART_LENGTH)) {}
            if (genres.isNotEmpty()) {
                chart("genres", ForkReplayText.t("top_genres", "Top genres"), ForkReplayText.genreRows(genres, CHART_LENGTH)) {}
            } else if (genresOn && ForkArtistGenres.available) {
                item("genres-pending") {
                    Note(
                        ForkReplayText.t("genres_pending", "Genres will appear as your listening history grows"),
                        Modifier.padding(horizontal = GUTTER),
                    )
                }
            }

            item("habits") { Habits(summary) }
            item("share") {
                ActionRow(Icons.Rounded.IosShare, ForkReplayText.t("share_my_replay", "Share my Replay")) {
                    ForkReplayUi.openShare(null)
                }
            }
            item("tail") { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/**
 * Which of the Replay's two full-window layers is up: the stories (opened at a card) and the share
 * sheet (for a card, or for the whole Replay). App-wide rather than the page's own, because the
 * page and the overlay that draws them are composed in different places.
 */
internal object ForkReplayUi {
    var story by mutableStateOf<ForkStoryPage?>(null)
        private set
    var share by mutableStateOf<ForkShareRequest?>(null)
        private set

    fun openStory(page: ForkStoryPage) {
        story = page
    }

    fun openShare(page: ForkStoryPage?) {
        share = ForkShareRequest(page)
    }

    fun closeStory() {
        story = null
    }

    fun closeShare() {
        share = null
    }
}

/** A share sheet's subject: a story card, or null for the whole Replay. */
internal data class ForkShareRequest(val page: ForkStoryPage?)

/**
 * The stories and the share sheet over the whole window, hooked into the app's overlay slot.
 * Shown only while the Replay page is ([visible]): leaving the page puts both away.
 */
@Composable
internal fun ForkReplayOverlay(summary: DesktopReplaySummary, holder: String, visible: Boolean) {
    LaunchedEffect(visible) {
        if (!visible) {
            ForkReplayUi.closeStory()
            ForkReplayUi.closeShare()
        }
    }
    if (!visible) return
    val story = ForkReplayUi.story
    val share = ForkReplayUi.share
    if (story == null && share == null) return
    val genres = rememberForkReplayGenres(summary)
    if (story != null && !summary.isEmpty) {
        ForkReplayStories(
            summary = summary,
            genres = genres,
            start = story,
            onClose = ForkReplayUi::closeStory,
            onShare = { page -> ForkReplayUi.openShare(page) },
            paused = share != null,
        )
    }
    if (share != null) {
        // Esc puts the sheet away (the stories under it keep their own keys for when it has gone).
        val focus = remember { FocusRequester() }
        LaunchedEffect(share) { runCatching { focus.requestFocus() } }
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Escape) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) ForkReplayUi.closeShare()
                    true
                },
        ) {
            ForkReplayShareSheet(summary, genres, holder, share.page, onDismiss = ForkReplayUi::closeShare)
        }
    }
}

/** The genre chart for [summary], recharted whenever a lookup lands or the switch moves. */
@Composable
internal fun rememberForkReplayGenres(summary: DesktopReplaySummary): List<DesktopRankedEntry> {
    val revision by ForkArtistGenres.revision.collectAsState()
    val genres by produceState(emptyList<DesktopRankedEntry>(), summary, revision) {
        value = withContext(Dispatchers.IO) { ForkArtistGenres.chart(summary.artists) }
    }
    return genres
}

// ── Pieces (the phone's ReplayScreen) ───────────────────────────────────────

/** The four stretches, as four equal shares of the bar (phone `PeriodPicker`). */
@Composable
private fun PeriodPicker(selected: DesktopReplayPeriod, onSelect: (DesktopReplayPeriod) -> Unit) {
    Row(
        Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DesktopReplayPeriod.entries.forEach { period ->
            val active = period == selected
            val background by animateColorAsState(
                if (active) Color.White.copy(alpha = 0.92f) else Color.Transparent,
                tween(160),
                label = "forkPeriodChip",
            )
            Text(
                ForkReplayText.tab(period),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W700,
                color = if (active) Color.Black else Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .clickable { onSelect(period) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

/** "Play your Replay" and "Share my Replay", drawn alike (phone `ReplayActionRow`). */
@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = GUTTER, vertical = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
    }
}

private fun LazyListScope.chart(
    key: String,
    title: String,
    rows: List<DesktopReplayRow>,
    circular: Boolean = false,
    onClick: (Int) -> Unit,
) {
    if (rows.isEmpty()) return
    item("$key-title") {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = Color.White,
            modifier = Modifier.padding(start = GUTTER, end = GUTTER, top = 8.dp, bottom = 10.dp),
        )
    }
    items(rows, key = { "$key-${it.key}" }) { row -> ChartRow(row, circular) { onClick(row.rank - 1) } }
    item("$key-gap") { Spacer(Modifier.height(20.dp)) }
}

@Composable
private fun ChartRow(row: DesktopReplayRow, circular: Boolean, onClick: () -> Unit) {
    val shape = if (circular) CircleShape else RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = GUTTER, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankBadge(row.rank, ForkReplayAccent)
        if (!row.artworkUrl.isNullOrBlank()) {
            DesktopArtwork(row.artworkUrl, Modifier.size(48.dp).clip(shape), px = ROW_ART_PX)
        } else {
            InitialTile(row.title, 48.dp, shape)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.W600, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(row.subtitle, ForkReplayText.listening(row.ms)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.plays > 0) {
            Text(row.plays.toString(), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.45f))
        }
    }
}

@Composable
private fun Habits(summary: DesktopReplaySummary) {
    Column(Modifier.padding(horizontal = GUTTER)) {
        Text(
            ForkReplayText.t("listening_shape", "Your listening pattern"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(ForkReplayText.t("songs", "Songs"), summary.distinctSongs.toString(), Modifier.weight(1f))
            StatTile(ForkReplayText.t("artists", "Artists"), summary.distinctArtists.toString(), Modifier.weight(1f))
            StatTile(ForkReplayText.t("albums", "Albums"), summary.distinctAlbums.toString(), Modifier.weight(1f))
        }
        summary.peakHour?.let {
            Spacer(Modifier.height(10.dp))
            Note(DesktopStrings.format("listen_most_around", ForkReplayText.hour(it), fallback = "You listen most around %1\$s."))
        }
        summary.busiestDay?.let {
            Spacer(Modifier.height(6.dp))
            Note(
                DesktopStrings.format(
                    "biggest_day_note",
                    ForkReplayText.day(it),
                    ForkReplayText.listening(summary.busiestDayMs),
                    fallback = "Your biggest day was %1\$s with %2\$s of listening.",
                ),
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.09f)).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.W800, color = Color.White)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f), letterSpacing = 1.sp)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f), modifier = modifier)
}

@Composable
private fun EmptyReplay(period: DesktopReplayPeriod) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            ForkReplayText.t("not_enough_listening", "Not enough listening yet"),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when (period) {
                DesktopReplayPeriod.THIS_WEEK -> ForkReplayText.t("not_enough_this_week", "Play a few more songs this week to unlock your Replay.")
                DesktopReplayPeriod.THIS_MONTH -> ForkReplayText.t("not_enough_this_month", "Play a few more songs this month to unlock your Replay.")
                else -> ForkReplayText.t("replay_empty_description", "Listen a little more and BitChord will turn your history into a Replay.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/** A top five is a result; a top ten is a list (phone `CHART_LENGTH`). */
private const val CHART_LENGTH = 5
private const val ROW_ART_PX = 120
private val GUTTER = DesktopPageGutter
