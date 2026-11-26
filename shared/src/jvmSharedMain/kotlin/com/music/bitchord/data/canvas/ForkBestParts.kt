package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.LyricLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Properties
import kotlin.math.abs

/**
 * Fork: a song's best part, Prism's `BestPart` / `BestParts` beside the "Most replayed" jump
 * ([ForkMostReplayed]) (audit 2026-11-20, part C 6 and 8):
 *
 *  - [chorusMs]: the first time the chorus comes round in time-synced lyrics, for a song with no
 *    "Most replayed" curve (the canvas's choice of part, canvas.md §7.6, and the best part here);
 *  - [snap]: a jump pulled back to where a lyric line begins, so it doesn't land mid-word;
 *  - [mine]: the listener's own best part for a song, which wins over the curve and the chorus
 *    wherever a best part is used (`fork-best-parts.properties`, beside the saved canvas lookups);
 *  - [current]: the playing song's, set by the player while it is open; v1.10's own Best Part
 *    pill shows it ([awaitStart], through `PlayerHost.bestPartMs`);
 *  - [lines]: the last songs' synced lyrics, as the player had them, for [snap] and [chorusMs] where
 *    the lyrics aren't to hand (the menu's jump, the canvas lookup). Nothing is fetched for them.
 */
object ForkBestParts {

    private const val TAG = "ForkBestParts"
    private const val FILE = "fork-best-parts.properties"

    /** Nothing in a song's opening seconds counts: everyone "replays" the start. */
    const val SKIP_HEAD_MS = 5_000L

    /** A best part needs some song left after it. */
    const val SKIP_TAIL_MS = 30_000L

    /** Shorter songs (interludes, skits) have no best part. */
    const val MIN_SONG_MS = 60_000L

    // ---- the chorus and the snap (pure) ------------------------------------------------------

    /**
     * Prism's `BestPart.chorusMs`: the earliest line of the most repeated lyric (and the lines of
     * that block before it, at most 4), from line-synced [lines]. Null when nothing repeats, with
     * fewer than 4 timed lines, or in a song under a minute.
     */
    fun chorusMs(lines: List<LyricLine>, durationMs: Long): Long? {
        if (durationMs in 1 until MIN_SONG_MS) return null
        val timed = lines.filter { it.timeMs >= 0 && !it.isGap && it.text.isNotBlank() }
        if (timed.size < 4) return null
        val keys = timed.map { normalize(it.text) }
        val counts = keys.filter { it.length >= 4 }.groupingBy { it }.eachCount()
        val most = counts.values.maxOrNull() ?: return null
        if (most < 2) return null
        val limit = if (durationMs > 0) durationMs - SKIP_TAIL_MS else Long.MAX_VALUE
        val first = keys.indices.firstOrNull { i -> counts[keys[i]] == most && timed[i].timeMs >= SKIP_HEAD_MS && timed[i].timeMs <= limit } ?: return null
        var start = first
        while (start - 1 >= 0 && (counts[keys[start - 1]] ?: 0) >= 2 && (counts[keys[start - 1]] ?: 0) >= most - 1 &&
            timed[start - 1].timeMs >= SKIP_HEAD_MS && first - start < 4
        ) start--
        return timed[start].timeMs
    }

    /** A song's beat grid, as Prism's `Tempo` (canvas.md §14 rule 2): its tempo, whether it holds, the first beat. */
    data class Tempo(val bpm: Double, val steady: Boolean, val firstBeatMs: Long) {
        val periodMs: Double get() = 60_000.0 / bpm
    }

    /**
     * Prism's `BestPart.snap` (canvas.md §14 rule 2): [targetMs] pulled to a lyric line beginning near
     * it (up to 4 s before or 1.5 s after, a line after counting 1.5 s farther), 250 ms ahead of the
     * line; with no line near, the bar line before it on a steady [tempo] grid; else a second before.
     * [lineStarts]: the song's synced line starts. The apps pass no grid: BitChord has none for the
     * playing song here (its Automix analysis is the mixer's), so the bar step is skipped (FORK.md).
     */
    fun snap(targetMs: Long, lineStarts: List<Long>, tempo: Tempo? = null): Long {
        lineStarts.filter { it in (targetMs - 4_000)..(targetMs + 1_500) }
            .minByOrNull { abs(it - targetMs) + if (it > targetMs) 1_500 else 0 }
            ?.let { return (it - 250).coerceAtLeast(0) }
        if (tempo != null && tempo.bpm > 0 && tempo.steady && tempo.firstBeatMs >= 0) {
            val bar = 4 * tempo.periodMs
            val bars = ((targetMs - tempo.firstBeatMs) / bar).toLong()
            if (bars >= 0) return (tempo.firstBeatMs + bars * bar).toLong()
        }
        return (targetMs - 1_000).coerceAtLeast(0)
    }

    /** §14 rule 4: where the seek bar's mark sits (0..1), only for a part between 1% and 99% of the song. */
    fun markAt(startMs: Long?, durationMs: Long): Float? {
        if (startMs == null || durationMs <= 0) return null
        val f = startMs.toFloat() / durationMs
        return f.takeIf { it in 0.01f..0.99f }
    }

    /** The line starts [snap] takes from [lines]: timed, not gaps. */
    fun starts(lines: List<LyricLine>): List<Long> = lines.filter { it.timeMs >= 0 && !it.isGap }.map { it.timeMs }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The best part from what is known: the listener's own ([own]) first, else the curve's peak
     * ([peakMs], -1 none), else the chorus in [lines]; a found one is snapped to a line. Null for none.
     */
    fun start(own: Long?, peakMs: Long, lines: List<LyricLine>, durationMs: Long): Long? {
        if (own != null) return own
        val raw = peakMs.takeIf { it >= 0 } ?: chorusMs(lines, durationMs) ?: return null
        val starts = starts(lines)
        return if (starts.isEmpty()) raw else snap(raw, starts)
    }

    // ---- the lyrics the player had -----------------------------------------------------------

    private val memo = object : LinkedHashMap<String, List<LyricLine>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>) = size > 8
    }

    /** Whether [lyrics] are timed, the ones [noteLyrics] keeps and the chorus is found in. */
    fun isSynced(lyrics: List<LyricLine>?): Boolean = lyrics != null && lyrics.any { it.timeMs > 0 }

    /** The player's synced [lyrics] for [songId] (unsynced ones are not kept). */
    fun noteLyrics(songId: String, lyrics: List<LyricLine>?) {
        if (songId.isBlank() || lyrics == null || !isSynced(lyrics)) return
        synchronized(memo) { memo[songId] = lyrics }
    }

    /** The synced lyrics the player last had for [songId], or none. */
    fun lines(songId: String): List<LyricLine> = synchronized(memo) { memo[songId] }.orEmpty()

    // ---- the listener's own ------------------------------------------------------------------

    @Volatile private var file: File? = null
    private var props: Properties? = null

    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, FILE)
        val p = Properties()
        if (f.exists()) runCatching { f.inputStream().use(p::load) }.onFailure { Log.w(TAG, "read failed: ${it.message}") }
        props = p
        file = f
    }

    /** The listener's own best part for [songId] (ms), if they set one. */
    @Synchronized
    fun mine(songId: String): Long? = props?.getProperty(songId)?.toLongOrNull()?.takeIf { it >= 0 }

    /** Sets (or with null clears) the listener's own best part for [songId]; the button follows at once. */
    fun setMine(songId: String, ms: Long?) {
        synchronized(this) {
            val p = props ?: return
            if (ms == null) p.remove(songId) else p.setProperty(songId, ms.coerceAtLeast(0).toString())
            while (p.size > 2000) p.remove(p.keys.first())
            save()
        }
        val now = _current.value?.takeIf { it.songId == songId } ?: return
        _current.value = now.copy(startMs = ms ?: now.autoMs, mine = ms != null)
    }

    private fun save() {
        val f = file ?: return
        val p = props ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { p.store(it, null) }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { Log.w(TAG, "write failed: ${it.message}") }
    }

    @Synchronized
    internal fun resetForTest(dir: File?) {
        file = null
        props = null
        _current.value = null
        synchronized(memo) { memo.clear() }
        if (dir != null) init(dir)
    }

    // ---- the playing song's ------------------------------------------------------------------

    /**
     * The playing song's best part: where it starts ([startMs], null: none known), what it would be
     * without the listener's own ([autoMs]), and how far into the song it became known ([knownAtMs]).
     */
    data class Current(val songId: String, val startMs: Long?, val autoMs: Long?, val knownAtMs: Long, val mine: Boolean)

    /**
     * [markAt] for [current] only when it is [songId]'s: on a song change the scrubber recomposes with
     * the new song's length a frame before `ForkBestPartFollow` clears [current], and the old song's
     * mark showed at a wrong place on the new song's bar (audit 2026-11-24 2a #1 part 3, 2026-11-26
     * 2a #4). A null [songId] (a caller that names none) keeps the old reading.
     */
    fun markFor(current: Current?, songId: String?, durationMs: Long): Float? =
        markAt(current?.takeIf { songId == null || it.songId == songId }?.startMs, durationMs)

    private val _current = MutableStateFlow<Current?>(null)
    val current: StateFlow<Current?> = _current.asStateFlow()

    fun setCurrent(value: Current?) {
        _current.value = value
    }

    /**
     * [songId]'s best part once the player has worked it out ([current]), for v1.10's Best Part pill
     * (`PlayerHost.bestPartMs` on both apps): the fork's pick (your own, else the most replayed part,
     * else the chorus) instead of upstream's `BestParts.startFor`, which knows no "Your best part" and
     * can't run on the PC. Null for none, or when nothing comes within [timeoutMs].
     */
    suspend fun awaitStart(songId: String, timeoutMs: Long = AWAIT_MS): Long? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { current.first { it?.songId == songId } }?.startMs

    private const val AWAIT_MS = 20_000L

    /**
     * Every later answer for [songId], for v1.10's pill after its one [awaitStart] (`PlayerControls`
     * hook). [awaitStart] settles on the first [current] for the song, which `ForkBestPartFollow` sets
     * before the synced lyrics are in (a song with no replay curve has no part yet), and v1.10's
     * `produceState` asks only once per song: without this the chorus found a moment later, or a
     * "Your best part" saved while the song plays ([setMine]), never reached the pill. Runs until
     * the caller's scope ends.
     */
    suspend fun follow(songId: String, set: (Long?) -> Unit) {
        current.collect { if (it?.songId == songId) set(it.startMs) }
    }

    // ---- Your best part (§14 rule 5) ---------------------------------------------------------

    /** The song whose "Your best part" editor is open (the player draws it), or null. */
    private val _editing = MutableStateFlow<String?>(null)
    val editing: StateFlow<String?> = _editing.asStateFlow()

    /** Opens "Your best part" for [songId] (the song menu's row, a long press on the button). */
    fun openEditor(songId: String) {
        _editing.value = songId
    }

    fun closeEditor() {
        _editing.value = null
    }

    /** What the editor needs of the app's player: where a song is while it plays, and a seek in it. */
    interface Player {
        /** Where [songId] is now (ms) while it plays; null otherwise. */
        fun positionOf(songId: String): Long?

        /** Seeks the playing [songId] to [ms] (nothing when another song plays or a party locks the controls). */
        fun seek(songId: String, ms: Long)
    }

    /** Set by each app (the phone's MediaController, the PC's window). */
    @Volatile var player: Player? = null

    /**
     * Where the editor opens (rule 5): the listener's own, else the automatic best part ([autoMs]),
     * else where the song is now ([nowMs]), else 0.
     */
    fun editorStart(mineMs: Long?, autoMs: Long?, nowMs: Long?): Long = mineMs ?: autoMs ?: nowMs ?: 0L

    // ---- the old button's setting ------------------------------------------------------------

    /**
     * The fork's own "Best part button" (by the scrubber) gave way to v1.10's Best Part pill; its
     * switch, kept under this key, now lives in v1.10's `show_best_part` (the PC: its own store).
     * Still read from a backup, so an old one's "off" carries over.
     */
    const val KEY_BUTTON = "best_part_button"

    /** True once when [from] had the old button switched off; the key is then cleared. */
    fun takeOldButtonOff(from: ForkCanvasSettings.Store): Boolean {
        val old = runCatching { from.get(KEY_BUTTON) }.getOrNull() ?: return false
        runCatching { from.put(KEY_BUTTON, null) }
        return old == "off"
    }

    // ---- display text, the same on both apps -------------------------------------------------

    const val PLAY_FROM = "Play from the best part"
    const val NONE_KNOWN = "No best part known for this song"

    /** "Play from the best part" (§14 rule 6): how long the part is looked for, and what is said without one. */
    const val FIND_MS = 5_000L
    const val FROM_START = "No best part found; playing from the start"

    /** "Your best part" (§14 rule 5): the menu row, the editor's title and its buttons. */
    const val EDITOR_TITLE = "Your best part"
    const val BACK_5 = "−5 s"
    const val FORWARD_5 = "+5 s"
    const val NOW = "Now"
    const val LISTEN = "Listen from here"
    const val SAVE = "Save"
    const val FORGET = "Forget"
    const val FORGET_TO_AUTO = "Back to the part people replay"
    const val SAVED = "Your best part is saved"

    /** "1:23". */
    fun clock(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
