package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Fork: a caption over the top of the player, so the window can be moved (and double-clicked to
 * maximize) while the player covers it. The player sheet fills the whole window, title bar
 * included, so with it open there was nothing left to grab (owner, 2026-11-03).
 *
 * Two strips either side of the sheet's own handle, which still closes the player. Thin, so the
 * player's own controls stay clear of it; it only exists where the app draws its own frame.
 */
@Composable
internal fun BoxScope.ForkPlayerCaption() {
    val scope = LocalDesktopWindowScope.current ?: return
    if (!DesktopPlatform.drawsOwnWindowFrame) return
    val actions = LocalDesktopWindowActions.current
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(CAPTION_HEIGHT)) {
        Box(Modifier.weight(1f).fillMaxHeight().captionPress(scope, actions))
        Spacer(Modifier.width(HANDLE_GAP))
        Box(Modifier.weight(1f).fillMaxHeight().captionPress(scope, actions))
    }
}

private val CAPTION_HEIGHT = 24.dp
private val HANDLE_GAP = 120.dp
