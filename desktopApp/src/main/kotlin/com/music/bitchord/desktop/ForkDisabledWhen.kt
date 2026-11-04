package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A settings row that only applies while another setting allows it, kept in its place and
 * faded and closed to the mouse while it doesn't (fork). The owner's rule: such a row never
 * disappears, so its value stays readable and the switch that brings it back is plain.
 *
 * The PC's twin of the phone's `ForkDisabledWhen` (and of upstream's `BypassedByBitPerfect`
 * there): the same alpha 0.38. `SettingsToggle`, `SettingsRow` and the panel rows have no
 * `enabled` of their own, so the clicks are taken here instead: every pointer event over the
 * rows is consumed before they see it (press, release, move, so no switch, chip or slider
 * moves), except the wheel, so the settings list still scrolls with the mouse over one of them.
 */
@Composable
internal fun ForkDisabledWhen(disabled: Boolean, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (disabled) 0.38f else 1f, label = "forkDisabled")
    // A Column, not a Box: a row can be several stacked pieces (a title, its chips, a caption).
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .then(
                if (disabled) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type != PointerEventType.Scroll) event.changes.forEach { it.consume() }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}
