package com.music.bitchord.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.NerdStats
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The phone's Audio Pipeline (`app/.../ui/components/AudioPipelineDialog.kt`) on the PC: the five
 * stages as one column of nodes on a signal bus, with a pulse travelling down it while something
 * plays. Replaces upstream's list of inset cards (`DesktopAudioPipelineDialog` hands over here).
 *
 * The drawing ([drawPipelineFlow], [pipelineStageActivation]) is copied, not shared: the phone's lives
 * in `:app`, which the desktop cannot depend on, and it is private there. Each stage's rows are the
 * PC engine's own ([DesktopAudioPipeline]), so the phone's Android-only rows (AudioFlinger, HAL,
 * USB capability, Bluetooth codec, bit-exact verdict) are not invented here.
 *
 * Costs nothing when unseen: the pulse is a [LaunchedEffect] in this composable, so it is cancelled
 * the moment the panel leaves composition, and it only runs while playing and with
 * "Reduce animation" off (the phone keys the same off the system animator scale, which a desktop
 * has no equivalent of; the app's own switch is the PC's).
 */
/**
 * Whether upstream's `DesktopAudioPipelineDialog` hands over to [ForkAudioPipelineDialog]. A switch
 * rather than a deleted body so upstream's list stays in place, word for word, to fall back to.
 */
internal const val FORK_PIPELINE_FLOW = true

@Composable
internal fun ForkAudioPipelineDialog(
    format: DesktopStreamFormat?,
    sourceName: String?,
    pipeline: DesktopAudioPipeline,
    onDismiss: () -> Unit,
) {
    // Only the playing flag, not the whole engine state: that carries the position and would
    // recompose the panel several times a second for nothing.
    val engine = ForkDebugBridge.engine
    val isPlaying by remember(engine) {
        engine?.state?.map { it.isPlaying }?.distinctUntilChanged() ?: flowOf(false)
    }.collectAsState(engine?.state?.value?.isPlaying == true)
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val loudnessOn by ForkLoudness.enabled.collectAsState()

    val isSignalActive = isPlaying && !reduceAnimation
    val flowAlpha by animateFloatAsState(
        targetValue = if (isSignalActive) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isSignalActive) 450 else 320,
            easing = if (isSignalActive) LinearOutSlowInEasing else FastOutLinearInEasing,
        ),
        label = "forkPipelineFlowAlpha",
    )
    var pulseProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isSignalActive) {
        pulseProgress = 0f
        if (!isSignalActive) return@LaunchedEffect
        val anim = Animatable(0f)
        while (isActive) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(durationMillis = 2400, easing = LinearEasing)) { pulseProgress = value }
        }
    }

    val density = LocalDensity.current
    var columnCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val stageCenters = remember { mutableStateMapOf<Int, Offset>() }
    val interactionRadiusPx = with(density) { 24.dp.toPx() }
    // Read inside graphicsLayer / drawBehind only, so a frame of the pulse redraws, never recomposes.
    val stageActivation: (Int) -> Float = { idx ->
        pipelineStageActivation(idx, pulseProgress, flowAlpha, stageCenters, interactionRadiusPx)
    }
    val onStageIconPositioned: (Int, Offset) -> Unit = { idx, center ->
        if (stageCenters[idx] != center) stageCenters[idx] = center
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 460) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 10.dp)) {
            Text(
                DesktopStrings["audio_pipeline", "Audio pipeline"],
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                DesktopStrings["audio_pipeline_subtitle", "Live signal path, decoder to output"],
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            // On its own layer, each frame re-records just the bus and nodes, not the rows.
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {}
                    .drawBehind { drawPipelineFlow(stageCenters, flowAlpha, pulseProgress, interactionRadiusPx) },
            )
            Column(Modifier.fillMaxWidth().onGloballyPositioned { columnCoordinates = it }) {
                // The engine's own copy, so Source and Decoder cannot disagree while an upgrade lands.
                val source = pipeline.sourceFormat ?: format
                ForkPipelineSection(0, Icons.AutoMirrored.Rounded.InsertDriveFile, DesktopStrings["pipeline_track_info", "Track info"], columnCoordinates, stageActivation, onStageIconPositioned) {
                    // A source addon's name; a Navidrome song has none, so the server is named instead.
                    val songId = ForkDebugBridge.engine?.state?.value?.song?.videoId
                    ForkPipelineRow(
                        DesktopStrings["pipeline_source", "Source"],
                        sourceName ?: "Navidrome".takeIf { songId != null && com.music.bitchord.data.navidrome.NavidromeSettings.isNavidromeId(songId) },
                    )
                    ForkPipelineRow(DesktopStrings["pipeline_format", "Format"], source?.codecLabel)
                    // A lossy codec has no bit depth of its own (the phone's rule too).
                    ForkPipelineRow(
                        DesktopStrings["pipeline_bit_depth", "Bit depth"],
                        source?.takeIf { it.isLossless }?.bitDepth?.let { "$it-bit" },
                    )
                    ForkPipelineRow(DesktopStrings["pipeline_sample_rate", "Sample rate"], source?.sampleRateHz?.let(::forkRateText))
                    ForkPipelineRow(DesktopStrings["pipeline_bitrate", "Bitrate"], source?.kbps?.let { "$it kbps" })
                    ForkPipelineRow(DesktopStrings["pipeline_channels", "Channels"], forkChannelLabel(source?.channels))
                }

                ForkPipelineSection(1, Icons.Rounded.Memory, DesktopStrings["pipeline_decoder", "Decoder"], columnCoordinates, stageActivation, onStageIconPositioned) {
                    ForkPipelineRow(DesktopStrings["pipeline_decoder_name", "Decoder name"], pipeline.decoderLabel)
                    ForkPipelineRow(DesktopStrings["pipeline_sample_rate", "Sample rate"], pipeline.decodedSampleRateHz?.let(::forkRateText))
                    ForkPipelineRow(DesktopStrings["pipeline_channels", "Channels"], forkChannelLabel(pipeline.decodedChannels))
                }

                // The decoder keeps the source's rate (swresample there only converts the sample
                // format and channel layout), so the comparison that matters is decoder → device.
                val inRate = pipeline.decodedSampleRateHz
                val outRate = pipeline.outputSampleRateHz
                val known = inRate != null && outRate != null
                ForkPipelineSection(2, Icons.Rounded.Tune, DesktopStrings["pipeline_resampler", "Resampler"], columnCoordinates, stageActivation, onStageIconPositioned) {
                    ForkPipelineRow(
                        DesktopStrings["pipeline_io_rate", "I/O rate"],
                        if (inRate == null && outRate == null) null
                        else "${inRate?.let(::forkRateText) ?: "—"} → ${outRate?.let(::forkRateText) ?: "—"}",
                    )
                    ForkPipelineRow(
                        DesktopStrings["pipeline_type", "Type"],
                        when { !known -> null; pipeline.resampling -> "Rate conversion"; else -> "None" },
                    )
                    ForkPipelineRow(
                        DesktopStrings["pipeline_quality", "Quality"],
                        when { !known -> null; pipeline.resampling -> "Resampled"; else -> "Passthrough" },
                    )
                }

                val on = DesktopStrings["on", "On"]
                val off = DesktopStrings["off", "Off"]
                ForkPipelineSection(3, Icons.Rounded.GraphicEq, DesktopStrings["pipeline_dsp", "DSP"], columnCoordinates, stageActivation, onStageIconPositioned) {
                    // The order the samples meet them in: the level correction runs after the equaliser
                    // on the PC (DesktopAudioEngine, ForkLoudnessStage), unlike the phone's DspChain.
                    ForkPipelineRow(DesktopStrings["equalizer", "Equalizer"], if (pipeline.equalizerEnabled) on else off)
                    ForkPipelineRow(DesktopStrings[ForkLoudness.TITLE_KEY, ForkLoudness.TITLE], if (loudnessOn) on else off)
                    ForkPipelineRow(DesktopStrings["skip_silence", "Skip silence"], if (pipeline.skipSilence) on else off)
                }

                ForkPipelineSection(4, Icons.AutoMirrored.Rounded.VolumeUp, DesktopStrings["pipeline_output_device", "Output device"], columnCoordinates, stageActivation, onStageIconPositioned) {
                    ForkPipelineRow(DesktopStrings["pipeline_device_name", "Device name"], pipeline.deviceName ?: "System default")
                    ForkPipelineRow(DesktopStrings["pipeline_output_api", "Output API"], "javax.sound")
                    ForkPipelineRow(
                        DesktopStrings["pipeline_pcm_format", "PCM format"],
                        pipeline.outputBytesPerSample?.let { if (pipeline.outputIsFloat == true) "32-bit float" else "${it * 8}-bit" },
                    )
                    ForkPipelineRow(DesktopStrings["pipeline_sample_rate", "Sample rate"], outRate?.let(::forkRateText))
                    ForkPipelineRow(DesktopStrings["pipeline_channels", "Channels"], forkChannelLabel(pipeline.outputChannels))
                    ForkPipelineRow(DesktopStrings["pipeline_buffers", "Buffers"], forkBufferText(pipeline))
                }
            }
        }
    }
}

/** The line's size as the phone words it: its length in time and frames, with the bytes upstream showed. */
private fun forkBufferText(pipeline: DesktopAudioPipeline): String? {
    val bytes = pipeline.bufferBytes.takeIf { it > 0 } ?: return null
    val frameBytes = (pipeline.outputBytesPerSample ?: 0) * (pipeline.outputChannels ?: 0)
    val rate = pipeline.outputSampleRateHz ?: 0
    if (frameBytes <= 0 || rate <= 0) return "${bytes / 1024} KB"
    val frames = bytes / frameBytes
    return "${bytes / 1024} KB (${frames * 1000L / rate} ms, $frames frames)"
}

/** The signal bus, the travelling pulse and the node rings behind the five stage icons (the phone's `drawSignalFlow`). */
private fun DrawScope.drawPipelineFlow(
    stageCenters: Map<Int, Offset>,
    flowAlpha: Float,
    pulseProgress: Float,
    interactionRadiusPx: Float,
) {
    if (stageCenters.size < 5) return
    val stagePoints = (0..4).map { stageCenters[it] ?: return }
    val xCenter = stagePoints[0].x
    val y0 = stagePoints[0].y
    val totalHeight = stagePoints[4].y - y0
    if (totalHeight <= 0f) return
    val nodeRadius = 11.5.dp.toPx()

    // 1. The static bus between the nodes, butt-capped at their rims.
    val busAlpha = if (flowAlpha > 0f) 0.22f + 0.06f * flowAlpha else 0.18f
    for (i in 0 until stagePoints.size - 1) {
        val yStart = stagePoints[i].y + nodeRadius
        val yEnd = stagePoints[i + 1].y - nodeRadius
        if (yEnd <= yStart) continue
        drawLine(Color.White.copy(alpha = busAlpha), Offset(xCenter, yStart), Offset(xCenter, yEnd), 2.dp.toPx(), StrokeCap.Butt)
        if (flowAlpha > 0f) {
            drawLine(Color.White.copy(alpha = 0.05f * flowAlpha), Offset(xCenter, yStart), Offset(xCenter, yEnd), 4.5.dp.toPx(), StrokeCap.Butt)
        }
    }

    // 2. The pulse travelling down the segments while playing; it fades into the last node.
    if (flowAlpha > 0f && pulseProgress <= 0.95f) {
        val pulseVisibility = (if (pulseProgress <= 0.85f) 1f else (1f - (pulseProgress - 0.85f) / 0.10f).coerceIn(0f, 1f)) * flowAlpha
        if (pulseVisibility > 0.01f) {
            val yPulse = y0 + (pulseProgress / 0.85f).coerceIn(0f, 1f) * totalHeight
            val tailLength = 38.dp.toPx()
            val leadLength = 8.dp.toPx()
            for (i in 0 until stagePoints.size - 1) {
                val yStart = stagePoints[i].y + nodeRadius
                val yEnd = stagePoints[i + 1].y - nodeRadius
                if (yEnd <= yStart) continue
                val pStart = max(yStart, yPulse - tailLength)
                val pEnd = min(yEnd, yPulse + leadLength)
                if (pEnd <= pStart) continue
                val halo = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.60f to Color.White.copy(alpha = 0.14f * pulseVisibility),
                        0.88f to Color.White.copy(alpha = 0.28f * pulseVisibility),
                        1.0f to Color.Transparent,
                    ),
                    startY = yPulse - tailLength,
                    endY = yPulse + leadLength,
                )
                drawLine(halo, Offset(xCenter, pStart), Offset(xCenter, pEnd), 6.dp.toPx(), StrokeCap.Butt)
                val core = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.50f to Color.White.copy(alpha = 0.45f * pulseVisibility),
                        0.86f to Color.White.copy(alpha = 0.98f * pulseVisibility),
                        1.0f to Color.Transparent,
                    ),
                    startY = yPulse - tailLength,
                    endY = yPulse + leadLength,
                )
                drawLine(core, Offset(xCenter, pStart), Offset(xCenter, pEnd), 2.25.dp.toPx(), StrokeCap.Butt)
                if (yPulse in yStart..yEnd) {
                    val head = Offset(xCenter, yPulse)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.35f * pulseVisibility), Color.Transparent),
                            center = head,
                            radius = 7.dp.toPx(),
                        ),
                        radius = 7.dp.toPx(),
                        center = head,
                    )
                    drawCircle(Color.White.copy(alpha = 0.98f * pulseVisibility), 1.75.dp.toPx(), head)
                }
            }
        }
    }

    // 3. The node at each stage: an opaque base (so the bus never shows through the icon), a halo,
    // and rings that brighten as the pulse passes.
    for (i in stagePoints.indices) {
        val center = Offset(xCenter, stagePoints[i].y)
        val act = pipelineStageActivation(i, pulseProgress, flowAlpha, stageCenters, interactionRadiusPx)
        // The panel's own fill, opaque: the phone uses its card's 0xFF131315 for the same reason.
        drawCircle(DesktopCardFill.copy(alpha = 1f), nodeRadius, center)
        val haloAlpha = 0.10f + 0.24f * act * flowAlpha
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color.White.copy(alpha = haloAlpha * 0.40f),
                    0.45f to Color.White.copy(alpha = haloAlpha),
                    1.0f to Color.Transparent,
                ),
                center = center,
                radius = nodeRadius + 5.dp.toPx(),
            ),
            radius = nodeRadius + 5.dp.toPx(),
            center = center,
        )
        if (flowAlpha > 0f && act > 0.02f) {
            drawCircle(Color.White.copy(alpha = 0.20f * act * flowAlpha), nodeRadius + 1.2.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }
        drawCircle(Color.White.copy(alpha = (0.35f + 0.55f * act * flowAlpha).coerceIn(0f, 1f)), nodeRadius, center, style = Stroke(1.dp.toPx()))
        if (flowAlpha > 0f && act > 0.05f) {
            drawCircle(Color.White.copy(alpha = 0.60f * act * flowAlpha), nodeRadius, center, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** How lit stage [stageIndex] is, 0–1, from how close the pulse is to it (the phone's `calculateStageActivation`). */
private fun pipelineStageActivation(
    stageIndex: Int,
    progress: Float,
    flowAlpha: Float,
    stageCenters: Map<Int, Offset>,
    interactionRadiusPx: Float,
): Float {
    if (flowAlpha <= 0.001f || stageCenters.size < 5) return 0f
    val p0 = stageCenters[0] ?: return 0f
    val p4 = stageCenters[4] ?: return 0f
    val totalHeight = p4.y - p0.y
    if (totalHeight <= 0f) return 0f
    val targetY = stageCenters[stageIndex]?.y ?: return 0f
    val yPulse = if (progress <= 0.85f) p0.y + (progress / 0.85f).coerceIn(0f, 1f) * totalHeight else p4.y
    val dy = abs(yPulse - targetY)
    if (dy > interactionRadiusPx) return 0f
    val p = 1f - dy / interactionRadiusPx
    val raw = (0.5f - 0.5f * cos(p * Math.PI.toFloat())).coerceIn(0f, 1f)
    val absorption = if (stageIndex == 4 && progress > 0.85f) {
        if (progress >= 0.95f) 0f else 1f - (progress - 0.85f) / 0.10f
    } else {
        1f
    }
    return (raw * absorption * flowAlpha).coerceIn(0f, 1f)
}

/** One stage: its node lane on the left, title and rows on the right (the phone's `PipelineSection`). */
@Composable
private fun ForkPipelineSection(
    stageIndex: Int,
    icon: ImageVector,
    title: String,
    columnCoordinates: LayoutCoordinates?,
    stageActivation: (Int) -> Float,
    onStageIconPositioned: (Int, Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    var iconCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun reportCenter(child: LayoutCoordinates?, parent: LayoutCoordinates?) {
        if (child == null || parent == null || !child.isAttached || !parent.isAttached) return
        onStageIconPositioned(stageIndex, parent.localPositionOf(child, Offset(child.size.width / 2f, child.size.height / 2f)))
    }
    // The first pass, when the column may not have reported yet.
    LaunchedEffect(iconCoordinates, columnCoordinates) { reportCenter(iconCoordinates, columnCoordinates) }

    ForkPipelineRule()
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 22.dp, top = 12.dp, bottom = 12.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(15.dp)
                    .onGloballyPositioned { coords ->
                        iconCoordinates = coords
                        // A stage growing when the engine reports moves every icon below it.
                        reportCenter(coords, columnCoordinates)
                    }
                    .graphicsLayer { alpha = (0.80f + 0.20f * stageActivation(stageIndex)).coerceIn(0f, 1f) },
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp, fontWeight = FontWeight.W600, letterSpacing = 0.5.sp),
                color = Color.White,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .graphicsLayer { alpha = (0.65f + 0.32f * stageActivation(stageIndex)).coerceIn(0f, 1f) },
            )
            Spacer(Modifier.height(7.dp))
            content()
        }
    }
}

/** "Label: value", the phone's row. An unknown value reads "—", as on the phone, so every stage keeps its rows. */
@Composable
private fun ForkPipelineRow(label: String, value: String?) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) { append("$label: ") }
            withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = 0.82f))) {
                append(value?.takeIf { it.isNotBlank() } ?: "—")
            }
        },
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    )
}

/** The hairline between stages, indented to the text column so it clears the node lane. */
@Composable
private fun ForkPipelineRule() {
    Box(
        Modifier
            .padding(start = 56.dp, end = 22.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Color.White.copy(alpha = 0.14f)),
    )
}

/** 44100 -> "44.1 kHz", as the phone writes rates. */
private fun forkRateText(hz: Int): String =
    NerdStats.megahertzLabel(hz) ?: "${"%.1f".format(Locale.ROOT, hz / 1000f).removeSuffix(".0")} kHz"

private fun forkChannelLabel(channels: Int?): String? = when (channels) {
    null -> null
    1 -> DesktopStrings["mono", "Mono"]
    2 -> DesktopStrings["stereo", "Stereo"]
    else -> "$channels ch"
}
