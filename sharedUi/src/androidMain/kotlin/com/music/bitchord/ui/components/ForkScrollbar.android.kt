package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Fork: nothing on the phone (PC-Continued e16ed1f8's Android actual). It is scrolled with a
// finger already inside the list; the pane is handed straight back, so the phone is unchanged.

@Composable
actual fun ForkScrollbarBox(
    state: LazyListState,
    modifier: Modifier,
    barPadding: PaddingValues,
    content: @Composable () -> Unit,
) = content()

@Composable
actual fun ForkScrollbarBox(
    state: LazyGridState,
    modifier: Modifier,
    barPadding: PaddingValues,
    content: @Composable () -> Unit,
) = content()

@Composable
actual fun ForkScrollbar(state: LazyListState, modifier: Modifier) {
    // Nothing drawn.
}
