package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Fork: the top bar's volume, as upstream's web player has it (`shell/VolumeControl.tsx`,
 * `shell/wheelVolume.ts`). The speaker mutes and, pressed again, puts back the level it muted
 * from; its glyph shows muted, low or loud. The mouse wheel over the speaker or the slider moves
 * the level 5% a notch (up is louder).
 *
 * Muted is volume 0 rather than a separate flag, so the level the app already persists, the
 * player's flyout and the media keys all agree; the level to come back to is kept as
 * `fork_volume_before_mute`, so a window closed while muted unmutes to it next time.
 */
internal object ForkVolume {

    /** One wheel notch moves the volume by this much (the drag slider is finer). */
    const val WHEEL_STEP = 0.05f

    /** Below this the speaker draws as low. */
    private const val LOW = 0.5f

    private const val KEY_BEFORE_MUTE = "fork_volume_before_mute"

    private val persistence by lazy { DesktopPersistence() }

    /**
     * The level a wheel step lands on: up (negative [deltaY]) raises it, down lowers it, clamped
     * to 0..1 and rounded to whole percents so steps don't gather float dust. No delta, no change.
     */
    fun afterWheel(volume: Float, deltaY: Float): Float {
        if (deltaY == 0f || deltaY.isNaN()) return volume
        val next = volume + if (deltaY < 0f) WHEEL_STEP else -WHEEL_STEP
        return (next.coerceIn(0f, 1f) * 100f).roundToInt() / 100f
    }

    /**
     * What the speaker does: a level above 0 mutes (and is remembered as [Toggle.before]); at 0
     * it comes back to [before], or full when there is nothing sensible to come back to.
     */
    fun toggle(volume: Float, before: Float?): Toggle =
        if (volume > 0f) {
            Toggle(next = 0f, before = volume)
        } else {
            Toggle(next = before?.takeIf { it > 0f && it <= 1f } ?: 1f, before = null)
        }

    data class Toggle(val next: Float, val before: Float?)

    enum class Glyph { MUTED, LOW, HIGH }

    fun glyph(volume: Float): Glyph = when {
        volume <= 0f -> Glyph.MUTED
        volume < LOW -> Glyph.LOW
        else -> Glyph.HIGH
    }

    fun press(volume: Float): Float {
        val stored = persistence.string(KEY_BEFORE_MUTE).toFloatOrNull()
        val result = toggle(volume, stored)
        result.before?.let { persistence.saveString(KEY_BEFORE_MUTE, it.toString()) }
        return result.next
    }
}

/** The speaker and the slider, in place of the plain icon beside `DesktopThinSlider`. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ForkVolumeControl(volume: Float, onVolumeChange: (Float) -> Unit) {
    val level by rememberUpdatedState(volume)
    val change by rememberUpdatedState(onVolumeChange)
    Row(
        Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
            val next = ForkVolume.afterWheel(level, delta)
            if (next == level) return@onPointerEvent
            event.changes.forEach { it.consume() }
            change(next)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyph = ForkVolume.glyph(volume)
        DesktopToolbarButton(onClick = { change(ForkVolume.press(level)) }, size = 28.dp) {
            Icon(
                when (glyph) {
                    ForkVolume.Glyph.MUTED -> Icons.AutoMirrored.Rounded.VolumeOff
                    ForkVolume.Glyph.LOW -> Icons.AutoMirrored.Rounded.VolumeDown
                    ForkVolume.Glyph.HIGH -> Icons.AutoMirrored.Rounded.VolumeUp
                },
                if (glyph == ForkVolume.Glyph.MUTED) DesktopStrings["unmute", "Unmute"] else DesktopStrings["mute", "Mute"],
                tint = DesktopSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(2.dp))
        DesktopThinSlider(
            value = volume,
            onValueChange = onVolumeChange,
            idleHeight = 4.dp,
            activeHeight = 8.dp,
            modifier = Modifier.width(78.dp),
        )
    }
}
