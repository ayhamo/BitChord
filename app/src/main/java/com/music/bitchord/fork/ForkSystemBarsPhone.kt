package com.music.bitchord.fork

import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.music.bitchord.ui.theme.ForkSystemBars
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The activity's side of "Hide system bars" ([ForkSystemBars]): applies the saved mode at start,
 * again whenever it changes in Settings, and whenever the window gets focus back. Focus is where
 * Android brings hidden bars back on its own: a dialog, a sheet, the notification shade or another
 * app over this one; a swipe's transient bars hide again by themselves.
 */
object ForkSystemBarsPhone {

    fun attach(activity: ComponentActivity) {
        val window = activity.window
        ForkSystemBars.apply(window)
        window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(
            ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) ForkSystemBars.apply(window) },
        )
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ForkSystemBars.mode.drop(1).collect { ForkSystemBars.apply(window, changed = true) }
            }
        }
    }
}
