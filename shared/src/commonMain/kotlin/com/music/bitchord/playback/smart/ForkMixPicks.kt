package com.music.bitchord.playback.smart

import com.music.bitchord.playback.smart.automixv2.ForkV2MixOverride
import com.music.bitchord.playback.smart.automixv2.ForkV2OverrideStore
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
 * Only Automix v2 reads them ([AutomixStyle.V2]): it plans in fork code
 * ([com.music.bitchord.playback.smart.automixv2.ForkV2Automix]), so upstream's planner stays
 * untouched. Under BitChord's own style the overview shows the picks greyed out.
 *
 * How a pick is used: it is handed to the ported planner as the extension saved picks before its
 * editor, {style, bars}, and migrated as it is read
 * ([com.music.bitchord.playback.smart.automixv2.ForkV2MixOverride.fromJson], editor.md §1):
 * - Fade: beat match off, the two songs overlapped at their own speeds. Filter: the planner's filter
 *   mix (at most [FILTER_MAX_BARS]). Blend: the planner's blend (its own fallbacks still apply).
 * - Blend, Filter, Fade, or a length with no style: no loop mix for the pair ([loopRefusal]).
 * - Loop: the loop mix whenever one is found (the extension's loopPicked), which keeps its own
 *   length; else the planner's mix.
 * - A picked length is the override's pinned bars: it beats the key-clash short blend, and still
 *   gives way to a weak grid's cap.
 *
 * Pairs are "outgoing id->incoming id", the loop mix's own pair key on both apps.
 *
 * Since the transition editor's storage (2026-11-19) the picks are no longer stored here: they are
 * the pair's override in [ForkV2OverrideStore] (keyed "a>b", editor.md §1), and this object is a
 * thin adapter that shows an override as a {style, bars} pick ([pickOf]) and saves a pick as the
 * override editor.md §1's migration table makes of it. The old saved lines are moved into the store
 * once by each app ([ForkV2OverrideStore.migrateLegacyPicks]).
 */
object ForkMixPicks {

    enum class Style(val key: String, val label: String) {
        LOOP("loop", "Loop"),
        // "Beat blend", not "Blend": the editor's Blend preset (editor.md §2) is another thing, and this
        // pick is the planner's own blend forced (override family "blend"). The key stays "blend".
        BLEND("blend", "Beat blend"),
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
         * The mix is armed: the planner holds this plan for the blend (ForkV2Automix's latch, from a few
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
    data class Loop(val pair: String, val loopStartSec: Double?, val why: String?, val header: String? = null) // header: the editor's §7 line for a planned loop mix

    val BARS = listOf(4, 8, 16, 32)

    /** Preview starts this long before a loop mix's first loop (the extension's 8 s). */
    const val PREVIEW_LOOP_LEAD_SEC = 8.0

    /** And this long before a blend, its tempo ramp included (the extension's hand lead + tail lead). */
    const val PREVIEW_BLEND_LEAD_SEC = 10.0

    /** Preview is offered only while the mix is further off than this. */
    const val PREVIEW_MIN_LEFT_SEC = 25.0

    const val PICKED_REASON = "picked in the overview"

    /** The longest filter mix (the ported planner's FILTER_MAX_BARS). */
    const val FILTER_MAX_BARS = 8

    /** The Automix style in force, mirrored by each app's setting (`ForkSettings`, `ForkDesktopAutomixStyle`). */
    @Volatile
    var style: AutomixStyle = AutomixStyle.BITCHORD

    /** Whether picks apply under [style]. */
    val active: Boolean get() = style != AutomixStyle.BITCHORD

    private val _picks = MutableStateFlow(picksFromStore())

    /** Every pair's override as a pick, keyed "a->b" ([pair]); follows [ForkV2OverrideStore]. */
    val picks: StateFlow<Map<String, Pick>> = _picks.asStateFlow()

    init {
        ForkV2OverrideStore.onChange = { _picks.value = picksFromStore() }
    }

    private val _planned = MutableStateFlow<Planned?>(null)
    val planned: StateFlow<Planned?> = _planned.asStateFlow()

    private val _loop = MutableStateFlow<Loop?>(null)
    val loop: StateFlow<Loop?> = _loop.asStateFlow()

    /** The loop mix's last verdict that wasn't a pick turning it away, for [set]. */
    private val lastDecided = MutableStateFlow<Loop?>(null)

    /** Moves the playing song to a position in ms (Preview); set by each app's player. */
    @Volatile
    var seek: ((Long) -> Unit)? = null

    fun pair(fromId: String, toId: String) = "$fromId->$toId"

    /** The pick for [fromId] -> [toId] under the style in force; null for none or under BitChord's style. */
    fun pickFor(fromId: String, toId: String): Pick? = if (active) pickOf(ForkV2OverrideStore.get(fromId, toId)) else null

    /**
     * Why the pair gets no loop mix because of its override, or null: anything the user set that is
     * not Loop (a preset, custom lanes, a family, a beat match, a length or a window) is a non-loop
     * mix picked. An override is never all Auto ([ForkV2MixOverride.normalize] drops those).
     */
    fun loopRefusal(fromId: String, toId: String): String? {
        if (!active) return null
        // a song at its playlist speed: no loop mix (spotify-mixes.md rule 10)
        com.music.bitchord.playback.smart.automixv2.ForkSongSpeed.loopRefusal(fromId, toId)?.let { return it }
        val ov = ForkV2OverrideStore.get(fromId, toId) ?: return null
        return if (ov.loop()) null else PICKED_REASON
    }

    /**
     * An override as the old chips can name it: Loop, the planner's blend or filter mix (a migrated
     * family), Fade (beat match off alone), a length alone, and for anything only the editor makes
     * (a preset, lanes, Sync/Glide, a window) a blend with its length.
     */
    internal fun pickOf(o: ForkV2MixOverride?): Pick? {
        if (o == null) return null
        val bars = o.bars.takeIf { it > 0 }
        val plain = o.preset == null && o.out == null && o.`in` == null
        return when {
            o.loop() -> Pick(Style.LOOP)
            o.family == "filter" -> Pick(Style.FILTER, bars)
            o.family == "blend" -> Pick(Style.BLEND, bars)
            plain && o.beatMatch == "off" -> Pick(Style.FADE)
            plain && o.beatMatch == null -> Pick(null, bars)
            else -> Pick(Style.BLEND, bars)
        }
    }

    private fun picksFromStore(): Map<String, Pick> {
        val out = LinkedHashMap<String, Pick>()
        for ((key, o) in ForkV2OverrideStore.overrides.value) {
            val (from, to) = ForkV2OverrideStore.idsOf(key) ?: continue
            pickOf(o)?.let { out[pair(from, to)] = it }
        }
        return out
    }

    private fun turnsLoopAway(pick: Pick) = pick.style == Style.BLEND || pick.style == Style.FILTER ||
        pick.style == Style.FADE || (pick.style == null && pick.bars != null)

    /**
     * Sets [pair]'s pick, the extension's `pick()`: a loop or a fade has no length of its own to pick,
     * a filter mix is at most [FILTER_MAX_BARS] bars, and an all-Auto pick is removed. Saved as the
     * pair's override (editor.md §1's table), keeping a lock the pair has; the store's cap applies.
     */
    fun set(pair: String, pick: Pick) {
        val p = normalised(pick)
        val (from, to) = ForkV2OverrideStore.idsOf(pair) ?: return
        val m = LinkedHashMap<String, Any?>()
        m["style"] = p.style?.key
        if (p.bars != null) m["bars"] = p.bars
        val ov = ForkV2MixOverride.fromJson(m)
        if (ov == null) {
            ForkV2OverrideStore.reset(from, to)
        } else {
            ov.lock = ForkV2OverrideStore.get(from, to)?.lock == true
            ForkV2OverrideStore.put(from, to, ov)
        }
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

    /** A loop or a fade has no length to pick, and a filter mix is at most [FILTER_MAX_BARS] bars. */
    private fun normalised(pick: Pick): Pick = when {
        pick.style == Style.LOOP || pick.style == Style.FADE -> pick.copy(bars = null)
        pick.style == Style.FILTER && (pick.bars ?: 0) > FILTER_MAX_BARS -> pick.copy(bars = FILTER_MAX_BARS)
        else -> pick
    }

    /**
     * From ForkV2Automix.planTransition, each time it plans a pair: what the mix is ([style], [bars],
     * the planner's [reason] when it is not a plain blend), and where Preview jumps to: just before
     * the outgoing ramp that starts [rampSongSec] before the mix-out [mixOut].
     */
    internal fun notePlanned(
        pair: String,
        style: Style,
        bars: Int,
        reason: String?,
        mixOut: Double,
        rampSongSec: Double,
        positionSec: Double,
        outTitle: String = "",
        inTitle: String = "",
        armed: Boolean = false,
    ) {
        val previewAt = mixOut - rampSongSec - PREVIEW_BLEND_LEAD_SEC
        // Whole seconds: this is called every tick, and the overview only needs the room left.
        val value = Planned(pair, style, bars, reason, previewAt, kotlin.math.floor(positionSec), outTitle, inTitle, armed)
        if (_planned.value != value) _planned.value = value
    }

    /** From ForkV2Automix.planTransition when it plans nothing (a short, spoken or live song): no stale pair. */
    internal fun notePlannedNothing() {
        _planned.value = null
    }

    /** Back to nothing picked, planned or noted (tests). */
    internal fun reset() {
        ForkV2OverrideStore.clear()
        _picks.value = emptyMap()
        _planned.value = null
        _loop.value = null
        lastDecided.value = null
    }

    /**
     * From each app's loop mix, whenever it decides a pair (the phone's `noteLoopVerdict`, the PC's
     * `ForkDesktopLoopMix.note`): planned with its first loop at [loopStartSec], or [why] not.
     */
    fun noteLoop(pair: String, loopStartSec: Double?, why: String?, plan: LoopMix.Plan? = null) {
        val value = Loop(pair, loopStartSec, why, plan?.let { com.music.bitchord.playback.smart.automixv2.ForkV2EditorGeometry.loopHeadline(it) })
        if (why != PICKED_REASON) lastDecided.value = value
        _loop.value = value
    }
}
