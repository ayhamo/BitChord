package com.music.bitchord.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * A settings row that only applies while another setting allows it, kept in its place and
 * faded and closed to touch while it doesn't (fork). The owner's rule: such a row never
 * disappears, so its value stays readable and the switch that brings it back is plain.
 *
 * The twin of upstream's private `BypassedByBitPerfect` in SettingsSheet.kt, with a name that
 * says nothing about why the row is off; kept here rather than reusing that one so the upstream
 * file only carries hook lines. Same look (alpha 0.38) and the same input rule: the press, the
 * release and a sideways drag (a slider) are taken, a vertical drag is left alone, so the page
 * still scrolls when a swipe starts on one of these rows.
 */
@Composable
internal fun ForkDisabledWhen(disabled: Boolean, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (disabled) 0.38f else 1f, label = "forkDisabled")
    // A Column, not a Box: a row can be several stacked pieces (a title row and its control).
    Column(
        Modifier
            .alpha(alpha)
            .then(
                if (disabled) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                            var sideways: Boolean? = null
                            var travel = Offset.Zero
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { change ->
                                    when {
                                        change.changedToUpIgnoreConsumed() -> change.consume()
                                        change.positionChanged() -> {
                                            travel += change.positionChange()
                                            if (sideways == null && travel.getDistance() > viewConfiguration.touchSlop / 2) {
                                                sideways = abs(travel.x) > abs(travel.y)
                                            }
                                            if (sideways == true) change.consume()
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
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

/**
 * Every row [block] adds to this group, drawn through [ForkDisabledWhen] (fork). Lets upstream's
 * `if (parentSetting) { row(…) … }` become one hook line, `forkDisabledWhen(!parentSetting) {`,
 * leaving the rows themselves untouched. Rows are always wrapped (also while enabled), so the
 * composition keeps its shape and the fade animates when the parent setting flips.
 *
 * Inline, so the rows' `stringResource` calls run in the caller's composable scope, as they did
 * inside the `if`.
 */
internal inline fun SettingsGroupScope.forkDisabledWhen(disabled: Boolean, block: SettingsGroupScope.() -> Unit) {
    val start = entries.size
    block()
    for (i in start until entries.size) {
        val entry = entries[i]
        entries[i] = SettingsGroupScope.Entry(entry.divided) { ForkDisabledWhen(disabled, entry.content) }
    }
}
