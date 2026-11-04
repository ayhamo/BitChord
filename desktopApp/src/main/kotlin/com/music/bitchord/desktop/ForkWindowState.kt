package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/**
 * Fork: the window opens where it was left, at the size it was left, maximized if it was (Main.kt
 * opened 1220×780 at the system's default place every time). Kept with the PC's settings
 * ([DesktopPersistence], so in the portable folder's `data\prefs`).
 *
 * Everything is in AWT's logical units, which are what Compose's window `dp` are on the desktop.
 * The saved place is checked against the screens there are now: a window left on a monitor that
 * has since been unplugged, or mostly off every screen, opens centred on the main one, and one too
 * big for its screen is shrunk to it.
 */
internal object ForkWindowState {

    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
    }

    /** At least this much of the window on a screen for that screen to count as where it is. */
    internal const val MIN_VISIBLE_WIDTH = 120
    internal const val MIN_VISIBLE_HEIGHT = 60

    /**
     * Where a window saved at [saved] goes now. [screens] are the usable areas (taskbar left out),
     * the main screen first. Null [saved] or no screens: null, the opening defaults.
     */
    internal fun place(saved: Bounds?, screens: List<Bounds>, minWidth: Int, minHeight: Int): Bounds? {
        if (saved == null || screens.isEmpty() || saved.width <= 0 || saved.height <= 0) return null
        val best = screens.maxBy { overlap(saved, it) }
        val visible = intersection(saved, best)
        val home = if (visible != null && visible.width >= MIN_VISIBLE_WIDTH && visible.height >= MIN_VISIBLE_HEIGHT) best else null
        val screen = home ?: screens.first()
        // Never smaller than the window's own minimum, never bigger than the screen.
        val width = saved.width.coerceAtLeast(minWidth).coerceAtMost(screen.width)
        val height = saved.height.coerceAtLeast(minHeight).coerceAtMost(screen.height)
        if (home == null) {
            return Bounds(screen.x + (screen.width - width) / 2, screen.y + (screen.height - height) / 2, width, height)
        }
        // Wholly on its screen, so the caption strip (and its drag area) can always be reached.
        val x = saved.x.coerceIn(screen.x, screen.right - width)
        val y = saved.y.coerceIn(screen.y, screen.bottom - height)
        return Bounds(x, y, width, height)
    }

    private fun intersection(a: Bounds, b: Bounds): Bounds? {
        val left = maxOf(a.x, b.x)
        val top = maxOf(a.y, b.y)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        return if (right > left && bottom > top) Bounds(left, top, right - left, bottom - top) else null
    }

    private fun overlap(a: Bounds, b: Bounds): Long = intersection(a, b)?.let { it.width.toLong() * it.height } ?: 0L

    /** The screens' usable areas now, the main one first; empty when there is no display. */
    internal fun screens(): List<Bounds> = runCatching {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val main = environment.defaultScreenDevice
        val toolkit = Toolkit.getDefaultToolkit()
        (listOf(main) + environment.screenDevices.filter { it != main }).map { device ->
            val config = device.defaultConfiguration
            val area = config.bounds
            val insets = toolkit.getScreenInsets(config)
            Bounds(
                area.x + insets.left,
                area.y + insets.top,
                area.width - insets.left - insets.right,
                area.height - insets.top - insets.bottom,
            )
        }
    }.getOrDefault(emptyList())

    private fun persistence() = DesktopPersistence()

    private fun savedBounds(): Bounds? {
        val p = persistence()
        val width = p.int(KEY_WIDTH, 0)
        val height = p.int(KEY_HEIGHT, 0)
        if (width <= 0 || height <= 0) return null
        return Bounds(p.int(KEY_X, 0), p.int(KEY_Y, 0), width, height)
    }

    /** Whether the window was maximized when it was last put down. */
    val startMaximized: Boolean by lazy { persistence().boolean(KEY_MAXIMIZED, false) }

    /** Main.kt's window state: the saved place, else the old opening size at the default place. */
    @Composable
    fun rememberState(defaultSize: DpSize, minWidth: Int, minHeight: Int): WindowState {
        val placed = remember { place(savedBounds(), screens(), minWidth, minHeight) }
        return if (placed == null) {
            rememberWindowState(size = defaultSize)
        } else {
            rememberWindowState(
                position = WindowPosition(placed.x.dp, placed.y.dp),
                size = DpSize(placed.width.dp, placed.height.dp),
            )
        }
    }

    /**
     * Saves where [window] is whenever it moves, resizes or is maximized, a moment after it
     * settles. Not while minimized (Windows parks it at -32000) or filling the screen for the
     * player; while maximized only the flag, so restoring goes back to the floating place.
     */
    fun track(window: Frame) {
        val timer = javax.swing.Timer(SAVE_DELAY_MS) { save(window) }.apply { isRepeats = false }
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = timer.restart()
            override fun componentResized(e: ComponentEvent) = timer.restart()
        })
        window.addWindowStateListener { timer.restart() }
    }

    private fun save(window: Frame) {
        val state = window.extendedState
        if (state and Frame.ICONIFIED != 0 || !window.isShowing) return
        if (DesktopWindowMode.fullScreen.value) return
        val maximized = DesktopWindowMode.maximized.value || state and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
        val p = persistence()
        p.saveBoolean(KEY_MAXIMIZED, maximized)
        if (maximized) return
        p.saveInt(KEY_X, window.x)
        p.saveInt(KEY_Y, window.y)
        p.saveInt(KEY_WIDTH, window.width)
        p.saveInt(KEY_HEIGHT, window.height)
    }

    private const val SAVE_DELAY_MS = 400
    internal const val KEY_X = "fork_window_x"
    internal const val KEY_Y = "fork_window_y"
    internal const val KEY_WIDTH = "fork_window_width"
    internal const val KEY_HEIGHT = "fork_window_height"
    internal const val KEY_MAXIMIZED = "fork_window_maximized"
}
