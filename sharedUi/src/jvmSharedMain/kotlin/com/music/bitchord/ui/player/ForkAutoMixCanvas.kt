package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.ForkCanvasSettings

/**
 * Fork: the fork's canvas settings that v1.10's Automix canvas (`rememberAutoMixCanvasPair`) has to
 * follow as the ordinary player does through [forkCanvases]: "Animated covers" ([covers]) and the
 * canvas settings' [generation] (a changed source, quality or per-song choice, canvas.md §13.10).
 *
 * The Automix canvas asks the host for the incoming song's clip itself and, once the mix has
 * promoted it, the player prefers that clip to the session's own (`promotedCanvas`), so a cover
 * clip kept playing with "Animated covers" off, and a settings change after the promotion left it
 * in place until the next song (audit 2026-11-24). Used as a key of both its effects (a change
 * drops the promotion and what is being prepared) and as [allows] on the clip it prepares.
 */
internal data class ForkAutoMixCanvasKey(val covers: Boolean, val generation: Int) {
    /**
     * Whether [clip] may play as the incoming song's clip: a cover clip only with "Animated covers"
     * on. A full-screen clip (`fillsPhone`) retires before the mix whatever this says.
     */
    fun allows(clip: CanvasArtwork): Boolean = covers || clip.source.fillsPhone
}

@Composable
internal fun rememberForkAutoMixCanvasKey(): ForkAutoMixCanvasKey {
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    val generation by ForkCanvasSettings.generation.collectAsStateWithLifecycle()
    return ForkAutoMixCanvasKey(covers, generation)
}
