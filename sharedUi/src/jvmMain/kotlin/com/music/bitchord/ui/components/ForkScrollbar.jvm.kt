package com.music.bitchord.ui.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Fork: PC-Continued e16ed1f8's AppleScrollbar.jvm.kt, with a padding for what floats over a pane.

/**
 * macOS's overlay bar: a light grey hairline that only really arrives when the pointer comes for
 * it. [ScrollbarStyle] has no hidden state, so the resting colour is the faintest grey that still
 * reads against the dark chrome.
 */
private val ForkScrollbarStyle = ScrollbarStyle(
    minimalHeight = 26.dp,
    thickness = 7.dp,
    shape = CircleShape,
    hoverDurationMillis = 450,
    unhoverColor = Color(0x2EABABB2),
    hoverColor = Color(0x8CABABB2),
)

@Composable
actual fun ForkScrollbarBox(
    state: LazyListState,
    modifier: Modifier,
    barPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        content()
        ForkScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(barPadding))
    }
}

@Composable
actual fun ForkScrollbarBox(
    state: LazyGridState,
    modifier: Modifier,
    barPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    val adapter: ScrollbarAdapter = rememberScrollbarAdapter(state)
    Box(modifier) {
        content()
        // No bar where the pane has nowhere to go: a list that shows all of itself offers nothing to reach.
        if (state.canScrollBackward || state.canScrollForward) {
            ForkBar(adapter, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(barPadding))
        }
    }
}

@Composable
actual fun ForkScrollbar(state: LazyListState, modifier: Modifier) {
    val adapter: ScrollbarAdapter = rememberScrollbarAdapter(state)
    if (state.canScrollBackward || state.canScrollForward) ForkBar(adapter, modifier)
}

/** The bar itself, laid over the right edge of its box rather than beside the list inside it. */
@Composable
private fun ForkBar(adapter: ScrollbarAdapter, modifier: Modifier) {
    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier.padding(end = 3.dp, top = 6.dp, bottom = 6.dp),
        style = ForkScrollbarStyle,
    )
}
