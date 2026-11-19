package com.music.bitchord.playback.smart.automixv2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The transition editor's overrides, one per pair of songs (spec/automix/editor.md §1,
 * override.schema.json), shared by the phone and the PC: the planner reads them
 * ([ForkV2Automix.overrideFor]) and the editor writes them.
 *
 * - **Key:** "fromId>toId" ([pairKey]), the extension's and the phone patches' key. The fork's
 *   older picks were keyed "fromId->toId"; they are moved here once ([migrateLegacyPicks]).
 * - **Stored as** one JSON object for the whole map, `{"a>b": override, …}`, each value as
 *   [ForkV2MixOverride.toJson] writes it. Each app hands in where that string lives ([install]):
 *   the phone's `ForkSettings` (a backed-up pref), the PC's `ForkDesktopAutomixStyle`
 *   (DesktopPersistence).
 * - **The cap** (editor.md §1): at most [ForkV2MixOverride.MAX_UNLOCKED] unlocked overrides, the
 *   oldest `savedAt` going first. Locked ones don't count and are never evicted; they have a cap of
 *   their own, [ForkV2MixOverride.MAX_LOCKED], and a lock past it is refused with [LOCK_CAP_REFUSAL]
 *   (crossfade.js setPairPick's wording, the master; the phone patches word it differently).
 * - **Auto is the reset:** an override that normalizes to nothing but Auto is deleted, so a lock
 *   alone keeps nothing.
 *
 * The map is held in a [MutableStateFlow] and changed with compare-and-set ([update]), because this
 * lives in commonMain with no lock primitive: the planner reads it on the playback thread each tick
 * while the editor writes from the UI thread.
 */
object ForkV2OverrideStore {

    /** editor.md pair key */
    fun pairKey(fromId: String, toId: String): String = "$fromId>$toId"

    /**
     * The two ids of a pair key, either this store's "a>b" or the older picks' "a->b" (the loop mix's
     * and [com.music.bitchord.playback.smart.ForkMixPicks.Planned.pair]'s key, still in use there).
     * Split on the first separator: YouTube ids carry '-' but never '>'.
     */
    fun idsOf(key: String): Pair<String, String>? {
        val legacy = key.indexOf("->")
        if (legacy > 0) return key.substring(0, legacy) to key.substring(legacy + 2)
        val i = key.indexOf('>')
        return if (i > 0) key.substring(0, i) to key.substring(i + 1) else null
    }

    /** The refusal for a lock past the locked cap (crossfade.js setPairPick). */
    val LOCK_CAP_REFUSAL = "${ForkV2MixOverride.MAX_LOCKED} pairs are locked already: unlock one first"

    /**
     * The refusal for a lock on a pair with no edit: a lock alone normalizes to Auto and would be
     * dropped (editor.md §1), so it is said rather than lost; the extension disables its Lock button
     * there instead.
     */
    const val NOTHING_TO_LOCK = "Nothing to lock: these two songs mix on Auto"

    /** The Automix version an override is saved under: the fork has only Automix v2 (the extension's 3). */
    const val ENGINE = 3

    private val map = MutableStateFlow<Map<String, ForkV2MixOverride>>(emptyMap())

    /** Every override by pair key, oldest saved first (read-only copies are made by [get]). */
    val overrides: StateFlow<Map<String, ForkV2MixOverride>> = map.asStateFlow()

    private val _changes = MutableStateFlow(0)

    /** bumps on every write, for UIs and the planner memo */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private var load: (() -> String?)? = null
    private var save: ((String) -> Unit)? = null

    /** ms since the epoch for `savedAt`; tests set it. */
    var clock: () -> Long = { nowMs() }

    fun get(fromId: String, toId: String): ForkV2MixOverride? = map.value[pairKey(fromId, toId)]?.copy()

    /**
     * saves (normalized, savedAt = now, v, engine); returns null when saved, else the refusal sentence
     * shown to the user (e.g. the locked cap)
     *
     * [override] is the pair's whole edit, lock included: an editor changing one field starts from
     * [get]'s copy. An override that normalizes to Auto removes the pair ([reset]).
     */
    fun put(fromId: String, toId: String, override: ForkV2MixOverride): String? =
        write(pairKey(fromId, toId), ForkV2MixOverride.normalize(override))

    /** drops the pair's override (Reset to Auto) */
    fun reset(fromId: String, toId: String) {
        write(pairKey(fromId, toId), null)
    }

    /** returns null when done, else the refusal sentence */
    fun setLocked(fromId: String, toId: String, locked: Boolean): String? {
        val o = get(fromId, toId) ?: return if (locked) NOTHING_TO_LOCK else null
        if (o.lock == locked) return null
        o.lock = locked
        return put(fromId, toId, o)
    }

    /**
     * platform persistence: one JSON string for the whole map; installed by each app at start
     *
     * Reads the map at once (a value it can't read is skipped, the rest kept) and saves on every
     * write after. Calling it again re-reads, which is what a restored backup needs.
     */
    fun install(load: () -> String?, save: (String) -> Unit) {
        this.load = load
        this.save = save
        map.value = decode(runCatching { load() }.getOrNull())
        bump()
    }

    /**
     * Moves the fork's older overview picks into the store, once: [saved] is the old pref's text
     * (`ForkMixPicks`' lines, "a->b\tstyle\tbars"), each line becoming the override editor.md §1's
     * table makes of {style, bars} ([ForkV2MixOverride.fromJson]). A pair the store already has keeps
     * its override. The caller clears the old pref afterwards; returns how many pairs moved.
     *
     * The old picks carried no time, so they get `savedAt`s a millisecond apart, just before the
     * oldest edit already saved (or now), in their saved order: the old list was oldest first, and
     * so the cap evicts them in that order and before any edit made in the editor.
     */
    fun migrateLegacyPicks(saved: String?): Int {
        val picks = legacyPicks(saved)
        if (picks.isEmpty()) return 0
        var moved = 0
        val now = clock()
        map.update { old ->
            moved = 0
            // before every edit already saved (crossfade.js: picks from before the editor go first)
            val base = minOf(now, old.values.minOfOrNull { it.savedAt } ?: now) - picks.size
            val next = LinkedHashMap<String, ForkV2MixOverride>()
            picks.forEachIndexed { i, (key, ov) ->
                if (key in old) return@forEachIndexed
                ov.engine = ENGINE
                ov.savedAt = base + i
                next[key] = ov
                moved++
            }
            // older than anything saved in the editor: first in the map, as they are oldest
            next.putAll(old)
            capped(next)
        }
        if (moved > 0) persist()
        bump()
        return moved
    }

    /** Back to an empty, uninstalled store (tests). */
    internal fun clear() {
        load = null
        save = null
        map.value = emptyMap()
        clock = { nowMs() }
        bump()
    }

    // ---------------------------------------------------------------------------------------------

    private fun write(key: String, ov: ForkV2MixOverride?): String? {
        var refusal: String? = null
        val now = clock()
        map.update { old ->
            refusal = null
            val was = old[key]
            if (ov != null && ov.lock && was?.lock != true &&
                old.values.count { it.lock } >= ForkV2MixOverride.MAX_LOCKED
            ) {
                refusal = LOCK_CAP_REFUSAL
                return@update old
            }
            val next = LinkedHashMap(old)
            next.remove(key)
            if (ov != null) {
                ov.engine = ENGINE
                ov.savedAt = now
                next[key] = ov
            }
            capped(next)
        }
        if (refusal != null) return refusal
        persist()
        bump()
        return null
    }

    /** [m] with the oldest-saved unlocked overrides past the cap dropped (insertion order breaks ties). */
    private fun capped(m: LinkedHashMap<String, ForkV2MixOverride>): Map<String, ForkV2MixOverride> {
        val loose = m.entries.filter { !it.value.lock }
        val over = loose.size - ForkV2MixOverride.MAX_UNLOCKED
        if (over <= 0) return m
        loose.withIndex().sortedWith(compareBy({ it.value.value.savedAt }, { it.index }))
            .take(over).forEach { m.remove(it.value.key) }
        return m
    }

    /**
     * Writes the map out. Without a lock two writers can save out of order, so a save is repeated
     * until the map it wrote is still the current one: the last save always holds the newest map.
     */
    private fun persist() {
        val s = save ?: return
        var snap: Map<String, ForkV2MixOverride>
        do {
            snap = map.value
            runCatching { s(encode(snap)) }
        } while (map.value !== snap)
    }

    /** Told after every change: `ForkMixPicks` keeps its {style, bars} view of the store with it. */
    internal var onChange: (() -> Unit)? = null

    private fun bump() {
        _changes.update { it + 1 }
        onChange?.invoke()
    }

    internal fun encode(m: Map<String, ForkV2MixOverride>): String =
        ForkV2Json.write(LinkedHashMap<String, Any?>().apply { for ((k, v) in m) put(k, v.toJson()) })

    @Suppress("UNCHECKED_CAST")
    internal fun decode(text: String?): Map<String, ForkV2MixOverride> {
        val out = LinkedHashMap<String, ForkV2MixOverride>()
        if (text.isNullOrBlank()) return out
        val json = runCatching { ForkV2Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return out
        for ((k, v) in json) {
            if (idsOf(k) == null) continue
            val ov = runCatching { ForkV2MixOverride.fromJson(v) }.getOrNull() ?: continue
            out[k] = ov
        }
        return out
    }

    /**
     * The old lines as overrides, keyed "a>b", read as ForkMixPicks.load read them: a bad line is
     * skipped, a loop or fade pick had no length, a filter mix at most 8 bars, lengths 4 to 32.
     */
    internal fun legacyPicks(saved: String?): List<Pair<String, ForkV2MixOverride>> {
        val out = LinkedHashMap<String, ForkV2MixOverride>()
        saved?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0].isBlank()) return@forEach
            val (from, to) = idsOf(parts[0]) ?: return@forEach
            val style = parts[1].takeIf { it in LEGACY_STYLES }
            var bars = parts[2].toIntOrNull()?.takeIf { it in LEGACY_BARS }
            if (style == "loop" || style == "crossfade") bars = null
            if (style == "filter" && bars != null && bars > LEGACY_FILTER_MAX_BARS) bars = LEGACY_FILTER_MAX_BARS
            val pick = LinkedHashMap<String, Any?>()
            pick["style"] = style
            if (bars != null) pick["bars"] = bars
            val ov = ForkV2MixOverride.fromJson(pick) ?: return@forEach
            val key = pairKey(from, to)
            out.remove(key) // a pair saved twice: the later line is the newer
            out[key] = ov
        }
        return out.entries.map { it.key to it.value }
    }

    private val LEGACY_STYLES = setOf("loop", "blend", "filter", "crossfade")
    private val LEGACY_BARS = listOf(4, 8, 16, 32)
    private const val LEGACY_FILTER_MAX_BARS = 8

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
