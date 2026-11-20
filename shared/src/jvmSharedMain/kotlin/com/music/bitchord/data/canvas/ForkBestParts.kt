package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.LyricLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *  - [current]: the playing song's, for the button by the scrubber ([shows]: Prism's
 *    `BestPartButton`), set by the player while it is open;
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

    /**
     * Prism's `BestPart.snap`, without its beat grid: [targetMs] pulled to a lyric line beginning
     * near it (up to 4 s before or 1.5 s after, one before preferred), 250 ms ahead of the line; with
     * no line near, a second before. [lineStarts]: the song's synced line starts.
     */
    fun snap(targetMs: Long, lineStarts: List<Long>): Long {
        lineStarts.filter { it in (targetMs - 4_000)..(targetMs + 1_500) }
            .minByOrNull { abs(it - targetMs) + if (it > targetMs) 1_500 else 0 }
            ?.let { return (it - 250).coerceAtLeast(0) }
        return (targetMs - 1_000).coerceAtLeast(0)
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

    /** Prism's `BestPartButton.shows`: in the first 30 s, or 15 s from when it became known (up to 45 s in), while 3 s ahead. */
    fun shows(atMs: Long, startMs: Long, knownAtMs: Long): Boolean {
        val until = maxOf(BUTTON_FIRST_MS, knownAtMs + BUTTON_AFTER_KNOWN_MS).coerceAtMost(BUTTON_LATEST_MS)
        return atMs < until && atMs < startMs - BUTTON_AHEAD_MS
    }

    private const val BUTTON_FIRST_MS = 30_000L
    private const val BUTTON_AFTER_KNOWN_MS = 15_000L
    const val BUTTON_LATEST_MS = 45_000L
    private const val BUTTON_AHEAD_MS = 3_000L

    // ---- the lyrics the player had -----------------------------------------------------------

    private val memo = object : LinkedHashMap<String, List<LyricLine>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>) = size > 8
    }

    /** The player's synced [lyrics] for [songId] (unsynced ones are not kept). */
    fun noteLyrics(songId: String, lyrics: List<LyricLine>?) {
        if (songId.isBlank() || lyrics == null || lyrics.none { it.timeMs > 0 }) return
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

    private val _current = MutableStateFlow<Current?>(null)
    val current: StateFlow<Current?> = _current.asStateFlow()

    fun setCurrent(value: Current?) {
        _current.value = value
    }

    // ---- the button's setting ----------------------------------------------------------------

    private val _button = MutableStateFlow(true)

    /** "Best part button": by the scrubber while a song's best part is ahead (on by default, as Prism). */
    val button: StateFlow<Boolean> = _button.asStateFlow()

    @Volatile private var settings: ForkCanvasSettings.Store? = null
    const val KEY_BUTTON = "best_part_button"

    fun loadSettings(from: ForkCanvasSettings.Store) {
        settings = from
        _button.value = from.get(KEY_BUTTON) != "off"
    }

    fun setButton(on: Boolean) {
        _button.value = on
        runCatching { settings?.put(KEY_BUTTON, if (on) "on" else "off") }
    }

    // ---- display text, the same on both apps -------------------------------------------------

    const val BUTTON_TITLE = "Best part button"
    const val BUTTON_SUBTITLE = "Under the seek bar in a song's first seconds: skips to its most replayed part, or the chorus in its lyrics"
    const val CHIP = "Best part"
    const val CHIP_MINE = "Your best part"
    const val PLAY_FROM = "Play from the best part"
    const val SET_MINE = "Set your best part here"
    const val CLEAR_MINE = "Clear your best part"
    const val NONE_KNOWN = "No best part known for this song"

    /** "1:23". */
    fun clock(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
