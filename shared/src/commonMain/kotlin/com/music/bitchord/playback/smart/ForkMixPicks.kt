package com.music.bitchord.playback.smart

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fork: the Automix overview's picks per pair of songs, as in the YouTube Music extension
 * (`crossfade.js` `pairPicks`, `automix-overview.js`): a style (Auto, Loop, Blend, Filter, Fade) and a
 * length (Auto, 4 to 32 bars), kept for the next time the same two songs meet, for the last
 * [MAX_PICKS] pairs.
 *
 * Only the Web and Advanced AutoDJ styles read them ([AutomixStyle.ORIGINAL], [AutomixStyle.ADVANCED]):
 * those plan in [ForkAutoDj], fork code, so upstream's planner stays untouched. Under BitChord's own
 * style the overview shows the picks greyed out.
 *
 * How a pick is used (the extension's rules):
 * - Fade: the 12 s equal-power crossfade. Filter: the filter mix (at most [ForkAutoDj.FILTER_MAX_BARS]).
 *   Blend: the planner's blend (still a filter mix when the tempos are too far apart).
 * - Blend, Filter, Fade, or a length with no style: no loop mix for the pair ([loopRefusal]).
 * - Loop: the loop mix when it can be done, which keeps its own length; else the planner's mix.
 * - A picked length beats the key-clash short blend, and still gives way to a weak grid's cap.
 *
 * Pairs are "outgoing id->incoming id", the loop mix's own pair key on both apps.
 */
object ForkMixPicks {

    enum class Style(val key: String, val label: String) {
        LOOP("loop", "Loop"),
        BLEND("blend", "Blend"),
        FILTER("filter", "Filter"),
        FADE("crossfade", "Fade"),
        ;

        companion object {
            fun fromKey(key: String?): Style? = entries.firstOrNull { it.key == key }
        }
    }

    /** One pair's pick; null fields are Auto. */
    data class Pick(val style: Style? = null, val bars: Int? = null) {
        val isEmpty: Boolean get() = style == null && bars == null
    }

    /** What the mix being planned is, for the overview's picks and note. */
    data class Planned(
        val pair: String,
        /** What it will be: [Style.LOOP], or the planner's blend, filter mix or fade. */
        val style: Style,
        val bars: Int,
        /** The planner's reason for anything but a plain blend ("tempos 30% apart", "picked in the overview"). */
        val reason: String?,
        /** Outgoing song second Preview jumps to: just before the mix. */
        val previewAtSec: Double,
        /** The outgoing song's playhead when this was planned, for "is there room for a preview". */
        val positionSec: Double,
        /** The two songs' titles, to tell whether the overview is drawing this pair (its decks carry no ids). */
        val outTitle: String = "",
        val inTitle: String = "",
        /**
         * The mix is armed: the planner holds this plan for the blend ([ForkAutoDj]'s latch, from a few
         * seconds before the tempo ramp), so a pick now is for the next time the pair plays.
         */
        val armed: Boolean = false,
    ) {
        /**
         * Whether the overview drawing [shownOut] -> [shownIn] is drawing this pair. It keeps drawing a
         * finished mix until the next pair can be drawn while the planner has moved on, and a pick then
         * would land on songs not on screen. Untitled plans (a planner given no titles) always match.
         */
        fun isShownAs(shownOut: String, shownIn: String): Boolean =
            outTitle.isEmpty() || (shownOut == outTitle && shownIn == inTitle)
    }

    /** Where the loop mix stands for a pair: planned at [loopStartSec], or [why] not. */
    data class Loop(val pair: String, val loopStartSec: Double?, val why: String?)

    const val MAX_PICKS = 300
    val BARS = listOf(4, 8, 16, 32)

    /** Preview starts this long before a loop mix's first loop (the extension's 8 s). */
    const val PREVIEW_LOOP_LEAD_SEC = 8.0

    /** And this long before a blend, its tempo ramp included (the extension's hand lead + tail lead). */
    const val PREVIEW_BLEND_LEAD_SEC = 10.0

    /** Preview is offered only while the mix is further off than this. */
    const val PREVIEW_MIN_LEFT_SEC = 25.0

    const val PICKED_REASON = "picked in the overview"

    /** The Automix style in force, mirrored by each app's setting (`ForkSettings`, `ForkDesktopAutomixStyle`). */
    @Volatile
    var style: AutomixStyle = AutomixStyle.BITCHORD

    /** Whether picks apply under [style]. */
    val active: Boolean get() = style != AutomixStyle.BITCHORD

    private val _picks = MutableStateFlow<Map<String, Pick>>(emptyMap())
    val picks: StateFlow<Map<String, Pick>> = _picks.asStateFlow()

    private val _planned = MutableStateFlow<Planned?>(null)
    val planned: StateFlow<Planned?> = _planned.asStateFlow()

    private val _loop = MutableStateFlow<Loop?>(null)
    val loop: StateFlow<Loop?> = _loop.asStateFlow()

    /** The loop mix's last verdict that wasn't a pick turning it away, for [set]. */
    private val lastDecided = MutableStateFlow<Loop?>(null)

    /** Writes the picks out ([encode]); set by each app. */
    @Volatile
    var save: ((String) -> Unit)? = null

    /** Moves the playing song to a position in ms (Preview); set by each app's player. */
    @Volatile
    var seek: ((Long) -> Unit)? = null

    fun pair(fromId: String, toId: String) = "$fromId->$toId"

    /** The pick for [fromId] -> [toId] under the style in force; null for none or under BitChord's style. */
    fun pickFor(fromId: String, toId: String): Pick? = if (active) _picks.value[pair(fromId, toId)] else null

    /** Why the pair gets no loop mix because of its pick, or null. */
    fun loopRefusal(fromId: String, toId: String): String? {
        val pick = pickFor(fromId, toId) ?: return null
        return if (turnsLoopAway(pick)) PICKED_REASON else null
    }

    private fun turnsLoopAway(pick: Pick) = pick.style == Style.BLEND || pick.style == Style.FILTER ||
        pick.style == Style.FADE || (pick.style == null && pick.bars != null)

    /**
     * Sets [pair]'s pick, the extension's `pick()`: a loop or a fade has no length of its own to pick,
     * a filter mix is at most [ForkAutoDj.FILTER_MAX_BARS] bars, and an all-Auto pick is removed.
     * The newest pick goes last, and the oldest beyond [MAX_PICKS] are dropped.
     */
    fun set(pair: String, pick: Pick) {
        val p = normalised(pick)
        var next: Map<String, Pick> = emptyMap()
        _picks.update { old ->
            LinkedHashMap(old).apply {
                remove(pair)
                if (!p.isEmpty) put(pair, p)
                while (size > MAX_PICKS) remove(keys.first())
            }.also { next = it }
        }
        save?.invoke(encode(next))
        // The phone keeps its loop plan per pair and, when nothing about the pair changed, hands it back
        // without deciding again, so a pick that lets the loop back in would leave the overview on "picked
        // in the overview" for good: put back what the loop mix last decided for this pair.
        // A compare-and-set, so a verdict the PC's audio thread notes meanwhile is never overwritten.
        if (turnsLoopAway(p)) return
        _loop.update { shown ->
            if (shown != null && shown.pair == pair && shown.why == PICKED_REASON) {
                lastDecided.value?.takeIf { it.pair == pair } ?: Loop(pair, null, "waiting on the loop mix")
            } else {
                shown
            }
        }
    }

    /** A loop or a fade has no length to pick, and a filter mix is at most [ForkAutoDj.FILTER_MAX_BARS] bars. */
    private fun normalised(pick: Pick): Pick = when {
        pick.style == Style.LOOP || pick.style == Style.FADE -> pick.copy(bars = null)
        pick.style == Style.FILTER && (pick.bars ?: 0) > ForkAutoDj.FILTER_MAX_BARS -> pick.copy(bars = ForkAutoDj.FILTER_MAX_BARS)
        else -> pick
    }

    /** Reads saved picks back ([encode]'s lines); a bad line is skipped. */
    fun load(saved: String?) {
        val map = LinkedHashMap<String, Pick>()
        saved?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0].isBlank()) return@forEach
            val pick = normalised(Pick(Style.fromKey(parts[1]), parts[2].toIntOrNull()?.takeIf { it in BARS }))
            if (!pick.isEmpty) map[parts[0]] = pick
        }
        while (map.size > MAX_PICKS) map.remove(map.keys.first())
        _picks.value = map
    }

    /** One pair a line: "a->b", style key or empty, bars or empty, tab-separated. */
    fun encode(picks: Map<String, Pick>): String =
        picks.entries.joinToString("\n") { (pair, p) -> "$pair\t${p.style?.key.orEmpty()}\t${p.bars?.toString().orEmpty()}" }

    /** From [ForkAutoDj.planTransition], each time it plans a pair. */
    internal fun notePlanned(
        pair: String,
        mix: ForkAutoDj.Mix,
        positionSec: Double,
        outTitle: String = "",
        inTitle: String = "",
        armed: Boolean = false,
    ) {
        val style = when (mix.style) {
            ForkAutoDj.Style.BLEND -> Style.BLEND
            ForkAutoDj.Style.FILTER -> Style.FILTER
            ForkAutoDj.Style.CROSSFADE -> Style.FADE
        }
        val reason = mix.reason.takeIf { it != "blend" }
        val previewAt = mix.mixOut - mix.rampSongSec - PREVIEW_BLEND_LEAD_SEC
        // Whole seconds: this is called every tick, and the overview only needs the room left.
        val value = Planned(pair, style, mix.bars, reason, previewAt, kotlin.math.floor(positionSec), outTitle, inTitle, armed)
        if (_planned.value != value) _planned.value = value
    }

    /** From [ForkAutoDj.planTransition] when it plans nothing (a short, spoken or live song): no stale pair. */
    internal fun notePlannedNothing() {
        _planned.value = null
    }

    /** Back to nothing picked, planned or noted (tests). */
    internal fun reset() {
        _picks.value = emptyMap()
        _planned.value = null
        _loop.value = null
        lastDecided.value = null
    }

    /**
     * From each app's loop mix, whenever it decides a pair (the phone's `noteLoopVerdict`, the PC's
     * `ForkDesktopLoopMix.note`): planned with its first loop at [loopStartSec], or [why] not.
     */
    fun noteLoop(pair: String, loopStartSec: Double?, why: String?) {
        val value = Loop(pair, loopStartSec, why)
        if (why != PICKED_REASON) lastDecided.value = value
        _loop.value = value
    }
}
