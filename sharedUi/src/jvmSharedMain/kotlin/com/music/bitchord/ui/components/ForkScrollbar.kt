package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Fork: an overlay scrollbar for the PC, from PC-Continued e16ed1f8 (`AppleScrollbar`).
 *
 * Drawn over the right edge of [content] rather than beside it, so a list never shifts when the
 * bar arrives, and thin enough to read as part of the window. The phone's actual draws nothing and
 * hands [content] straight back: a phone has no pointer to reach for a bar with, so the phone is
 * unchanged.
 *
 * The pane hands over the same [LazyListState] it is built with; that is the only place the bar
 * can learn how much is on screen and how far it has gone. [barPadding] keeps the bar clear of
 * what floats over the pane's ends (the PC's player bar).
 */
@Composable
expect fun ForkScrollbarBox(
    state: LazyListState,
    modifier: Modifier,
    barPadding: PaddingValues,
    content: @Composable () -> Unit,
)

/** [ForkScrollbarBox] for a grid, whose bar tracks rows rather than items. */
@Composable
expect fun ForkScrollbarBox(
    state: LazyGridState,
    modifier: Modifier,
    barPadding: PaddingValues,
    content: @Composable () -> Unit,
)

/**
 * The bar on its own, for a list that already sits in a `Box`: called as that Box's last child,
 * placed by the caller's [modifier] (e.g. `align(Alignment.CenterEnd).fillMaxHeight()`).
 */
@Composable
expect fun ForkScrollbar(
    state: LazyListState,
    modifier: Modifier,
)
