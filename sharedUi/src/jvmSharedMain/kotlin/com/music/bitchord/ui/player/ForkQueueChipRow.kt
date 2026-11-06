package com.music.bitchord.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ForkQueueChips

/**
 * Fork: YouTube Music's queue chips (All, Popular, Discover, Deep cuts, the
 * song's moods, decades and genres) as a row under the AutoPlay heading, on
 * the phone and the PC alike. A tap swaps AutoPlay's rows for that chip's
 * queue; see [ForkQueueChips] for where the chips come from and what a tap may
 * change. Hooked into [InlineQueue] with one line, inside its AutoPlay section.
 *
 * The item is always there while the section is, and draws nothing when the
 * chips don't belong to this queue ([ForkQueueChips.shows]: no chips, a
 * Navidrome song, a party, repeat-all, an AutoPlay tail from another station). Keeping one
 * stable key rather than adding and removing the item keeps the queue's rows
 * from jumping while the chips come and go.
 *
 * Drawn as the player's own capsules (the output and repeat pills: white at
 * 12 % on the artwork, fully round) with the picked chip filled white and
 * written black, as YouTube Music marks it. The chip being fetched dims until
 * its answer lands; a failed fetch just un-dims, the old chip still picked.
 */
internal fun LazyListScope.forkQueueChips(queue: List<Song>, currentIndex: Int, locked: Boolean) {
    item(key = "fork-queue-chips") {
        ForkQueueChipRow(
            queue = queue,
            currentIndex = currentIndex,
            locked = locked,
            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = ChipRowMotion),
        )
    }
}

private val ChipRowMotion = tween<IntOffset>(durationMillis = 200, easing = FastOutSlowInEasing)

@Composable
private fun ForkQueueChipRow(queue: List<Song>, currentIndex: Int, locked: Boolean, modifier: Modifier = Modifier) {
    val state by ForkQueueChips.state.collectAsStateWithLifecycle()
    val available by ForkQueueChips.available.collectAsStateWithLifecycle()
    val party by PlayerPlatform.host.party.collectAsStateWithLifecycle()
    val repeatAll by ForkQueueChips.repeatAll.collectAsStateWithLifecycle()
    val shown = remember(state, available, queue, currentIndex, party.inParty, repeatAll) {
        ForkQueueChips.shows(state, available, queue, currentIndex, party.inParty, repeatAll)
    }
    val chips = state
    if (!shown || chips == null) {
        Box(modifier)
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.chips.forEachIndexed { index, chip ->
            QueueChipPill(
                title = chip.title,
                selected = index == chips.selected,
                loading = index == chips.loading,
                enabled = !locked,
                onClick = { ForkQueueChips.select(index) },
            )
        }
    }
}

@Composable
private fun QueueChipPill(title: String, selected: Boolean, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
    val ink by animateColorAsState(if (selected) Color.Black else Color.White)
    Box(
        modifier = Modifier
            .height(32.dp)
            .alpha(if (loading) 0.5f else if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
