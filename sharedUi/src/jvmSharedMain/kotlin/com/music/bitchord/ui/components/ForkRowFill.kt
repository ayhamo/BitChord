package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil

/**
 * Fork: a preview row (a Library shelf, an artist page's albums) shows as many
 * [SHELF_CARD_WIDTH] cards as fill its width, never fewer than [min].
 *
 * Upstream stopped every such row at 5 cards: on a phone that already runs off
 * the edge, but a landscape tablet or a wide window was left with an empty half
 * row beside the "Show all". The last card counted is the one the edge cuts,
 * so a full row still reads as one that scrolls.
 */
@Composable
fun ForkRowFill(min: Int, spacing: Dp, content: @Composable (count: Int) -> Unit) {
    BoxWithConstraints {
        val room = maxWidth - PAGE_GUTTER * 2 + spacing
        content(maxOf(min, ceil(room / (SHELF_CARD_WIDTH + spacing)).toInt()))
    }
}
