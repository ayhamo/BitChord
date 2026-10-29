package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the cover stays up on a new track before the full-screen clip takes over. */
internal const val FORK_BACKDROP_HOLD_MS = 2_500L

/** How long the full-screen clip then takes to fade in over it. */
internal const val FORK_BACKDROP_FADE_MS = 1_200

/**
 * Fork: a full-screen clip (a Spotify Canvas or a Navidrome backdrop) waits on
 * each new track, so the cover is seen first, and then fades in slowly.
 *
 * Upstream switched to the clip on its first frame, a few hundred ms into the
 * track, too fast to see the cover at all. This sits between the clip and the
 * player: [rendered] holds the clip's "first frame" back by
 * [FORK_BACKDROP_HOLD_MS] (the player keeps its cover layout until then) and
 * [alpha] then fades the clip in over [FORK_BACKDROP_FADE_MS]. Once a track's
 * clip has come in, it is not held again: the clip gives its frame up and takes
 * it back whenever the panels or the app cover it, and those come back at once.
 */
internal class ForkBackdropIntro(
    private val key: String?,
    private val enabled: Boolean,
    private val scope: CoroutineScope,
) : RememberObserver {
    private val fade = Animatable(if (enabled && key != shownFor) 0f else 1f)
    private var hold: Job? = null

    /** The clip's opacity, read in the draw phase. */
    val alpha: Float get() = fade.value

    fun rendered(rendered: Boolean, report: (Boolean) -> Unit) {
        hold?.cancel()
        if (!enabled || !rendered || key == shownFor) {
            report(rendered)
            // A fade cut short (the screen locked mid-fade drops the clip's frame) finishes when it is back.
            if (rendered && fade.value < 1f) hold = scope.launch { fade.animateTo(1f, tween(FORK_BACKDROP_FADE_MS)) }
            return
        }
        hold = scope.launch {
            delay(FORK_BACKDROP_HOLD_MS)
            shownFor = key
            report(true)
            fade.animateTo(1f, tween(FORK_BACKDROP_FADE_MS))
        }
    }

    // A new track (or the switch) replaces this intro: its hold must not go on to report for a clip that has gone.
    override fun onForgotten() {
        hold?.cancel()
    }

    override fun onAbandoned() {
        hold?.cancel()
    }

    override fun onRemembered() = Unit

    private companion object {
        /** The last clip that came in, so reopening the player on it doesn't hold again. */
        var shownFor: String? = null
    }
}

@Composable
internal fun rememberForkBackdropIntro(key: String?, enabled: Boolean): ForkBackdropIntro {
    val scope = rememberCoroutineScope()
    return remember(key, enabled) { ForkBackdropIntro(key, enabled, scope) }
}
