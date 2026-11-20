package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.lyrics.ForkLyricsRules
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.MENU_FROST_ALPHA
import com.music.bitchord.ui.components.MenuFrostDark
import com.music.bitchord.ui.components.liveMenuFrost
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.utils.containSheetGestures
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.math.roundToInt

private val SheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
private val CardShape = RoundedCornerShape(16.dp)
private val Scrim = Color.Black.copy(alpha = 0.5f)

/**
 * Fork: the lyrics offset drawer with the rulebook's two offsets (lyrics.md §3) — "This song",
 * saved by video id, and "All songs", BitChord's global setting — in place of upstream's
 * `LyricsOffsetSheet` (one global, ±5 s in steps of 100 ms). Each has steps of 50 ms, a slider
 * over ±3000 ms, a field for an exact value and a reset (§3.3); both apply as they change (§3.4).
 * Shown in the rule's sign, positive earlier; the global is stored turned around, as BitChord
 * always stored it ([ForkLyricsRules.globalFromStored]), so a saved value keeps its meaning.
 * Drawn as upstream's drawer is: the same frost, handle and drag-to-dismiss.
 */
@Composable
internal fun ForkLyricsOffsetSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val storedGlobal by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val songKey by ForkLyricsOffsets.current.collectAsStateWithLifecycle()
    val songs by ForkLyricsOffsets.songs.collectAsStateWithLifecycle()
    val songMs = songKey?.let { songs[it] } ?: 0
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val drawerOffset by animateFloatAsState(drag, spring(stiffness = Spring.StiffnessMediumLow), label = "forkOffsetDrawer")
    val scrimAlpha = if (height > 0) (1f - drawerOffset / height).coerceIn(0f, 1f) else 1f
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val frost = LocalPlayerFrost.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .containSheetGestures()
            .background(Scrim.copy(alpha = Scrim.alpha * scrimAlpha))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(180)) { it },
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .onSizeChanged { height = it.height }
                    .offset { IntOffset(0, drawerOffset.roundToInt()) }
                    .clip(SheetShape)
                    .then(
                        when {
                            reduceDynamicBlur -> Modifier.background(MenuFrostDark)
                            frost != null -> with(frost) { Modifier.frostPanel(SheetShape, MenuFrostDark.copy(alpha = MENU_FROST_ALPHA)) }
                            else -> Modifier.liveMenuFrost(hazeState, MenuFrostDark)
                        },
                    )
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = {})
                    .pointerInput(height) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (height > 0 && drag > height * 0.25f) onDismiss() else drag = 0f },
                            onDragCancel = { drag = 0f },
                        ) { _, delta -> drag = (drag + delta).coerceAtLeast(0f) }
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(bottom = 12.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f)),
                )
                Text(
                    text = stringResource(Res.string.lyrics_offset),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                )
                Text(
                    text = stringResource(Res.string.fork_lyrics_offset_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 3.dp, bottom = 14.dp),
                )
                ForkOffsetCard(
                    title = stringResource(Res.string.fork_lyrics_offset_song),
                    valueMs = songMs,
                    limitMs = ForkLyricsOffsets.FIELD_MS,
                    enabled = songKey != null,
                ) { value -> songKey?.let { ForkLyricsOffsets.setSongOffset(it, value) } }
                Spacer(Modifier.height(10.dp))
                ForkOffsetCard(
                    title = stringResource(Res.string.fork_lyrics_offset_global),
                    valueMs = ForkLyricsRules.globalFromStored(storedGlobal),
                    limitMs = MAX_LYRICS_OFFSET_MS,
                    enabled = true,
                ) { value -> PlayerSettings.setLyricsOffsetMs(ForkLyricsRules.storedFromRule(value)) }
            }
        }
    }
}

/**
 * One offset: its value (tap to type one), −/+ in [ForkLyricsOffsets.STEP_MS], a slider over
 * ±[ForkLyricsOffsets.SLIDER_MS], and a reset once it is off zero. [limitMs] bounds the field.
 */
@Composable
private fun ForkOffsetCard(
    title: String,
    valueMs: Int,
    limitMs: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val haptics = rememberHaptics()
    val set: (Int) -> Unit = { onChange(it.coerceIn(-limitMs, limitMs)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = if (enabled) 0.85f else 0.35f),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            ForkOffsetField(valueMs, enabled, set)
            Text(
                text = stringResource(Res.string.lyrics_offset_reset),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = if (enabled && valueMs != 0) 0.8f else 0.3f),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled && valueMs != 0) {
                        haptics.play(Haptic.Tap)
                        set(0)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ForkStepButton("−", stringResource(Res.string.decrease_lyrics_offset), enabled && valueMs > -limitMs) {
                haptics.play(Haptic.Select)
                set(valueMs - ForkLyricsOffsets.STEP_MS)
            }
            ThinSlider(
                value = ((valueMs.coerceIn(-ForkLyricsOffsets.SLIDER_MS, ForkLyricsOffsets.SLIDER_MS) + ForkLyricsOffsets.SLIDER_MS).toFloat() /
                    (2 * ForkLyricsOffsets.SLIDER_MS)),
                onValueChange = { fraction ->
                    if (enabled) {
                        val raw = -ForkLyricsOffsets.SLIDER_MS + fraction.coerceIn(0f, 1f) * 2 * ForkLyricsOffsets.SLIDER_MS
                        set((raw / ForkLyricsOffsets.STEP_MS).roundToInt() * ForkLyricsOffsets.STEP_MS)
                    }
                },
                idleHeight = 6.dp,
                activeHeight = 10.dp,
                modifier = Modifier.weight(1f),
            )
            ForkStepButton("+", stringResource(Res.string.increase_lyrics_offset), enabled && valueMs < limitMs) {
                haptics.play(Haptic.Select)
                set(valueMs + ForkLyricsOffsets.STEP_MS)
            }
        }
    }
}

/** The value as an editable "+0.35 s"-style field; typed in ms, applied on Done or on leaving it. */
@Composable
private fun ForkOffsetField(valueMs: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(valueMs, editing) { mutableStateOf(if (editing) valueMs.toString() else forkFormatOffset(valueMs)) }
    val label = stringResource(Res.string.fork_lyrics_offset_field)
    val commit = {
        text.trim().removeSuffix("ms").trim().toIntOrNull()?.let(onChange)
        editing = false
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '-' || ch == '+' }.take(7) },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        ),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .width(96.dp)
            .semantics { contentDescription = label }
            .onFocusChanged { focus ->
                if (focus.isFocused && !editing) editing = true
                else if (!focus.isFocused && editing) commit()
            },
    )
}

@Composable
private fun ForkStepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = if (enabled) 0.85f else 0.25f))
    }
}

/** "+0.35 s": the rule's sign, two decimals for the 50 ms steps. */
internal fun forkFormatOffset(ms: Int): String = String.format(Locale.getDefault(), "%+.2f s", ms / 1_000f)
