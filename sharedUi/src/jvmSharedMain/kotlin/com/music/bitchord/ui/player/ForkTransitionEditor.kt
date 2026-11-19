package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.playback.smart.ForkMixPicks
import com.music.bitchord.playback.smart.automixv2.ForkV2EditorFeed
import com.music.bitchord.playback.smart.automixv2.ForkV2EditorGeometry as G
import com.music.bitchord.playback.smart.automixv2.ForkV2MixOverride
import com.music.bitchord.playback.smart.automixv2.ForkV2OverrideStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Fork: the Automix transition editor (spec/automix/editor.md §1-§8) in the shared mix popup, so the
 * phone (NowPlayingScreen's MixViewOverlay) and the PC (the player menu's "Automix overview",
 * ForkAutomixPreview) get the same one. It replaces the fork's first pick panel (Style
 * Auto/Loop/Blend/Filter/Fade and Length 4-32).
 *
 * Behaviour and wording follow the extension's overview (src/automix-overview.js) and the phone
 * patches (MixOverview.java: chips, a folded Customize, pinch zoom and long-press snapping); the look
 * stays BitChord's popup: its dark card, its frame blue, the decks coloured from the artwork.
 *
 * Three pieces, each hooked into upstream's MixViewPopup by one line:
 *  - [ForkTransitionHeader]: what the mix is ("Auto · Blend · 8 bars · 124 BPM · 15.5 s"), the
 *    Custom badge, "analysing the next song…".
 *  - [forkEditorDecks]: the two songs on the mix's own clock, drawn from Automix v2's plan
 *    (plan.curves: volume, EQ, effect lanes) instead of upstream's sampled faders, with the window
 *    dragged per song. Upstream's decks draw anything else (BitChord's style, a loop mix, a song
 *    still being analysed).
 *  - [ForkTransitionEditor]: the controls and the note.
 *
 * The data is Automix v2's last plan ([ForkV2EditorFeed]) for the pair the popup shows, and the pair's
 * override ([ForkV2OverrideStore], schema-shaped, keyed "from>to"). An edit is saved at once; the
 * planner picks it up on its next plan. Only Automix v2 plans with overrides: under BitChord's style
 * the controls are greyed and say so.
 */

private val EditorBlue = Color(0xFF3A9BDC) // the popup's frame blue
private val EditorChipOff = Color(0xFF2A2A2A) // the popup's chip grey
private val EditorPanel = Color(0xFF1E1E1E)
private val EditorNote = Color.White.copy(alpha = 0.55f)

/** editor.md §2: the lanes' colours, the same in both apps. */
private val VolumeColor = Color(0xFF4C8DFF)
private val EqColor = Color(0xFFF5C542)
private val EffectColor = Color(0xFFA779FF)

/** editor.md §2: each preset's chip colour (Auto and Custom: the frame's). */
private val PresetColors = mapOf(
    "auto" to EditorBlue, "smooth" to VolumeColor, "rise" to EffectColor, "blend" to EqColor, "wave" to EffectColor,
    "melt" to EffectColor, "slam" to Color(0xFFE5534B), "loop" to Color(0xFF3FB950), "custom" to EditorBlue,
)

private const val EditorSilentAlpha = 0.32f
private val EditorDecksHeight = 196.dp

/** A drag of one song under the mix's frame, while the finger (or mouse) is down. */
internal class EditorDrag(
    val pair: String,
    val out: Boolean,
    /** Where the plan has that song's side of the window (song seconds). */
    val base: Double,
    val pos: ForkV2MixOverride.Position,
    /** Why the drag is held, or null. */
    val limit: String?,
)

/** The editor's own state: one popup per process (as [MixViewRequest]). */
internal object ForkEditorUi {
    var drag by mutableStateOf<EditorDrag?>(null)

    /** A refused save or a nudge past its reach, for the pair it was for: shown until the next edit. */
    var refused by mutableStateOf<Pair<String, String>?>(null)
    var customOpen by mutableStateOf(false)
}

/** What the editor knows about the pair the popup shows. */
internal class EditorModel(
    val styleOn: Boolean,
    /** Automix v2's plan for the shown pair, or null: not planned (yet), or another pair. */
    val feed: ForkV2EditorFeed.Planned?,
    val planned: ForkMixPicks.Planned?,
    val ov: ForkV2MixOverride?,
    val loop: ForkMixPicks.Loop?,
    /** The pair's mix is held for playing, or plays: an edit is for the next time. */
    val mixing: Boolean,
) {
    val pairKey: String? = feed?.let { ForkV2OverrideStore.pairKey(it.fromId, it.toId) }
    val preset: String = ov?.preset ?: "auto"
    val hasWindow: Boolean = ov?.out != null || ov?.`in` != null
    private val loopPlanned = loop?.loopStartSec != null

    /** The loop mix said no for its own reasons (not because of a pick). */
    val loopNone: Boolean = loop != null && loop.loopStartSec == null && loop.why != ForkMixPicks.PICKED_REASON &&
        loop.why?.startsWith("waiting") != true

    /** An override that asks for something other than the loop mix turns it away. */
    private val loopTurnedAway = ov != null && preset != "loop" &&
        (ov.preset != null || ov.family != null || ov.bars > 0 || hasWindow || ov.beatMatch != null)

    /** The loop mix is what plays (its own length, curves and placement). */
    val playsLoop: Boolean = loopPlanned && !loopTurnedAway

    /** The length chips, lanes and window are off for a loop mix (editor.md §2). */
    val isLoop: Boolean = preset == "loop" || playsLoop

    val editable: Boolean = styleOn && feed != null
    val windowEditable: Boolean = editable && !mixing && !isLoop && feed!!.plan.style != "loop"
}

@Composable
private fun editorModel(view: MixTransitionView?, live: Boolean): EditorModel {
    val planned by ForkMixPicks.planned.collectAsStateWithLifecycle()
    val feed by ForkV2EditorFeed.planned.collectAsStateWithLifecycle()
    val loop by ForkMixPicks.loop.collectAsStateWithLifecycle()
    val changes by ForkV2OverrideStore.changes.collectAsStateWithLifecycle()
    val inProgress by PlayerSettings.smartMixInProgress.collectAsStateWithLifecycle()
    val styleOn = ForkMixPicks.active
    // Only the pair the popup draws: not the next pair while it still draws a finished mix.
    val shown = planned?.takeIf { view != null && it.isShownAs(view.outgoing.title, view.incoming.title) }
    val f = feed?.takeIf { shown != null && ForkMixPicks.pair(it.fromId, it.toId) == shown.pair }
    val ov = remember(f?.fromId, f?.toId, changes) { f?.let { ForkV2OverrideStore.get(it.fromId, it.toId) } }
    return EditorModel(
        styleOn = styleOn,
        feed = f,
        planned = shown,
        ov = ov,
        loop = loop?.takeIf { shown != null && it.pair == shown.pair },
        mixing = live || inProgress || shown?.armed == true,
    )
}

// -------------------------------------------------------------------------------------------------
// saving

private fun save(f: ForkV2EditorFeed.Planned, next: ForkV2MixOverride?) {
    val key = ForkV2OverrideStore.pairKey(f.fromId, f.toId)
    if (next == null) {
        ForkV2OverrideStore.reset(f.fromId, f.toId)
        ForkEditorUi.refused = null
        return
    }
    next.engine = 3 // Automix v2 (override.schema.json "engine")
    next.savedAt = System.currentTimeMillis()
    ForkEditorUi.refused = ForkV2OverrideStore.put(f.fromId, f.toId, next)?.let { key to it }
}

private fun edit(m: EditorModel, presetPicked: Boolean, change: ForkV2MixOverride.() -> Unit) {
    val f = m.feed ?: return
    save(f, G.edited(ForkV2OverrideStore.get(f.fromId, f.toId), presetPicked, change))
}

/** A 10 ms step of one song's position, from where the plan has it when nothing is saved yet. */
private fun nudge(m: EditorModel, out: Boolean, dir: Int) {
    val f = m.feed ?: return
    if (!m.windowEditable) return
    val ov = ForkV2OverrideStore.get(f.fromId, f.toId)
    val now = (if (out) ov?.out else ov?.`in`)
        ?: G.positionOf(if (out) f.plan.mixOutTrackTime else f.plan.mixInTrackTime, if (out) f.gridOut else f.gridIn, false, false)
    val pos = G.nudged(now, dir)
    if (pos == null) {
        ForkEditorUi.refused = m.pairKey!! to G.NUDGE_REFUSED
        return
    }
    val (o, i) = G.windowWith(ov, out, pos, f.plan.mixOutTrackTime, f.plan.mixInTrackTime, f.gridOut, f.gridIn)
    save(f, G.edited(ov, false) { this.out = o; this.`in` = i })
}

/** A finished drag: the window saved with the dragged side where it was let go (a drag that moved nothing saves nothing). */
private fun commit(d: EditorDrag) {
    val f = ForkV2EditorFeed.planned.value ?: return
    if (ForkV2OverrideStore.pairKey(f.fromId, f.toId) != d.pair) return // the song changed under the drag
    if (abs(d.pos.sec - d.base) < 0.005) return
    val ov = ForkV2OverrideStore.get(f.fromId, f.toId)
    val (o, i) = G.windowWith(ov, d.out, d.pos, f.plan.mixOutTrackTime, f.plan.mixInTrackTime, f.gridOut, f.gridIn)
    save(f, G.edited(ov, false) { out = o; `in` = i })
}

// -------------------------------------------------------------------------------------------------
// the header (editor.md §7)

/**
 * Over the popup's songs: the preset (Auto's with what the planner chose), the length, the tempo
 * and the seconds; the Custom badge for an edited pair; "analysing the next song…" while an
 * analysis is pending. Nothing under BitChord's own style (upstream's popup as it was).
 */
@Composable
internal fun ForkTransitionHeader(view: MixTransitionView?, live: Boolean) {
    if (!ForkMixPicks.active) return
    val m = editorModel(view, live)
    val analysing = when {
        view == null -> "analysing…"
        view.incoming.analysing -> "analysing the next song…"
        view.outgoing.analysing -> "analysing this song…"
        else -> null
    }
    val plan = m.feed?.plan
    val title = if (plan == null) "Automix" else G.headline(G.presetName(m.ov, plan, m.playsLoop), plan, m.playsLoop)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            if (analysing != null) {
                Text(analysing, color = EditorNote, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (G.badge(m.ov)) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Custom",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(EditorBlue).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** A deck's key chip: its Camelot code with the key's name ("4A · F minor"), as the extension's song tags. */
internal fun forkKeyLabel(code: String, key: String): String = if (key.isBlank()) code else "$code · ${key.trim()}"

// -------------------------------------------------------------------------------------------------
// the decks on the mix's clock

/** The part of the picture in view, as fractions of the whole (pad, mix, pad): the phone's pinch zoom. */
private class Zoom(val f0: Double, val f1: Double)

/**
 * Upstream's MixDecks asks this first: true when it drew the editor's decks (Automix v2 planned the
 * pair the popup shows, both songs are analysed, and it isn't a loop mix), false to draw its own.
 * [outColors] / [inColors]: each deck's low, mid and high colours from its artwork.
 */
@Composable
internal fun forkEditorDecks(view: MixTransitionView, progress: State<Float>, live: Boolean, outColors: List<Color>, inColors: List<Color>): Boolean {
    if (!ForkMixPicks.active || view.outgoing.analysing || view.incoming.analysing) return false
    val m = editorModel(view, live)
    val f = m.feed ?: return false
    if (m.playsLoop) return false
    EditorDecks(view, m, f, progress, live, outColors, inColors)
    return true
}

@Composable
private fun EditorDecks(
    view: MixTransitionView,
    m: EditorModel,
    f: ForkV2EditorFeed.Planned,
    progress: State<Float>,
    live: Boolean,
    outColors: List<Color>,
    inColors: List<Color>,
) {
    val plan = f.plan
    val D = max(0.5, plan.durationSec)
    val pad = max(4.0, D * 0.24)
    val pairKey = m.pairKey!!
    val zoom: MutableState<Zoom> = remember(pairKey) { mutableStateOf(Zoom(0.0, 1.0)) }
    val drag = ForkEditorUi.drag?.takeIf { it.pair == pairKey }
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val touch = drawerFollowsListScroll
    val model by rememberUpdatedState(m)
    val feed by rememberUpdatedState(f)
    // the gesture outlives a re-plan of the same pair (a new length): it reads the mix's seconds as they are now
    val mixSec by rememberUpdatedState(D)
    // PC: Esc during a drag drops the drag, unsaved (the extension's Esc); the phone's back does the same
    PlayerBackHandler(enabled = drag != null) { ForkEditorUi.drag = null }
    val positionSec = m.planned?.positionSec ?: Double.NaN
    val clock by remember(D, live, positionSec, plan.mixOutTrackTime) {
        derivedStateOf {
            val at = if (live) progress.value * D else positionSec - plan.mixOutTrackTime
            G.mixClock(at, D)
        }
    }
    val bars = plan.bars
    val chip = if (plan.style == "crossfade" || bars <= 0) "${plan.durationSec.roundToInt()} s" else if (bars == 1) "1 bar" else "$bars bars"

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(EditorDecksHeight)
                .semantics { contentDescription = "Automix transition: drag a song to move the window" }
                .pointerInput(pairKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val fNow = feed
                        val w = size.width.toDouble()
                        val out = down.position.y < size.height / 2f
                        val z = zoom.value
                        val span = (z.f1 - z.f0) * (mixSec + 2 * max(4.0, mixSec * 0.24))
                        val base = if (out) fNow.plan.mixOutTrackTime else fNow.plan.mixInTrackTime
                        val canDrag = model.windowEditable
                        var moved = false
                        var set = false
                        var cancelled = false
                        var beats = false
                        var pinching = false
                        var lastDist = 0f
                        var anchor = 0.0
                        ForkEditorUi.refused = null
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // a second finger: a zoom, not a drag (the drag is dropped, nothing saved)
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val dist = (a - b).getDistance()
                                val cx = ((a.x + b.x) / 2).toDouble()
                                if (!pinching) {
                                    pinching = true
                                    if (set) ForkEditorUi.drag = null
                                    cancelled = true
                                    lastDist = dist
                                    val q = zoom.value
                                    anchor = q.f0 + (cx / w) * (q.f1 - q.f0)
                                } else if (lastDist > 0f && dist > 0f) {
                                    // the point under the fingers stays under them
                                    val q = zoom.value
                                    val s = ((q.f1 - q.f0) / (dist / lastDist)).coerceIn(1 / G.MAX_ZOOM, 1.0)
                                    val a0 = (anchor - (cx / w) * s).coerceIn(0.0, 1 - s)
                                    zoom.value = Zoom(a0, a0 + s)
                                    lastDist = dist
                                }
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            if (pinching || cancelled || !canDrag) continue
                            val c = pressed[0]
                            val dx = c.position.x - down.position.x
                            if (!moved && abs(dx) > viewConfiguration.touchSlop) {
                                moved = true
                                // a hold before the drag moves: beats instead of downbeats (the phone's long-press)
                                beats = touch && c.uptimeMillis - down.uptimeMillis >= G.LONG_PRESS_MS
                                if (beats) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            if (!moved) continue
                            if (set && ForkEditorUi.drag == null) {
                                cancelled = true // Esc
                                continue
                            }
                            val ov = model.ov
                            val want = G.dragWant(base, dx.toDouble(), w, span)
                            val lim = fNow.limits
                            val at = G.dragTo(
                                want, lim.lo(out), lim.hi(out), if (out) fNow.gridOut else fNow.gridIn,
                                snap = ov?.beatMatch != "off", beats = beats || ev.keyboardModifiers.isShiftPressed, out = out,
                            )
                            ForkEditorUi.drag = EditorDrag(pairKey, out, base, at.pos, at.limit)
                            set = true
                            c.consume()
                        }
                        val d = ForkEditorUi.drag
                        if (set && !cancelled && d != null && d.pair == pairKey) {
                            ForkEditorUi.drag = null
                            commit(d)
                        } else if (set) {
                            ForkEditorUi.drag = null
                        }
                    }
                },
        ) {
            val z = zoom.value
            val all = D + 2 * pad
            val t0 = -pad + z.f0 * all
            val t1 = -pad + z.f1 * all
            drawEditorDecks(view, f, drag, D, t0, t1, outColors, inColors)
            // the bars chip between the lanes, under the middle of the frame
            val x0 = xOf(0.0, t0, t1)
            val x1 = xOf(D, t0, t1)
            val text = measurer.measure(chip, TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            val cw = text.size.width + 20.dp.toPx()
            val ch = text.size.height + 6.dp.toPx()
            val cx = ((x0 + x1) / 2 - cw / 2).coerceIn(2f, size.width - cw - 2f)
            drawRoundRect(Color(0xFF2A2A2A), Offset(cx, size.height / 2 - ch / 2), Size(cw, ch), CornerRadius(ch / 2))
            drawText(text, topLeft = Offset(cx + 10.dp.toPx(), size.height / 2 - text.size.height / 2))
            // the playhead, on the mix's clock; gone once the mix is over
            val at = if (live) progress.value * D else positionSec - plan.mixOutTrackTime
            if (!at.isNaN() && at in t0..t1 && (live || at < 0)) {
                val x = xOf(at, t0, t1)
                drawLine(Color.White.copy(alpha = 0.9f), Offset(x, 4.dp.toPx()), Offset(x, size.height - 4.dp.toPx()), 1.5.dp.toPx())
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LaneKey("Volume", VolumeColor)
            LaneKey("EQ", EqColor)
            LaneKey("Effect", EffectColor)
            Spacer(Modifier.weight(1f))
            Text(clock, color = EditorNote, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun LaneKey(label: String, color: Color) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(4.dp))
    Text(label, color = EditorNote, fontSize = 11.sp)
    Spacer(Modifier.width(10.dp))
}

private fun DrawScope.xOf(t: Double, t0: Double, t1: Double): Float = ((t - t0) / (t1 - t0) * size.width).toFloat()

/**
 * Both songs on the mix's clock (automix-overview.js drawStatic, in BitChord's look): A on top, B
 * under it, each its three-band waveform, upstream's frame over the mix, the beat grid inside it,
 * and the plan's curves: volume along each lane's floor, the shelves (0 dB at the top, −18 at the
 * bottom) and the filters (dashed, log Hz). A dragged song is drawn where it would mix.
 */
private fun DrawScope.drawEditorDecks(
    view: MixTransitionView,
    f: ForkV2EditorFeed.Planned,
    drag: EditorDrag?,
    D: Double,
    t0: Double,
    t1: Double,
    outColors: List<Color>,
    inColors: List<Color>,
) {
    val plan = f.plan
    val w = size.width
    val gap = 10.dp.toPx()
    val laneH = (size.height - gap * 3) / 2
    val tops = floatArrayOf(gap, gap * 2 + laneH)
    val x0 = xOf(0.0, t0, t1)
    val x1 = xOf(D, t0, t1)
    fun t(x: Float): Double = t0 + (x / w) * (t1 - t0)

    // the grey well behind the mix
    drawRoundRect(EditorPanel.copy(alpha = 0.85f), Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(14.dp.toPx()))

    // the beat grid inside the frame
    if (plan.style != "crossfade" && plan.bpm > 0) {
        val b = 60 / plan.bpm
        var k = 0
        while (k * b <= D + 1e-6) {
            val x = xOf(k * b, t0, t1)
            if (x in 0f..w) {
                drawLine(Color.White.copy(alpha = if (k % 4 == 0) 0.14f else 0.06f), Offset(x, tops[0]), Offset(x, tops[1] + laneH), 1.dp.toPx())
            }
            k++
        }
    }

    val blend = plan.style == "blend"
    val glide = plan.glide
    val outRate = if (blend && glide == null && plan.outRate > 0) plan.outRate else 1.0
    val inRate = when {
        blend && glide != null && glide.q > 0 && plan.beatSec > 0 -> plan.bars * 4 * plan.beatSec / glide.q / D
        blend && plan.rate > 0 -> plan.rate
        else -> 1.0
    }
    val outBase = if (drag?.out == true) drag.pos.sec else plan.mixOutTrackTime
    val inBase = if (drag?.out == false) drag.pos.sec else plan.mixInTrackTime
    val curves = plan.curves
    fun gain(out: Boolean, t: Double): Double = when {
        t < 0 -> if (out) 1.0 else 0.0
        t > D -> if (out) 0.0 else 1.0
        else -> G.curveAt((if (out) curves?.out else curves?.`in`)?.get("gain"), t, false, if (out) 1 - t / D else t / D)
    }

    for (lane in 0..1) {
        val out = lane == 0
        val deck = if (out) view.outgoing else view.incoming
        val colors = if (out) outColors else inColors
        val top = tops[lane]
        val centre = top + laneH / 2
        val half = laneH / 2 * 0.86f
        val bins = deck.low.size
        if (bins > 0 && deck.waveEnd > deck.waveStart) {
            val perBin = (deck.waveEnd - deck.waveStart) / bins
            val step = 2.dp.toPx()
            var x = 0f
            while (x < w) {
                val tm = t(x + step / 2)
                val song = if (out) {
                    if (tm < 0) outBase + tm else if (tm <= D) outBase + tm * outRate else outBase + D * outRate + (tm - D)
                } else {
                    if (tm < 0) inBase + tm else if (tm <= D) inBase + tm * inRate else inBase + D * inRate + (tm - D)
                }
                val i = ((song - deck.waveStart) / perBin).toInt()
                if (song >= deck.waveStart && i in 0 until bins) {
                    val silent = if (out) tm > D else tm < 0
                    val a = when {
                        silent -> EditorSilentAlpha
                        tm in 0.0..D -> 0.55f + 0.45f * gain(out, tm).toFloat().coerceIn(0f, 1f)
                        else -> 1f
                    }
                    val cw = step + 0.6f
                    column(x, cw, centre, deck.low[i] * half, colors[0].copy(alpha = a))
                    column(x, cw, centre, deck.mid[i] * half, colors[1].copy(alpha = a))
                    column(x, cw, centre, deck.high[i] * half, colors[2].copy(alpha = a))
                }
                x += step
            }
        } else {
            drawLine(colors[1].copy(alpha = 0.35f), Offset(0f, centre), Offset(w, centre), 1.dp.toPx())
        }

        // the volume lane, the whole width: full before the mix on A, silent before it on B
        val floor = top + laneH - 1
        val span = laneH - 4
        val line = Path()
        var x = 0f
        while (x <= w) {
            val y = (floor - gain(out, t(x)) * span).toFloat()
            if (x == 0f) line.moveTo(x, y) else line.lineTo(x, y)
            x += 2f
        }
        drawPath(line, VolumeColor, style = Stroke(1.5.dp.toPx()))

        // the EQ and effect lanes, inside the frame
        val side = if (out) curves?.out else curves?.`in`
        if (side != null) {
            val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            fun lane(pts: Array<DoubleArray>?, color: Color, geo: Boolean, height: (Double) -> Double) {
                if (pts == null || pts.isEmpty()) return
                val p = Path()
                val from = max(0f, x0)
                val to = minOf(w, x1)
                var px = from
                var first = true
                while (px <= to) {
                    val y = (top + 2 + (1 - height(G.curveAt(pts, t(px), geo, 0.0))) * (laneH - 6)).toFloat()
                    if (first) p.moveTo(px, y) else p.lineTo(px, y)
                    first = false
                    px += 2f
                }
                drawPath(p, color, style = Stroke(1.5.dp.toPx(), pathEffect = if (geo) dashed else null))
            }
            clipRect(0f, top, w, top + laneH) {
                lane(side["bassDb"], EqColor, false, G::dbHeight)
                lane(side["highDb"], EqColor.copy(alpha = 0.67f), false, G::dbHeight)
                lane(side["lowpassHz"], EffectColor, true, G::lowpassHeight)
                lane(side["highpassHz"], EffectColor, true, G::highpassHeight)
            }
        }

        // upstream's frame: A's open on the left (it was already playing), B's on the right (it plays on)
        val frame = Path().apply {
            if (out) {
                moveTo(x0, top)
                lineTo(x1, top)
                lineTo(x1, top + laneH)
            } else {
                moveTo(x0, top + laneH)
                lineTo(x0, top)
                lineTo(x1, top)
            }
        }
        drawPath(frame, EditorBlue, style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round))
    }
}

private fun DrawScope.column(x: Float, width: Float, centre: Float, extent: Float, color: Color) {
    if (extent <= 0.3f) return
    drawRect(color, topLeft = Offset(x, centre - extent), size = Size(width, extent * 2f))
}

// -------------------------------------------------------------------------------------------------
// the controls

/**
 * The editor's controls under the decks: presets, Customize (the three lanes), beat match, length,
 * the window's nudges, then Preview, Lock and Reset to Auto with the note that says what the edit
 * did. Edits are saved for the pair at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ForkTransitionEditor(live: Boolean, view: MixTransitionView?, modifier: Modifier = Modifier) {
    val m = editorModel(view, live)
    val ov = m.ov
    val f = m.feed
    val plan = f?.plan
    val on = m.editable
    val touch = drawerFollowsListScroll
    val drag = ForkEditorUi.drag?.takeIf { it.pair == m.pairKey }
    val refused = ForkEditorUi.refused?.takeIf { it.first == m.pairKey }?.second

    // Preview: the existing rule (ForkMixPicks): only while the mix is more than 25 s off
    val previewAt = when {
        m.planned == null -> null
        m.playsLoop -> m.loop?.loopStartSec?.minus(ForkMixPicks.PREVIEW_LOOP_LEAD_SEC)
        else -> m.planned.previewAtSec
    }
    val leftSec = when {
        m.planned == null || previewAt == null -> null
        m.playsLoop -> previewAt + ForkMixPicks.PREVIEW_LOOP_LEAD_SEC - m.planned.positionSec
        else -> previewAt + ForkMixPicks.PREVIEW_BLEND_LEAD_SEC - m.planned.positionSec
    }
    val canPreview = on && !m.mixing && previewAt != null && previewAt > 0 && leftSec != null &&
        leftSec >= ForkMixPicks.PREVIEW_MIN_LEFT_SEC && ForkMixPicks.seek != null

    val note = when {
        !m.styleOn -> "Pick Automix v2 in Settings to edit the mix for each pair of songs."
        f == null || plan == null -> "Planning the mix…"
        drag != null -> {
            val other = if (drag.out) G.positionOf(plan.mixInTrackTime, f.gridIn, false, false) else G.positionOf(plan.mixOutTrackTime, f.gridOut, false, false)
            (if (drag.out) G.readout(drag.pos, other) else G.readout(other, drag.pos)) + (drag.limit?.let { " · held: $it" } ?: "")
        }
        refused != null -> refused
        m.mixing -> "Mixing now: an edit is for the next time these two play."
        m.preset == "loop" && !m.playsLoop -> if (m.loopNone) "No loop mix for these two: ${m.loop?.why}" else "Preparing the loop mix…"
        !plan.warnings.isNullOrEmpty() -> "⚠ " + plan.warnings!!.joinToString("; ")
        ov != null && ov.bars > 0 && plan.bars > 0 && ov.bars != plan.bars && !m.isLoop && plan.style != "crossfade" ->
            "${ov.bars} bars asked; ${plan.bars} fit here."
        ov != null && m.hasWindow && !m.isLoop -> G.readout(
            ov.out ?: G.positionOf(plan.mixOutTrackTime, f.gridOut, false, false),
            ov.`in` ?: G.positionOf(plan.mixInTrackTime, f.gridIn, false, false),
        )
        ov?.family != null -> "An older pick (${if (ov.family == "filter") "the filter mix" else "Automix's own blend"}) is kept for these two: a preset replaces it."
        ov != null -> if (ov.lock) "Locked for these two songs." else "Kept for these two songs."
        m.playsLoop -> "Loop mix: a loop mix sets its own length."
        plan.reason != null -> plan.reason!!
        m.windowEditable -> "Drag a song under the mix to move the window (${if (touch) "long-press" else "Shift"}: beats)."
        else -> ""
    }

    Column(modifier.fillMaxWidth().alpha(if (m.styleOn) 1f else 0.4f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = if (touch) 210.dp else 340.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            EditorRow("Preset") {
                for ((id, name) in G.PRESET_NAMES) {
                    // Custom appears once lanes were edited
                    if (id == "custom" && m.preset != "custom") continue
                    val text = if (id == "auto" && m.preset == "auto") G.autoName(plan, m.playsLoop) else name
                    EditorChip(text, lit = m.preset == id, enabled = on && !(id == "loop" && m.loopNone), color = PresetColors[id] ?: EditorBlue) {
                        if (id == "custom") return@EditorChip
                        edit(m, presetPicked = true) { preset = id }
                    }
                }
            }
            // Customize: the three lanes, folded
            val laneOn = on && m.preset != "loop"
            Text(
                if (ForkEditorUi.customOpen) "Customize ▾" else "Customize ▸",
                color = Color.White.copy(alpha = if (laneOn) 0.75f else 0.3f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = laneOn) { ForkEditorUi.customOpen = !ForkEditorUi.customOpen }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            )
            if (ForkEditorUi.customOpen) {
                val lanes = G.lanesNow(ov)
                for ((k, lane) in listOf("volume", "eq", "effect").withIndex()) {
                    val color = listOf(VolumeColor, EqColor, EffectColor)[k]
                    EditorRow(listOf("Volume", "EQ", "Effect")[k], dot = color) {
                        LaneChip(lanes[k], ForkV2MixOverride.options(lane), lit = m.preset == "custom", enabled = laneOn, color = color) { v ->
                            edit(m, presetPicked = true) {
                                preset = "custom"
                                volume = lanes[0]
                                eq = lanes[1]
                                effect = lanes[2]
                                when (k) {
                                    0 -> volume = v
                                    1 -> eq = v
                                    else -> effect = v
                                }
                            }
                        }
                    }
                }
            }
            val bm = ov?.beatMatch ?: "auto"
            EditorRow("Beat match") {
                for ((id, name) in listOf("auto" to "Auto", "sync" to "Sync", "glide" to "Glide", "off" to "Off")) {
                    EditorChip(name, lit = bm == id, enabled = on && m.preset != "loop") { edit(m, false) { beatMatch = id } }
                }
            }
            val bars = ov?.bars ?: 0
            EditorRow("Length") {
                for (b in listOf(0) + ForkV2MixOverride.BARS.toList()) {
                    EditorChip(if (b == 0) "Auto" else "$b", lit = bars == b, enabled = on && !(m.isLoop && b != 0)) { edit(m, false) { this.bars = b } }
                }
            }
            EditorRow("Window") {
                val w = m.windowEditable
                EditorChip("A −10 ms", false, w) { nudge(m, true, -1) }
                EditorChip("A +10 ms", false, w) { nudge(m, true, 1) }
                EditorChip("B −10 ms", false, w) { nudge(m, false, -1) }
                EditorChip("B +10 ms", false, w) { nudge(m, false, 1) }
                EditorChip("Auto", lit = !m.hasWindow, enabled = w && m.hasWindow) { edit(m, false) { out = null; `in` = null } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorChip("▶ Preview", false, canPreview) {
                previewAt?.let { at -> ForkMixPicks.seek?.invoke((at * 1000).roundToLong().coerceAtLeast(0L)) }
            }
            EditorChip(if (ov?.lock == true) "Locked" else "Lock", lit = ov?.lock == true, enabled = on && ov != null) {
                val ff = m.feed ?: return@EditorChip
                val cur = ForkV2OverrideStore.get(ff.fromId, ff.toId) ?: return@EditorChip
                ForkEditorUi.refused = ForkV2OverrideStore.setLocked(ff.fromId, ff.toId, !cur.lock)?.let { m.pairKey!! to it }
            }
            EditorChip("Reset to Auto", false, enabled = on && ov != null) {
                val ff = m.feed ?: return@EditorChip
                ForkV2OverrideStore.reset(ff.fromId, ff.toId)
                ForkEditorUi.refused = null
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(note, color = EditorNote, fontSize = 12.sp, maxLines = 3)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorRow(label: String, dot: Color? = null, chips: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Row(Modifier.width(76.dp).padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(5.dp))
            }
            Text(label, color = EditorNote, fontSize = 12.sp, maxLines = 1)
        }
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) { chips() }
    }
}

@Composable
private fun EditorChip(text: String, lit: Boolean, enabled: Boolean, color: Color = EditorBlue, onClick: () -> Unit) {
    // a yellow chip (the Blend preset) reads in black
    val dark = lit && color == EqColor
    Text(
        text = text,
        color = if (dark) Color(0xFF151515) else if (lit) Color.White else Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp,
        fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .alpha(if (enabled || lit) 1f else 0.35f)
            .clip(RoundedCornerShape(50))
            .background(if (lit) color else EditorChipOff)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** One lane's shape: a chip with the shape's name that opens the list of shapes (the extension's dropdown). */
@Composable
private fun LaneChip(value: String, options: List<String>, lit: Boolean, enabled: Boolean, color: Color, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        EditorChip("${G.SHAPE_NAMES[value] ?: value} ▾", lit = lit, enabled = enabled, color = color) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) {
                DropdownMenuItem(
                    text = { Text(G.SHAPE_NAMES[o] ?: o, fontWeight = if (o == value) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        if (o != value || !lit) onPick(o)
                    },
                )
            }
        }
    }
}

/**
 * Fork: the mix popup's size now that it holds the transition editor (owner, 2026-11-19: "make
 * Automix overview wider"). Upstream capped the card at 480dp with 26dp side margins, sized for
 * its two decks; the editor's decks are a timeline whose window is dragged by the bar, so width is
 * resolution. The PC gets up to 760dp; the phone keeps the full screen minus 10dp a side (a phone
 * is narrower than 480dp anyway, so its margins were the only width left to give).
 */
internal object ForkMixPopup {
    val maxWidth = 760.dp
    val sidePadding get() = if (drawerFollowsListScroll) 10.dp else 26.dp
}
