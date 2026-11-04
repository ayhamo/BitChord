package com.music.bitchord.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The mouse wheel anywhere on a page of Settings scrolls it, the margins either side of its centred
 * column too.
 *
 * Every page of Settings is a column held to [DesktopSettingsPageWidth] (or its own wider max) down
 * the middle of the page area, and its scrolling part lives inside that column, so a wheel over the
 * margins hit nothing that scrolls. A wheel nothing used (seen on the Final pass, not consumed) is
 * posted again as the same AWT wheel event moved sideways to the area's centre line, at the same
 * height, where the page's column is: Compose then hit-tests it as if the pointer were there. One
 * place for every page (Account & integrations, Equalizer, Audio output, …) instead of moving each
 * page's scroll container out to the window's edges.
 *
 * Loop guard: an event already on the centre line is never posted again, so a wheel the column
 * itself doesn't use (at the end of its scroll, or over its heading) costs one copy and stops.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.forkWheelAnywhere(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            if (change.isConsumed) continue
            val wheel = event.awtEventOrNull as? MouseWheelEvent ?: continue
            val dxPx = size.width / 2f - change.position.x
            if (abs(dxPx) < CENTRE_SLACK_PX) continue
            // Compose's pixels are the screen's; AWT's are scaled by the display's factor (the UI
            // scale setting only changes Compose's density, not this).
            val scale = wheel.component?.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
            val dx = (dxPx / scale).roundToInt()
            val copy = MouseWheelEvent(
                wheel.component,
                wheel.id,
                wheel.`when`,
                wheel.modifiersEx,
                wheel.x + dx,
                wheel.y,
                wheel.xOnScreen + dx,
                wheel.yOnScreen,
                wheel.clickCount,
                false,
                wheel.scrollType,
                wheel.scrollAmount,
                wheel.wheelRotation,
                wheel.preciseWheelRotation,
            )
            change.consume()
            EventQueue.invokeLater { Toolkit.getDefaultToolkit().systemEventQueue.postEvent(copy) }
        }
    }
}

/** How near the centre line an event counts as already there (the posted copy lands within a pixel). */
private const val CENTRE_SLACK_PX = 3f
