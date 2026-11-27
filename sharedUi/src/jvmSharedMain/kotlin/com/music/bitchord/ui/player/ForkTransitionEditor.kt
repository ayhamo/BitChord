package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed as keyCtrl
import androidx.compose.ui.input.key.isMetaPressed as keyMeta
import androidx.compose.ui.input.key.isShiftPressed as keyShift
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import com.music.bitchord.playback.smart.automixv2.ForkV2DrawEditor
import com.music.bitchord.playback.smart.automixv2.ForkV2Drawing
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
import com.music.bitchord.playback.smart.ForkAnalysisProgress
import com.music.bitchord.playback.smart.ForkMixPicks
import com.music.bitchord.playback.smart.automixv2.ForkV2EditorFeed
import com.music.bitchord.playback.smart.automixv2.ForkV2EditorGeometry as G
import com.music.bitchord.playback.smart.automixv2.ForkV2MixFile
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
private val FxColor = Color(0xFFE8E8E8)
private val LoopingColor = Color(0xFF5FD39A)

/** A lane's colour (editor.md §2, §12, §13). */
private fun laneColor(lane: String) = when (lane) {
    "volume" -> VolumeColor
    "eq" -> EqColor
    "fx" -> FxColor
    "looping" -> LoopingColor
    else -> EffectColor
}

/** editor.md §2: each preset's chip colour (Auto and Custom: the frame's). */
private val PresetColors = mapOf(
    "auto" to EditorBlue, "smooth" to VolumeColor, "rise" to EffectColor, "blend" to EqColor, "wave" to EffectColor,
    "melt" to EffectColor, "slam" to Color(0xFFE5534B), "loop" to Color(0xFF3FB950), "custom" to EditorBlue,
    "afterglow" to EffectColor, "surge" to EffectColor, "dissolve" to EffectColor, "none" to EditorBlue,
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

    // editor.md §10, drawn curves (automix-overview.js drawKey, pdrag, pendingDraw, undo)
    /** The curve being drawn on (gain, bassDb, highDb, lowpassHz, highpassHz), null: none chosen ("Done"). */
    var drawKey by mutableStateOf<String?>(null)
    var pointDrag by mutableStateOf<PointDrag?>(null)

    /** Draw picked on a loop mix: (pair, lane), started once the blend is planned. */
    var pendingDraw by mutableStateOf<Pair<String, String>?>(null)

    /** One step back per drawing edit while the popup is open, newest last. */
    val undo = mutableStateListOf<ForkV2DrawEditor.Step>()

    /** A shape or preset waiting on "Replace the drawing?" (editor.md §10): the question and what Yes does. */
    internal class ReplaceAsk(val question: String, val yes: () -> Unit)

    internal var askReplace by mutableStateOf<ReplaceAsk?>(null)
    var repeatFrom by mutableStateOf("0")
    var repeatLen by mutableStateOf("2")

    /**
     * The popup closed: the drawing's tools start afresh next time (the extension's hide()). The replace question and a
     * song drag go too: both name the pair they were for, and a question left open at the close came back on the next
     * opening, over any pair, with its Yes still saving into the old one (audit 2026-11-26 3a #8).
     */
    fun closeDrawing() {
        drawKey = null
        pointDrag = null
        pendingDraw = null
        askReplace = null
        drag = null
        undo.clear()
        shownPair = null
    }

    /** The pair the popup last showed (null: none yet), for [showing]. */
    private var shownPair: String? = null

    /**
     * The popup now shows [pair]. Another pair than before (the song moved on with the popup open)
     * drops the chosen curve and a point drag: the next pair opened in point-drawing mode when it
     * had the same lane drawn (audit 2026-11-24). A pair that is briefly none (the plan being
     * worked out again after a save) is not a change, so drawing survives its own edits.
     */
    fun showing(pair: String?) {
        if (pair == null) return
        if (shownPair != null && pair != shownPair) stopDrawing()
        shownPair = pair
    }

    /**
     * "Reset to Auto", or another pair: no curve chosen, nothing dragged, no Draw waiting on a loop mix's blend and no
     * replace question. A Draw picked on a loop mix stayed armed through Reset to Auto and started drawing the next time
     * Custom was picked on the pair (audit 2026-11-26 3a #8).
     */
    fun stopDrawing() {
        drawKey = null
        pointDrag = null
        pendingDraw = null
        askReplace = null
    }
}

/** A point of the chosen curve being dragged: the drawing as it is under the finger. */
internal class PointDrag(val pair: String, val key: String, val out: Boolean, val i: Int, val d: ForkV2Drawing, val moved: Boolean)

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

    /** The length chips, lanes and window are off for a loop mix (editor.md §2), and for No transition. */
    val isLoop: Boolean = preset == "loop" || preset == "none" || playsLoop

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
    val ov = remember(f?.fromId, f?.toId, changes) { f?.let { stored(it) } }
    LaunchedEffect(f?.fromId, f?.toId) {
        // the songs' names for a mix file's export (mix-file.md rule 8); an effect, not a `remember` (it was): a write
        // to a shared store has no place in composition (audit 2026-11-26 3a #7)
        if (f != null && view != null) {
            ForkMixFiles.know(f.fromId, view.outgoing.title, view.outgoing.artist)
            ForkMixFiles.know(f.toId, view.incoming.title, view.incoming.artist)
        }
    }
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

/**
 * The pair's saved override on this app's grid: a mix file's beats counted at half or double this song's tempo are
 * folded (mix-file.md rule 4), as the planner plays them, so the drawing shows and an edit saves on this grid.
 */
private fun stored(f: ForkV2EditorFeed.Planned): ForkV2MixOverride? =
    ForkV2MixFile.fold(ForkV2OverrideStore.get(f.fromId, f.toId), f.gridOut?.bpm).ov

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
    save(f, G.edited(stored(f), presetPicked, change))
}

// -------------------------------------------------------------------------------------------------
// drawn curves (editor.md §10 rule 6)

/** The curve being drawn on, when the shown pair's plan can be drawn on and that curve's lane is drawn. */
private fun activeDrawKey(m: EditorModel): String? {
    val key = ForkEditorUi.drawKey ?: return null
    val f = m.feed ?: return null
    if (!m.editable || m.playsLoop || f.plan.style == "loop") return null
    return key.takeIf { m.ov?.drawing(ForkV2Drawing.CURVES[key]!!.lane) != null }
}

/** The drawn lanes of the shown pair's override. */
private fun drawnLanes(m: EditorModel): List<String> =
    if (m.ov?.preset == "custom") ForkV2MixOverride.LANES.filter { m.ov.drawing(it) != null } else emptyList()

/** Draw in a lane's dropdown: the lane's shape as points, saved; its first curve chosen. */
private fun startDraw(m: EditorModel, lane: String) {
    val f = m.feed ?: return
    if (!m.editable) return
    val pair = m.pairKey!!
    if (f.plan.style == "loop" || m.playsLoop) {
        // a loop mix (Auto chose one): Custom first, and the drawing once the blend is planned
        ForkEditorUi.pendingDraw = pair to lane
        val l = G.lanesNow(stored(f))
        edit(m, presetPicked = true) {
            drawings.clear()
            preset = "custom"
            volume = l[0]
            eq = l[1]
            effect = l[2]
            fx = l[3].takeIf { it != "none" }
            looping = l[4].takeIf { it != "none" }
        }
        return
    }
    val cur = stored(f)
    val d = ForkV2DrawEditor.startDrawing(lane, cur, max(0.5, f.plan.durationSec), ForkV2DrawEditor.ctx(f.plan, f.gridOut))
    if (cur?.drawing(lane) == null) saveDrawing(m, lane, d)
    ForkEditorUi.drawKey = ForkV2Drawing.KEYS[lane]!!.first()
}

/** A drawing edit saved at once, with the length shown pinned (a drawing is made for its window) and an Undo step. */
private fun saveDrawing(m: EditorModel, lane: String, d: ForkV2Drawing) {
    val f = m.feed ?: return
    val cur = stored(f)
    val bars = ForkV2DrawEditor.pinnedBars(cur, f.plan)
    ForkEditorUi.undo.add(ForkV2DrawEditor.Step(m.pairKey!!, cur?.copy(), cur?.bars ?: 0, bars))
    while (ForkEditorUi.undo.size > ForkV2DrawEditor.UNDO_MAX) ForkEditorUi.undo.removeAt(0)
    save(f, ForkV2DrawEditor.withDrawing(cur, lane, d, bars))
}

/**
 * A shape in [lane]'s dropdown, or a preset other than Custom ([lane] null), over a drawing (editor.md §10
 * "Replacing a drawing", spec c6550d5): "Replace the drawing?" first. No leaves everything as it was; Yes puts the
 * whole override from before it on the Undo list, then [then] saves the choice. With nothing drawn there, [then] at once.
 */
private fun replacing(m: EditorModel, lane: String?, then: () -> Unit) {
    val f = m.feed ?: return
    val pair = m.pairKey ?: return
    val cur = stored(f)
    val n = ForkV2DrawEditor.replacedDrawings(cur, lane).size
    if (n == 0) return then()
    ForkEditorUi.askReplace = ForkEditorUi.ReplaceAsk(ForkV2DrawEditor.replaceQuestion(n)) {
        ForkEditorUi.undo.add(ForkV2DrawEditor.Step(pair, cur?.copy(), 0, 0, whole = true))
        while (ForkEditorUi.undo.size > ForkV2DrawEditor.UNDO_MAX) ForkEditorUi.undo.removeAt(0)
        then()
        ForkEditorUi.stopDrawing()
    }
}

/** Undo: the shown pair's last drawing edit back (another pair's steps are gone with it). */
private fun undoDraw(m: EditorModel) {
    val f = m.feed ?: return
    val u = ForkEditorUi.undo
    while (u.isNotEmpty() && u.last().pair != m.pairKey) u.removeAt(u.size - 1)
    if (u.isEmpty()) return
    val step = u.removeAt(u.size - 1)
    save(f, ForkV2DrawEditor.undone(stored(f), step))
}

/** Repeat beats a–b every n until the window's end, on the chosen curve, both decks. */
private fun repeatDraw(m: EditorModel) {
    val f = m.feed ?: return
    val key = activeDrawKey(m) ?: return
    val lane = ForkV2Drawing.CURVES[key]!!.lane
    val d = m.ov?.drawing(lane) ?: return
    val from = ForkEditorUi.repeatFrom.trim().replace(',', '.').toDoubleOrNull() ?: return
    val len = ForkEditorUi.repeatLen.trim().replace(',', '.').toDoubleOrNull() ?: return
    if (!(len > 0) || !(from >= 0)) return
    val c = ForkV2DrawEditor.ctx(f.plan, f.gridOut)
    val r = ForkV2DrawEditor.repeat(d, key, from, len, c.x(max(0.5, f.plan.durationSec), d.unit))
    if (r == null) {
        ForkEditorUi.refused = m.pairKey!! to ForkV2Drawing.TOO_MANY
        return
    }
    saveDrawing(m, lane, r)
}

/** The decks' canvas layout (automix-overview.js layout): a curve's height n in deck k's half, and back. */
private class DrawLayout(val laneH: Float, val tops: FloatArray) {
    fun y(key: String, k: Int, n: Double): Float =
        if (key == "gain") (tops[k] + laneH - 1 - n * (laneH - 4)).toFloat() else (tops[k] + 2 + (1 - n) * (laneH - 6)).toFloat()

    fun n(key: String, k: Int, y: Float): Double = (
        if (key == "gain") (tops[k] + laneH - 1 - y) / (laneH - 4) else 1 - (y - tops[k] - 2) / (laneH - 6)
        ).toDouble().coerceIn(0.0, 1.0)
}

private fun drawLayout(height: Float, gap: Float): DrawLayout {
    val laneH = (height - gap * 3) / 2
    return DrawLayout(laneH, floatArrayOf(gap, gap * 2 + laneH))
}

/** A 10 ms step of one song's position, from where the plan has it when nothing is saved yet. */
private fun nudge(m: EditorModel, out: Boolean, dir: Int) {
    val f = m.feed ?: return
    if (!m.windowEditable) return
    val ov = stored(f)
    val now = (if (out) ov?.out else ov?.`in`)
        ?: G.positionOf(if (out) f.plan.mixOutTrackTime else f.plan.mixInTrackTime, if (out) f.gridOut else f.gridIn, false, false)
    // held at the window's limits as a drag is (editor.md §6), not only at the 2 s nudge reach
    val r = G.nudge(now, dir, f.limits.lo(out), f.limits.hi(out), out)
    val pos = r.pos
    if (pos == null) {
        ForkEditorUi.refused = m.pairKey!! to (r.refused ?: G.NUDGE_REFUSED)
        return
    }
    val (o, i) = G.windowWith(ov, out, pos, f.plan.mixOutTrackTime, f.plan.mixInTrackTime, f.gridOut, f.gridIn)
    save(f, G.edited(ov, false) { this.out = o; this.`in` = i })
}

/**
 * What "Set" would save from the "B in after" box: its seconds (a comma read as the point) when they are a number and
 * differ from the [late] saved, else null and the chip is off. An empty box or a lone "." read as null, and
 * `null != late` left the chip lit with a click that did nothing (audit 2026-11-26 3a #10).
 */
internal fun lateToSet(text: String, late: Double): Double? =
    text.replace(',', '.').toDoubleOrNull()?.takeIf { it != late }

/** B's late entry set in seconds, 0 clearing it (automix-overview.js setLate): saved on the mix-in, placed where the plan has it if unsaved. */
private fun setLate(m: EditorModel, sec: Double) {
    val f = m.feed ?: return
    if (!m.windowEditable) return
    val ov = stored(f)
    val (o, i) = G.withLate(ov, sec, f.plan.mixOutTrackTime, f.plan.mixInTrackTime, f.gridOut, f.gridIn) ?: return
    save(f, G.edited(ov, false) { out = o; `in` = i })
}

/** A finished drag: the window saved with the dragged side where it was let go (a drag that moved nothing saves nothing). */
private fun commit(d: EditorDrag) {
    val f = ForkV2EditorFeed.planned.value ?: return
    if (ForkV2OverrideStore.pairKey(f.fromId, f.toId) != d.pair) return // the song changed under the drag
    if (abs(d.pos.sec - d.base) < 0.005) return
    val ov = stored(f)
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
    val title = when { // editor.md §7: the header names the plan
        m.playsLoop -> m.loop?.header ?: "Loop mix"
        plan == null -> "Automix"
        else -> G.headline(plan)
    }
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

/** Whether the editor's picture has room to draw or take a touch: every x is a fraction of its width. */
internal fun editorSized(width: Float, height: Float): Boolean = width > 0f && height > 0f

/**
 * Where the bars chip's left edge goes: centred on [mid], kept 2 px inside the picture. A picture
 * narrower than the chip (a first layout pass, a narrow window) centres it instead: the clamp's
 * range was empty there and `coerceIn` threw.
 */
internal fun chipLeft(mid: Float, chipWidth: Float, width: Float): Float =
    if (width < chipWidth + 4f) (width - chipWidth) / 2 else (mid - chipWidth / 2).coerceIn(2f, width - chipWidth - 2f)

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
    PlayerBackHandler(enabled = ForkEditorUi.pointDrag != null) { ForkEditorUi.pointDrag = null }
    // a drawing: the canvas takes the keys once pressed (Ctrl+Z), and a second tap soon after is a double-click
    val focus = remember { FocusRequester() }
    val lastTap = remember { mutableStateOf<Pair<Long, Offset>?>(null) }
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
                .onPreviewKeyEvent { e ->
                    // Ctrl+Z: a drawing's last edit back (editor.md §10 rule 6)
                    if (e.type == KeyEventType.KeyDown && (e.keyCtrl || e.keyMeta) && !e.keyShift && e.key == Key.Z &&
                        ForkEditorUi.undo.any { it.pair == pairKey }
                    ) {
                        undoDraw(model)
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(focus)
                .focusable()
                .pointerInput(pairKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Not laid out yet (0 wide or high): every position / width below is NaN,
                        // which passed the frame's bounds check and could store Zoom(NaN, NaN).
                        if (!editorSized(size.width.toFloat(), size.height.toFloat())) return@awaitEachGesture
                        val fNow = feed
                        // a chosen curve: the frame is for its points (editor.md §10 rule 6)
                        val drawKey = activeDrawKey(model)
                        if (drawKey != null) {
                            runCatching { focus.requestFocus() }
                            val lane = ForkV2Drawing.CURVES[drawKey]!!.lane
                            val d0 = model.ov?.drawing(lane) ?: return@awaitEachGesture
                            val w = size.width.toDouble()
                            val z = zoom.value
                            val all = mixSec + 2 * max(4.0, mixSec * 0.24)
                            val t0 = -max(4.0, mixSec * 0.24) + z.f0 * all
                            val t1 = -max(4.0, mixSec * 0.24) + z.f1 * all
                            fun xPx(t: Double): Double = (t - t0) / (t1 - t0) * w
                            fun tOf(px: Double): Double = t0 + px / w * (t1 - t0)
                            val lay = drawLayout(size.height.toFloat(), 10.dp.toPx())
                            val k = if (down.position.y < size.height / 2f) 0 else 1
                            val out = k == 0
                            val c = ForkV2DrawEditor.ctx(fNow.plan, fNow.gridOut)
                            val pts = d0.points(drawKey, out)
                            val hitPx = (if (touch) 14.dp else 7.dp).toPx()
                            var hit: Int? = null
                            var best = hitPx.toDouble()
                            pts.forEachIndexed { i, p ->
                                val dx = xPx(c.sec(p[0], d0.unit)) - down.position.x
                                val dy = (lay.y(drawKey, k, ForkV2DrawEditor.norm(drawKey, p[1])) - down.position.y).toDouble()
                                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                if (dist <= best) {
                                    best = dist
                                    hit = i
                                }
                            }
                            ForkEditorUi.refused = null
                            // a double-click (double-tap) or a right-click on a point deletes it
                            val tap = lastTap.value
                            lastTap.value = down.uptimeMillis to down.position
                            val double = tap != null && down.uptimeMillis - tap.first <= viewConfiguration.doubleTapTimeoutMillis &&
                                (down.position - tap.second).getDistance() <= hitPx * 2
                            if (currentEvent.buttons.isSecondaryPressed || double) {
                                down.consume()
                                lastTap.value = null
                                val i = hit ?: return@awaitEachGesture
                                val left = ForkV2DrawEditor.delete(drawKey, pts, i)
                                if (left == null) ForkEditorUi.refused = pairKey to ForkV2DrawEditor.VOLUME_KEEPS_ONE
                                else saveDrawing(model, lane, d0.with(drawKey, out, left))
                                return@awaitEachGesture
                            }
                            var pd: PointDrag
                            val i0 = hit
                            if (i0 == null) {
                                // points go inside the frame
                                val t = tOf(down.position.x.toDouble())
                                if (t < 0 || t > mixSec) return@awaitEachGesture
                                if (pts.size >= ForkV2Drawing.MAX_POINTS) {
                                    ForkEditorUi.refused = pairKey to ForkV2Drawing.TOO_MANY
                                    return@awaitEachGesture
                                }
                                val p = ForkV2DrawEditor.pointAt(
                                    drawKey, c.x(t, d0.unit), lay.n(drawKey, k, down.position.y), d0.unit, currentEvent.keyboardModifiers.isShiftPressed,
                                )
                                val (added, i) = ForkV2DrawEditor.add(pts, p) ?: return@awaitEachGesture
                                pd = PointDrag(pairKey, drawKey, out, i, d0.with(drawKey, out, added), moved = true)
                            } else {
                                pd = PointDrag(pairKey, drawKey, out, i0, d0, moved = false)
                            }
                            ForkEditorUi.pointDrag = pd
                            down.consume()
                            var cancelled = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2 || ForkEditorUi.pointDrag == null) {
                                    cancelled = true // a second finger, or Esc: dropped, nothing saved
                                    ForkEditorUi.pointDrag = null
                                    continue
                                }
                                if (cancelled) continue
                                val ch = pressed[0]
                                if (!pd.moved && (ch.position - down.position).getDistance() <= viewConfiguration.touchSlop) continue
                                val t = tOf(ch.position.x.toDouble()).coerceIn(0.0, mixSec)
                                val p = ForkV2DrawEditor.pointAt(drawKey, c.x(t, d0.unit), lay.n(drawKey, k, ch.position.y), d0.unit, ev.keyboardModifiers.isShiftPressed)
                                pd = PointDrag(pairKey, drawKey, out, pd.i, pd.d.with(drawKey, out, ForkV2DrawEditor.move(pd.d.points(drawKey, out), pd.i, p)), moved = true)
                                ForkEditorUi.pointDrag = pd
                                ch.consume()
                            }
                            ForkEditorUi.pointDrag = null
                            if (!cancelled && pd.moved && ForkV2EditorFeed.planned.value?.let { ForkV2OverrideStore.pairKey(it.fromId, it.toId) } == pairKey) {
                                saveDrawing(model, lane, pd.d)
                            }
                            return@awaitEachGesture
                        }
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
            if (!editorSized(size.width, size.height)) return@Canvas
            val z = zoom.value
            val all = D + 2 * pad
            val t0 = -pad + z.f0 * all
            val t1 = -pad + z.f1 * all
            drawEditorDecks(view, f, drag, D, t0, t1, outColors, inColors)
            activeDrawKey(m)?.let { key -> drawPoints(m, f, key, D, t0, t1) }
            // the bars chip between the lanes, under the middle of the frame
            val x0 = xOf(0.0, t0, t1)
            val x1 = xOf(D, t0, t1)
            val text = measurer.measure(chip, TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            val cw = text.size.width + 20.dp.toPx()
            val ch = text.size.height + 6.dp.toPx()
            val cx = chipLeft((x0 + x1) / 2, cw, size.width)
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
            LaneKey("Filter", EffectColor)
            LaneKey("Effects", FxColor)
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

    val outBase = if (drag?.out == true) drag.pos.sec else plan.mixOutTrackTime
    val inBase = if (drag?.out == false) drag.pos.sec else plan.mixInTrackTime
    val late = plan.inDelaySec // B's late entry (mix-file.md rule 2): B is silent, and not drawn, until then
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
                // (the plan's rates in the window for any style, each song's playlist speed outside it: spotify-mixes.md rule 10)
                val song = G.songAt(out, tm, D, if (out) outBase else inBase, late, plan)
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
                lane(side["midDb"], EqColor.copy(alpha = 0.47f), false, G::dbHeight)
                lane(side["lowpassHz"], EffectColor, true, G::lowpassHeight)
                lane(side["highpassHz"], EffectColor, true, G::highpassHeight)
                // editor.md §12 rule 6: the noise and the sends 0 at the lane's bottom, 1 at its top
                lane(side["noise"], EffectColor.copy(alpha = 0.67f), false) { it }
                lane(side["echoSend"], FxColor, false) { it }
                lane(side["reverbSend"], FxColor.copy(alpha = 0.73f), false) { it }
                lane(side["delaySend"], FxColor.copy(alpha = 0.53f), false) { it }
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

/**
 * The chosen curve over the decks (automix-overview.js drawPoints): bold in its lane's colour with its
 * points, on both decks' halves, inside the frame; the one being dragged white, as it moves.
 */
private fun DrawScope.drawPoints(m: EditorModel, f: ForkV2EditorFeed.Planned, key: String, D: Double, t0: Double, t1: Double) {
    val c = ForkV2Drawing.CURVES[key]!!
    val pd = ForkEditorUi.pointDrag?.takeIf { it.pair == m.pairKey && it.key == key }
    val d = pd?.d ?: m.ov?.drawing(c.lane) ?: return
    val ctx = ForkV2DrawEditor.ctx(f.plan, f.gridOut)
    val lay = drawLayout(size.height, 10.dp.toPx())
    val color = laneColor(c.lane)
    val x0 = xOf(0.0, t0, t1)
    val x1 = xOf(D, t0, t1)
    clipRect(max(0f, x0), 0f, minOf(size.width, x1), size.height) {
        for (k in 0..1) {
            val out = k == 0
            val pts = d.points(key, out)
            if (pts.isEmpty()) continue
            val xy = pts.map { p -> Offset(xOf(ctx.sec(p[0], d.unit), t0, t1), lay.y(key, k, ForkV2DrawEditor.norm(key, p[1]))) }
            val path = Path().apply {
                moveTo(x0, xy[0].y)
                for (o in xy) lineTo(o.x, o.y)
                lineTo(x1, xy.last().y)
            }
            drawPath(path, color, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
            xy.forEachIndexed { i, o ->
                val dragged = pd != null && pd.out == out && pd.i == i
                drawCircle(if (dragged) Color.White else color, 4.dp.toPx(), o)
                drawCircle(Color(0xFF15141C), 4.dp.toPx(), o, style = Stroke(1.dp.toPx()))
            }
        }
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

    // editor.md §10: the drawing's state lives while the popup is open
    DisposableEffect(Unit) { onDispose { ForkEditorUi.closeDrawing() } }
    LaunchedEffect(m.pairKey) { ForkEditorUi.showing(m.pairKey) }
    // Draw picked on a loop mix: once the blend with Custom is planned, the drawing starts on it
    val pending = ForkEditorUi.pendingDraw
    LaunchedEffect(pending, plan, m.pairKey) {
        if (pending == null) return@LaunchedEffect
        if (pending.first != m.pairKey || !m.editable) {
            ForkEditorUi.pendingDraw = null
        } else if (plan != null && plan.style != "loop" && !m.playsLoop && plan.preset == "custom") {
            ForkEditorUi.pendingDraw = null
            startDraw(m, pending.second)
        }
    }

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

    // editor.md §11: the analysis row under the decks, above the controls; not greyed with them (BitChord's
    // own style analyses the same songs)
    val mixingNow = live || PlayerSettings.smartMixInProgress.collectAsStateWithLifecycle().value
    Column(modifier.fillMaxWidth()) {
    ForkProgressRow(hidden = mixingNow, modifier = Modifier.padding(bottom = 6.dp))
    Column(Modifier.fillMaxWidth().alpha(if (m.styleOn) 1f else 0.4f)) {
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
                        replacing(m, null) { edit(m, presetPicked = true) { preset = id } }
                    }
                }
            }
            // Customize: the three lanes, folded
            val laneOn = on && m.preset != "loop" && m.preset != "none"
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
                // editor.md §2: Spotify's lane names; Looping is a shape only (§13), never drawn
                for ((k, lane) in ForkV2MixOverride.LANES.withIndex()) {
                    val color = laneColor(lane)
                    val shapes = ForkV2MixOverride.options(lane) + if (lane == "looping") emptyList() else listOf(ForkV2MixOverride.DRAW)
                    EditorRow(listOf("Volume", "EQ", "Filter", "Effects", "Looping")[k], dot = color) {
                        LaneChip(lanes[k], shapes, lit = m.preset == "custom", enabled = laneOn, color = color) { v ->
                            if (v == ForkV2MixOverride.DRAW) {
                                startDraw(m, lane) // editor.md §10: the lane's shape as points
                                return@LaneChip
                            }
                            replacing(m, lane) {
                                edit(m, presetPicked = true) {
                                    if (preset != "custom") drawings.clear()
                                    preset = "custom"
                                    volume = lanes[0]
                                    eq = lanes[1]
                                    effect = lanes[2]
                                    fx = lanes[3].takeIf { it != "none" }
                                    looping = lanes[4].takeIf { it != "none" }
                                    setLane(lane, v)
                                }
                            }
                        }
                    }
                }
            }
            // the drawing's tools (editor.md §10 rule 6): which curve, Repeat, Undo
            val drawn = drawnLanes(m)
            if (on && drawn.isNotEmpty() && !m.playsLoop && plan?.style != "loop") {
                val key = activeDrawKey(m)
                EditorRow("Draw") {
                    for ((k, c) in ForkV2Drawing.CURVES) {
                        if (c.lane !in drawn) continue
                        val color = laneColor(c.lane)
                        EditorChip(c.chip, lit = key == k, enabled = true, color = color) {
                            ForkEditorUi.drawKey = k
                            ForkEditorUi.pointDrag = null
                        }
                    }
                    EditorChip("Done", lit = key == null, enabled = key != null) {
                        ForkEditorUi.drawKey = null
                        ForkEditorUi.pointDrag = null
                    }
                }
                val unit = if (f != null && ForkV2DrawEditor.ctx(f.plan, f.gridOut).unit == "sec") "s" else "beats"
                EditorRow("") {
                    Text("Repeat from", color = EditorNote, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    NumberField(ForkEditorUi.repeatFrom) { ForkEditorUi.repeatFrom = it }
                    Text("every", color = EditorNote, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    NumberField(ForkEditorUi.repeatLen) { ForkEditorUi.repeatLen = it }
                    Text(unit, color = EditorNote, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    EditorChip("Repeat", false, enabled = key != null) { repeatDraw(m) }
                    EditorChip("Undo", false, enabled = ForkEditorUi.undo.any { it.pair == m.pairKey }) { undoDraw(m) }
                }
                Text(
                    if (key != null) {
                        "${if (touch) "Tap" else "Click"} in the frame to add a point to ${ForkV2Drawing.CURVES[key]!!.chip.lowercase()}, drag to move it, " +
                            (if (touch) "double-tap" else "double-click or right-click") + " to delete" + (if (touch) "" else " (Shift: free; Ctrl+Z: undo)") +
                            ". Done moves the window again."
                    } else {
                        "Pick a line to draw on."
                    },
                    color = EditorNote,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 76.dp, bottom = 4.dp),
                )
            }
            val bm = ov?.beatMatchOf() ?: "auto" // fork: an imported mix shows Sync (mix-file.md rule 6)
            EditorRow("Beat match") {
                for ((id, name) in listOf("auto" to "Auto", "sync" to "Sync", "glide" to "Glide", "off" to "Off")) {
                    EditorChip(name, lit = bm == id, enabled = on && m.preset != "loop" && m.preset != "none") { edit(m, false) { beatMatch = id } }
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
            // B's late entry (mix-file.md rule 2): seconds into the mix before B starts at its mix-in; 0: with the mix
            val late = ov?.`in`?.delaySec ?: 0.0
            var lateText by remember(m.pairKey, late) { mutableStateOf(if (late > 0) late.toBigDecimal().stripTrailingZeros().toPlainString() else "0") }
            EditorRow("") {
                Text("B in after", color = EditorNote, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                NumberField(lateText) { lateText = it }
                Text("s", color = EditorNote, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                val lateSet = lateToSet(lateText, late)
                EditorChip("Set", false, enabled = m.windowEditable && lateSet != null) {
                    lateSet?.let { setLate(m, it) }
                }
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
                ForkEditorUi.stopDrawing() // the drawing is gone; its curve isn't left chosen for the next Custom
            }
            // a replaced drawing's Undo while the drawing's tools are hidden (editor.md §10 "Replacing a drawing")
            val toolsShown = on && drawnLanes(m).isNotEmpty() && !m.playsLoop && plan?.style != "loop"
            if (!toolsShown && ForkEditorUi.undo.any { it.pair == m.pairKey }) {
                EditorChip("Undo", false, enabled = on) { undoDraw(m) }
            }
        }
        ForkEditorUi.askReplace?.let { a ->
            AlertDialog(
                onDismissRequest = { ForkEditorUi.askReplace = null },
                title = { Text(a.question) },
                confirmButton = { TextButton(onClick = { ForkEditorUi.askReplace = null; a.yes() }) { Text("Yes") } },
                dismissButton = { TextButton(onClick = { ForkEditorUi.askReplace = null }) { Text("No") } },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(note, color = EditorNote, fontSize = 12.sp, maxLines = 3)
        // ready-made mixes in and out (spec/automix/mix-file.md, ForkMixFiles)
        if (ForkMixFiles.available) {
            val saved by ForkV2OverrideStore.overrides.collectAsState()
            Spacer(Modifier.height(8.dp))
            EditorRow("Mix files") {
                EditorChip("Import…", false, enabled = true) { ForkMixFiles.importMixes() }
                EditorChip("Export this pair", false, enabled = f != null && ov != null) { f?.let { ForkMixFiles.exportMixes(it.fromId, it.toId) } }
                EditorChip("Export all", false, enabled = saved.isNotEmpty()) { ForkMixFiles.exportMixes() }
                EditorChip("Play these mixes", false, enabled = ForkMixFiles.canPlay) { ForkMixFiles.playImported() }
            }
            ForkMixFiles.status?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = EditorNote, fontSize = 12.sp, maxLines = 2)
            }
        }
        ForkMixImportConfirm()
    }
    }
}

// -------------------------------------------------------------------------------------------------
// the progress row (editor.md §11)

/**
 * One row for the analysis (editor.md §11, spec fef76a9): "Analysing this song / the next song:
 * <stage> · NN%" over a bar filled to NN% (this song before the next, never both), a failure with its
 * reason or another outcome in its own words in its place; when those have nothing to say, the
 * playing song's addon copy (rule 6: asked for, fetched, lined up with YouTube's, switched to; never
 * the next song's), then "Looking for a loop mix…", both with a moving bar
 * ([ForkAnalysisProgress.rowFor]).
 *
 * [hidden] during a live mix (the popup shows the mix itself then). Not drawn until it first has
 * something to say; after that it keeps its room when it has nothing (invisible, not gone), so the
 * controls below don't jump while the popup is open. Look as the phone's overview: 12 sp text at 90%
 * white over a 6 dp bar in the frame's blue on a 20% white track.
 */
/**
 * Whether the row's bar sweeps: only while the row is seen ([visible]) and has no percentage (NaN).
 * A held "Looking for a loop mix…" row kept its sweep running at alpha 0, a frame every refresh for
 * as long as the popup stayed open.
 */
internal fun progressSweeps(visible: Boolean, fill: Float?): Boolean = visible && fill != null && fill.isNaN()

@Composable
private fun ForkProgressRow(hidden: Boolean, modifier: Modifier = Modifier) {
    ForkAnalysisProgress.changes.collectAsStateWithLifecycle().value
    val loopState by ForkMixPicks.loop.collectAsStateWithLifecycle()
    val pair = ForkAnalysisProgress.pair()
    val loopSearching = ForkMixPicks.active && pair?.second != null && loopState?.let { l ->
        l.pair == ForkMixPicks.pair(pair.first, pair.second!!) && l.loopStartSec == null && l.why?.startsWith("waiting") == true
    } == true
    val row = if (hidden) null else ForkAnalysisProgress.rowFor(pair?.first, pair?.second, loopSearching)
    // The last row said, kept for its room once there is nothing to say. A plain holder written
    // after the commit: it was two states written in the composition itself, which invalidated it.
    val held = remember { arrayOfNulls<ForkAnalysisProgress.Row>(1) }
    SideEffect { if (row != null) held[0] = row }
    val r = row ?: held[0] ?: return
    Column(modifier.fillMaxWidth().alpha(if (row == null) 0f else 1f)) {
        Text(r.text, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        val fill = r.fill
        Box(Modifier.fillMaxWidth().height(6.dp).alpha(if (fill == null) 0f else 1f).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) {
            if (fill != null && !fill.isNaN()) {
                Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(6.dp).background(EditorBlue))
            } else if (progressSweeps(visible = row != null, fill = fill)) {
                // indeterminate: a third of the track sweeping across
                val sweep by androidx.compose.animation.core.rememberInfiniteTransition(label = "forkProgress").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200)),
                    label = "forkProgressSweep",
                )
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val w = size.width / 3
                    drawRect(EditorBlue, topLeft = Offset(-w + sweep * (size.width + w), 0f), size = Size(w, size.height))
                }
            }
        }
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
                        if (o != value || !lit || o == ForkV2MixOverride.DRAW) onPick(o)
                    },
                )
            }
        }
    }
}

/** A small number box (Repeat's start and length). */
@Composable
private fun NumberField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .width(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(EditorChipOff)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
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
