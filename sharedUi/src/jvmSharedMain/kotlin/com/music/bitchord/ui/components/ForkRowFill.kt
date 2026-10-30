package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil

/**
 * Fork: how many [SHELF_CARD_WIDTH] cards a preview row (a Library shelf, an
 * artist page's albums) shows: as many as fill the window, never fewer than [min].
 *
 * Upstream stopped every such row at 5 cards: on a phone that already runs off
 * the edge, but a landscape tablet or a wide window was left with an empty half
 * row beside the "Show all". The last card counted is the one the edge cuts,
 * so a full row still reads as one that scrolls. By the window, not the row: every
 * row in a list is that wide, and measuring each one means a subcomposition per row.
 */
@Composable
fun forkRowMax(min: Int, spacing: Dp): Int {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val room = width - PAGE_GUTTER * 2 + spacing
    return maxOf(min, ceil(room / (SHELF_CARD_WIDTH + spacing)).toInt())
}
