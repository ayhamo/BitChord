package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.playback.smart.ForkAutoDj
import com.music.bitchord.playback.smart.ForkMixPicks
import kotlin.math.roundToLong

private val PickOn = Color(0xFF3A9BDC) // the popup's frame blue
private val PickOff = Color(0xFF2A2A2A) // the popup's chip grey
private val PickNote = Color.White.copy(alpha = 0.55f)

/**
 * Fork: the Automix overview's picks under the decks, as the YouTube Music extension's overview has
 * them (`automix-overview.js`): a style (Auto, Loop, Blend, Filter, Fade) and a length (Auto, 4 to 32
 * bars) for this pair of songs, kept for the next time they meet ([ForkMixPicks]), Preview to jump to
 * just before the mix, and a note on what the pick did.
 *
 * Only the Web and Advanced AutoDJ styles plan with picks; under BitChord's own style the rows are
 * greyed out and the note says so. While a mix plays, a pick is for the next time the pair plays.
 * [live] is the popup's own: a blend in flight. [view] is the pair it draws; the picks wait until the
 * planner's pair is that one.
 */
@Composable
internal fun ForkMixPicksPanel(live: Boolean, view: MixTransitionView?, modifier: Modifier = Modifier) {
    val picks by ForkMixPicks.picks.collectAsStateWithLifecycle()
    val planned by ForkMixPicks.planned.collectAsStateWithLifecycle()
    val loopNote by ForkMixPicks.loop.collectAsStateWithLifecycle()
    val mixing by PlayerSettings.smartMixInProgress.collectAsStateWithLifecycle()
    val styleOn = ForkMixPicks.active
    // Only the pair the popup draws: none when it draws nothing (the last song), and not the next pair
    // while it still draws a finished mix.
    val plan = planned?.takeIf { view != null && it.isShownAs(view.outgoing.title, view.incoming.title) }
    val pair = plan?.pair
    val pick = pair?.let { picks[it] } ?: ForkMixPicks.Pick()
    val loop = loopNote?.takeIf { it.pair == pair }
    val loopPlanned = loop?.loopStartSec != null
    // A picked blend, filter mix or fade turns the loop away; with no pick the loop wins when it's planned.
    val pickedNoLoop = pick.style == ForkMixPicks.Style.BLEND || pick.style == ForkMixPicks.Style.FILTER ||
        pick.style == ForkMixPicks.Style.FADE || (pick.style == null && pick.bars != null)
    val actual = if (loopPlanned && !pickedNoLoop) {
        ForkMixPicks.Style.LOOP
    } else {
        plan?.style
    }
    val editable = styleOn && plan != null && !live && !mixing && !plan.armed
    // "No loop mix for these two": the loop mix's own reason, unless it's the pick that turned it away.
    val loopNone = loop != null && loop.loopStartSec == null && loop.why != ForkMixPicks.PICKED_REASON &&
        loop.why?.startsWith("waiting") != true

    val style = pick.style
    val fixedLen = style == ForkMixPicks.Style.LOOP || style == ForkMixPicks.Style.FADE ||
        (style == null && pick.bars == null && (actual == ForkMixPicks.Style.LOOP || actual == ForkMixPicks.Style.FADE))
    val lenWhy = when {
        fixedLen && (style ?: actual) == ForkMixPicks.Style.LOOP -> "A loop mix sets its own length: pick Blend or Filter to choose one"
        fixedLen -> "A fade has a fixed length"
        style == ForkMixPicks.Style.FILTER -> "A filter mix is at most ${ForkAutoDj.FILTER_MAX_BARS} bars"
        else -> null
    }

    val previewAt = when {
        plan == null -> null
        actual == ForkMixPicks.Style.LOOP -> loop?.loopStartSec?.minus(ForkMixPicks.PREVIEW_LOOP_LEAD_SEC)
        else -> plan.previewAtSec
    }
    val leftSec = when {
        plan == null || previewAt == null -> null
        actual == ForkMixPicks.Style.LOOP -> previewAt + ForkMixPicks.PREVIEW_LOOP_LEAD_SEC - plan.positionSec
        else -> previewAt + ForkMixPicks.PREVIEW_BLEND_LEAD_SEC - plan.positionSec
    }
    val canPreview = editable && previewAt != null && previewAt > 0 && leftSec != null &&
        leftSec >= ForkMixPicks.PREVIEW_MIN_LEFT_SEC && ForkMixPicks.seek != null

    val note = when {
        !styleOn -> "Pick Web or Advanced AutoDJ in Settings to choose a mix for each pair of songs."
        plan == null -> "Planning the mix…"
        live || mixing || plan.armed -> "Mixing now: a new pick is for the next time these two play."
        style != null && style != actual -> if (style == ForkMixPicks.Style.LOOP) {
            if (loopNone) "No loop mix for these two: ${loop?.why}" else "Preparing the loop mix…"
        } else {
            // A blend picked for songs too far apart in tempo is still a filter mix: say why.
            plan.reason ?: "Planning…"
        }
        pick.bars != null && plan.bars != 0 && pick.bars != plan.bars && actual != ForkMixPicks.Style.LOOP ->
            "${pick.bars} bars asked; ${plan.bars} fit here."
        style != null || pick.bars != null -> "Kept for these two songs."
        plan.reason != null && actual != ForkMixPicks.Style.LOOP -> plan.reason.orEmpty()
        lenWhy != null && fixedLen -> "$lenWhy."
        else -> ""
    }

    fun choose(change: ForkMixPicks.Pick.() -> ForkMixPicks.Pick) {
        if (pair == null || !editable) return
        ForkMixPicks.set(pair, pick.change())
    }

    Column(modifier.fillMaxWidth().alpha(if (styleOn) 1f else 0.4f)) {
        PickRow("Style") {
            PickChip("Auto", on = style == null, enabled = editable) { choose { copy(style = null) } }
            for (s in ForkMixPicks.Style.entries) {
                val off = s == ForkMixPicks.Style.LOOP && loopNone
                PickChip(s.label, on = style == s, enabled = editable && !off) { choose { copy(style = s) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        PickRow("Length") {
            PickChip("Auto", on = pick.bars == null, enabled = editable) { choose { copy(bars = null) } }
            for (b in ForkMixPicks.BARS) {
                val off = fixedLen || (style == ForkMixPicks.Style.FILTER && b > ForkAutoDj.FILTER_MAX_BARS)
                PickChip("$b", on = pick.bars == b, enabled = editable && !off) { choose { copy(bars = b) } }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PickChip("▶ Preview", on = false, enabled = canPreview) {
                previewAt?.let { at -> ForkMixPicks.seek?.invoke((at * 1000).roundToLong().coerceAtLeast(0L)) }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = note,
                color = PickNote,
                fontSize = 12.sp,
                maxLines = 2,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PickRow(label: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = PickNote,
            fontSize = 12.sp,
            modifier = Modifier.width(50.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { chips() }
    }
}

@Composable
private fun PickChip(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (on) Color.White else Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .alpha(if (enabled || on) 1f else 0.35f)
            .clip(RoundedCornerShape(50))
            .background(if (on) PickOn else PickOff)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}
