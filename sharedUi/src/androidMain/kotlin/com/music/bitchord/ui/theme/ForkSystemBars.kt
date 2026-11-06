package com.music.bitchord.ui.theme

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork: "Hide system bars" (phone, Settings > Appearance; owner, 2026-11-06). The status bar, the
 * navigation bar or both hidden app-wide; a swipe from the edge shows them for a moment
 * ([WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]).
 *
 * Lives beside upstream's [HideStatusBar] / [HideSystemBars] because both of those show the bars
 * again when they end (the player closing, a music video leaving full screen): their `onDispose`
 * calls [reapply], which puts this mode back. The app persists [mode] and calls [apply] when it
 * changes and when the activity's window gets focus back (a dialog or sheet is its own window, so
 * the bars show while one is open and hide again once it closes).
 */
object ForkSystemBars {

    enum class Mode { NONE, ALL, TOP, BOTTOM }

    val mode = MutableStateFlow(Mode.NONE)

    /** Upstream's "Hide status bar in player" has nothing left to do (greyed, not hidden). */
    fun hidesStatusBar(mode: Mode): Boolean = mode == Mode.ALL || mode == Mode.TOP

    /** Whether this fork switched the window's cutout mode, so only that is ever undone. */
    private var cutoutSetHere = false

    /**
     * Applies [mode] to [window]. [changed]: the setting itself changed, so a bar this mode no
     * longer hides is shown again; otherwise None leaves the window alone, so a bar upstream's
     * player is hiding stays hidden.
     */
    fun apply(window: Window, view: View = window.decorView, changed: Boolean = false) {
        val now = mode.value
        val controller = WindowCompat.getInsetsController(window, view)
        val hide = when (now) {
            Mode.NONE -> 0
            Mode.ALL -> WindowInsetsCompat.Type.systemBars()
            Mode.TOP -> WindowInsetsCompat.Type.statusBars()
            Mode.BOTTOM -> WindowInsetsCompat.Type.navigationBars()
        }
        if (now == Mode.NONE && !changed) return
        val show = WindowInsetsCompat.Type.systemBars() and hide.inv()
        if (changed && show != 0) controller.show(show)
        if (hide != 0) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(hide)
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        setCutout(window, hidesStatusBar(now))
    }

    /** Upstream's hide ended and showed the bars: this mode's go away again. */
    fun reapply(window: Window, view: View) {
        if (mode.value != Mode.NONE) apply(window, view)
    }

    /**
     * Before Android 15 a window on the default cutout mode is pushed below the camera once the
     * status bar is gone, a black band at the top (upstream's [HideStatusBar] works around the
     * same). 15+ lays every window out edge to edge, so it is left alone there.
     */
    private fun setCutout(window: Window, statusHidden: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val current = window.attributes.layoutInDisplayCutoutMode
        if (statusHidden && current == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            cutoutSetHere = true
        } else if (!statusHidden && cutoutSetHere) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            cutoutSetHere = false
        }
    }
}
