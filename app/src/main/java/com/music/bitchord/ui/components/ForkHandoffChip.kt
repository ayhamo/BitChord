package com.music.bitchord.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.navidrome.ForkHandoff
import com.music.bitchord.fork.ForkHandoffPhone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fork: "Continue from PC: <song>, 2:14", a chip above the mini player when the PC left a newer
 * queue on the server ([ForkHandoffPhone.offer]). A tap continues it; the close button or a swipe
 * sideways lets that note go. Drawn from [ForkOfflineBanner], so the main screen needs no hook of
 * its own; it also tells [ForkHandoffPhone] when the screen comes to the front (a look at the
 * server) and leaves it (the queue is written for the PC).
 */
@Composable
internal fun ForkHandoffChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        ForkHandoffPhone.init(context)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> ForkHandoffPhone.checkOffer()
                Lifecycle.Event.ON_STOP -> ForkHandoffPhone.onBackground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val note by ForkHandoffPhone.offer.collectAsStateWithLifecycle()
    val shown = note
    AnimatedVisibility(visible = shown != null, modifier = modifier) {
        val n = shown ?: return@AnimatedVisibility
        var drag by remember(n.key) { mutableFloatStateOf(0f) }
        val limit = with(LocalDensity.current) { 120.dp.toPx() }
        val failed = stringResource(R.string.fork_handoff_failed)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .offset { IntOffset(drag.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (abs(drag) / (limit * 1.5f)).coerceIn(0f, 0.8f) }
                .pointerInput(n.key) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (abs(drag) > limit) ForkHandoffPhone.dismiss() else drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, amount -> drag += amount }
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                .clickable {
                    if (!ForkHandoffPhone.restoreOffered()) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                }
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(Icons.Rounded.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    R.string.fork_handoff_chip,
                    if (n.device.kind == ForkHandoff.Device.PC) stringResource(R.string.fork_handoff_pc) else n.device.name,
                    n.current?.title.orEmpty(),
                    ForkHandoff.clock(n.positionMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = ForkHandoffPhone::dismiss) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.fork_handoff_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
