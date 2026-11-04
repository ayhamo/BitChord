package com.music.bitchord.ui.player

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Fork: the lyrics panel stops following the song while a finger is down on a line.
 *
 * Upstream pauses the panel's own scrolling once picking has started (`browsing = … || picking`),
 * but not during the long press that starts it: the playing line kept being scrolled to for the
 * ~half second the finger was held, so the line that ended up under the finger, and the one the
 * long press picked, were not the one pressed. Each row's press is counted here and the panel
 * reads [held] as one more reason to be browsing; a lift or cancel (a tap that seeks, a drag that
 * becomes a scroll, a long press that turned into picking) releases it, and following resumes as
 * it does after a pick is cancelled or shared.
 */
internal class ForkLyricsHold {

    private var presses by mutableIntStateOf(0)

    /** Some line is under a finger right now. */
    val held: Boolean get() = presses > 0

    fun press() {
        presses++
    }

    fun release() {
        if (presses > 0) presses--
    }

    /** Counts [source]'s presses for as long as the row is composed; a row scrolled away mid-press lets go. */
    @Composable
    fun Track(source: InteractionSource) {
        val down = remember(source) { mutableListOf<PressInteraction.Press>() }
        LaunchedEffect(source) {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> { down += interaction; press() }
                    is PressInteraction.Release -> if (down.remove(interaction.press)) release()
                    is PressInteraction.Cancel -> if (down.remove(interaction.press)) release()
                }
            }
        }
        DisposableEffect(source) {
            onDispose {
                repeat(down.size) { release() }
                down.clear()
            }
        }
    }
}
