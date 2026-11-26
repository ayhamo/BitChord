package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.lyrics.ForkLyricsRules
import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.sharedui.resources.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * Fork: the lyrics rulebook's [view] rules in BitChord's panel (`YT Music Extension/spec/lyrics/
 * lyrics.md`). The rules themselves are pure functions in `:shared` ([ForkLyricsRules]); this file
 * holds the settings they read and the pieces `LyricsPanel` / `NowPlayingScreen` hook in with one
 * line each (FORK.md lists the lines).
 *
 * The settings live here as flows, read by the panel on both platforms; the phone
 * (`ForkLyricsSettings`) and the PC (`ForkLyricsSettingsDesktop`) load and store them.
 */
object ForkLyricsPrefs {
    const val TEXT_SIZE_MIN = 10
    const val TEXT_SIZE_MAX = 40

    /** BitChord's own 34 sp (the rulebook's default is 36; a fresh install keeps BitChord's look). */
    const val TEXT_SIZE_DEFAULT = 34
    const val LINE_SPACING_MIN = 1.0f
    const val LINE_SPACING_MAX = 2.5f

    /** BitChord's own 41 / 34 sp, on the rulebook's 0.1 grid (its default is 1.3). */
    const val LINE_SPACING_DEFAULT = 1.2f

    /** §2.6 "Lyrics by real timing": every lead off (the line changes on its start, the scroll too). Off by default. */
    val realTiming = MutableStateFlow(false)

    /** §4.1 "Auto-scroll": the panel follows the song. On by default. */
    val autoScroll = MutableStateFlow(true)

    /** §5.2 "Duet sides": singers on their sides, a group centred. On by default. */
    val duetSides = MutableStateFlow(true)

    /** §8.1, sp, steps of 1. */
    val textSize = MutableStateFlow(TEXT_SIZE_DEFAULT)

    /** §8.2, times the text size, steps of 0.1. */
    val lineSpacing = MutableStateFlow(LINE_SPACING_DEFAULT)

    /** §9.2 "Romanized as main": the romanized line is the big one. Off by default. */
    val romanizedAsMain = MutableStateFlow(false)

    /** §9.2: with "romanized as main", the original stays under it. On by default. */
    val keepOriginalUnder = MutableStateFlow(true)

    /** §9.1: the languages romanized (all on by default). */
    val romanLanguages = MutableStateFlow(ForkLyricsRules.RomanLanguage.entries.toSet())

    fun clampTextSize(value: Int): Int = value.coerceIn(TEXT_SIZE_MIN, TEXT_SIZE_MAX)

    /** On the 0.1 grid, within range. */
    fun clampLineSpacing(value: Float): Float =
        (Math.round(value.coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX) * 10f) / 10f)

    /** A saved language list ("JAPANESE,KOREAN"); unknown names are dropped, null is every language. */
    fun parseLanguages(saved: String?): Set<ForkLyricsRules.RomanLanguage> =
        if (saved == null) {
            ForkLyricsRules.RomanLanguage.entries.toSet()
        } else {
            saved.split(',').mapNotNull { name -> ForkLyricsRules.RomanLanguage.entries.firstOrNull { it.name == name.trim() } }.toSet()
        }

    fun formatLanguages(set: Set<ForkLyricsRules.RomanLanguage>): String =
        ForkLyricsRules.RomanLanguage.entries.filter { it in set }.joinToString(",") { it.name }
}

/**
 * §3: the song's own lyrics offset, saved by video id, added to the global one. Both in the rule's
 * sign (positive shows the lyrics earlier); BitChord's stored global has the other sign
 * ([ForkLyricsRules.globalFromStored]). Changed in place, no Save step.
 */
object ForkLyricsOffsets {
    /** Where the song offsets are kept: the phone's prefs, the PC's preferences. */
    interface Store {
        fun read(songKey: String): Int?
        fun write(songKey: String, ms: Int?)
    }

    @Volatile
    var store: Store? = null

    /** The rulebook's editor: a slider over ±3000 ms in steps of 50 (§3.3); the field takes more. */
    const val STEP_MS = 50
    const val SLIDER_MS = 3_000

    /** The field's limit for the song offset (a minute either way: past that it is the wrong song). */
    const val FIELD_MS = 60_000

    private val _songs = MutableStateFlow<Map<String, Int>>(emptyMap())
    val songs: StateFlow<Map<String, Int>> = _songs.asStateFlow()

    /** The song the player shows, whose offset applies. */
    val current = MutableStateFlow<String?>(null)

    /** Makes [key] the current song, reading its saved offset once. */
    fun select(key: String?) {
        if (key != null && key !in _songs.value) {
            val saved = runCatching { store?.read(key) }.getOrNull()
            if (saved != null && saved != 0) _songs.value = _songs.value + (key to saved)
        }
        current.value = key
    }

    fun songOffset(key: String?): Int = key?.let { _songs.value[it] } ?: 0

    fun setSongOffset(key: String, ms: Int) {
        val value = ms.coerceIn(-FIELD_MS, FIELD_MS)
        _songs.value = if (value == 0) _songs.value - key else _songs.value + (key to value)
        runCatching { store?.write(key, value.takeIf { it != 0 }) }
    }

    /** The combined offset in BitChord's sign, what `LyricPlayhead` and the lyric seeks take. */
    fun storedCombined(storedGlobal: Int, songMs: Int): Int =
        ForkLyricsRules.storedFromRule(ForkLyricsRules.combinedOffset(songMs, ForkLyricsRules.globalFromStored(storedGlobal)))
}

/** Makes [videoId] the song whose offset applies; from the player and the lyrics side panel. */
@Composable
internal fun ForkTrackLyricsOffset(videoId: String) {
    LaunchedEffect(videoId) { ForkLyricsOffsets.select(videoId) }
}

/**
 * The global and the song's offset together, in BitChord's sign (positive = later), in place of
 * `PlayerSettings.lyricsOffsetMs` where the playhead and the lyric seeks read it.
 */
@Composable
internal fun forkCombinedLyricsOffset(): State<Int> {
    val global = PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val key = ForkLyricsOffsets.current.collectAsStateWithLifecycle()
    val songs = ForkLyricsOffsets.songs.collectAsStateWithLifecycle()
    return remember(global, key, songs) {
        derivedStateOf { ForkLyricsOffsets.storedCombined(global.value, key.value?.let { songs.value[it] } ?: 0) }
    }
}

/** The rule's gaps in place of BitChord's (§6.1), on the lines as they enter the player. */
@Composable
internal fun forkLyricsForView(lines: List<LyricLine>?): List<LyricLine>? =
    remember(lines) { lines?.let(ForkLyricsRules::withSpecGaps) }

/** §2: the rows lit in the panel, with the early highlight, or on their own timing with "real timing" on. */
internal fun forkPanelActiveRows(lines: List<LyricLine>, positionMs: Long): List<Int> =
    if (forkRealTimingSeen.value) activeLyricRows(lines, positionMs) else ForkLyricsRules.activeRows(lines, positionMs)

/**
 * §4.3: following comes back on the button, a tap on a line, or the lyrics shown again, "not on a
 * timer". BitChord's two timers (600 ms after the active line is back on screen, 5 s after the
 * scroll stops) are switched off by this in `LyricsPanel`.
 */
internal const val FORK_FOLLOW_TIMERS = false

/** §2.6: the scroll's own lead, none with "real timing" on. */
internal fun forkScrollLead(leadMs: Long): Long = if (forkRealTimingSeen.value) 0L else leadMs

/**
 * [ForkLyricsPrefs.realTiming] as Compose state, what [forkPanelActiveRows] and [forkScrollLead] read.
 *
 * Why: both run inside `LyricsPanel`'s `derivedStateOf` blocks, which re-run only when a Compose
 * state they read changes. Read straight from the `MutableStateFlow` (the PC's and the phone's
 * settings write it), a toggle while paused left the highlight and the scroll on the old timing until
 * the clock ticked again (audit 2026-11-26 2a #6). Kept in step by [rememberForkLyricsView], which
 * the panel calls first; the flow stays the setting's home.
 */
internal val forkRealTimingSeen = mutableStateOf(false)

/** §2.5: the scroll animates for at most 450 ms. */
internal fun forkScrollSpan(leadMs: Long): Long = minOf(leadMs, ForkLyricsRules.SCROLL_ANIMATION_MAX_MS)

/**
 * Whether [subLines] are a romanization (not a translation): §9.2 and §10.3 lay the two out
 * differently. Marked by identity where the romanization comes back ([forkRomanized]).
 */
internal object ForkLyricsSubKind {
    private val recent = ArrayDeque<List<LyricLine>>()

    @Synchronized
    fun markRomanized(lines: List<LyricLine>) {
        if (recent.any { it === lines }) return
        recent.addLast(lines)
        while (recent.size > 4) recent.removeFirst()
    }

    @Synchronized
    fun isRomanized(lines: List<LyricLine>?): Boolean = lines != null && recent.any { it === lines }
}

/** §9.1: a romanization with the switched-off languages dropped, marked as one. */
internal fun forkRomanized(originals: List<LyricLine>, romanized: List<LyricLine>): List<LyricLine> =
    ForkLyricsRules.romanizedFor(originals, romanized, ForkLyricsPrefs.romanLanguages.value)
        .also(ForkLyricsSubKind::markRomanized)

/** §9.2 "Romanized as main": the romanized lines take the lead and the originals go under them (or nowhere). */
@Composable
internal fun forkMainAndSub(lines: List<LyricLine>, subLines: List<LyricLine>?): Pair<List<LyricLine>, List<LyricLine>?> {
    val asMain by ForkLyricsPrefs.romanizedAsMain.collectAsStateWithLifecycle()
    val keep by ForkLyricsPrefs.keepOriginalUnder.collectAsStateWithLifecycle()
    return remember(lines, subLines, asMain, keep) {
        if (asMain && subLines != null && subLines.size == lines.size && ForkLyricsSubKind.isRomanized(subLines)) {
            val under = if (keep) lines else null
            under?.let(ForkLyricsSubKind::markRomanized)
            subLines to under
        } else {
            lines to subLines
        }
    }
}

/** The panel's sizes and switches, read once per composition of `LyricsPanel`. */
@Stable
internal class ForkLyricsView(
    val sides: Boolean,
    val autoScroll: Boolean,
    private val size: Int,
    private val spacing: Float,
) {
    /** §8: the synced lines' size and line height. */
    val fontSize: TextUnit get() = size.sp
    val lineHeight: TextUnit get() = (size * spacing).sp

    /** Unsynced sheets keep BitChord's 30 / 34 ratio to the synced size. */
    val plainFontSize: TextUnit get() = (size * 30f / 34f).sp
    val plainLineHeight: TextUnit get() = (size * 30f / 34f * spacing).sp

    /** The answering vocal keeps BitChord's 23 / 34. */
    val backingFontSize: TextUnit get() = (size * 23f / 34f).sp
    val backingLineHeight: TextUnit get() = (size * 23f / 34f * spacing).sp

    /** §9.2, §10.3: the line under the original is 0.64 of its size. */
    val subFontSize: TextUnit get() = (size * SUB_RATIO).sp
    val subLineHeight: TextUnit get() = (size * SUB_RATIO * spacing).sp

    /** §9.2 romanization 0.8; §10.3 translation 0.7 on the active line, 0.3 on the others. */
    fun subAlpha(subLines: List<LyricLine>?, active: Boolean): Float = when {
        ForkLyricsSubKind.isRomanized(subLines) -> 0.8f
        active -> 0.7f
        else -> 0.3f
    }

    /** §5.2: a group's line, centred when sides are on. */
    fun centred(line: LyricLine): Boolean = sides && line.alignment == LyricAlignment.Center

    private companion object {
        const val SUB_RATIO = 0.64f
    }
}

@Composable
internal fun rememberForkLyricsView(): ForkLyricsView {
    val sides by ForkLyricsPrefs.duetSides.collectAsStateWithLifecycle()
    val autoScroll by ForkLyricsPrefs.autoScroll.collectAsStateWithLifecycle()
    val size by ForkLyricsPrefs.textSize.collectAsStateWithLifecycle()
    val spacing by ForkLyricsPrefs.lineSpacing.collectAsStateWithLifecycle()
    val realTiming by ForkLyricsPrefs.realTiming.collectAsStateWithLifecycle()
    SideEffect { forkRealTimingSeen.value = realTiming }
    return remember(sides, autoScroll, size, spacing) {
        ForkLyricsView(sides, autoScroll, ForkLyricsPrefs.clampTextSize(size), ForkLyricsPrefs.clampLineSpacing(spacing))
    }
}

/** A group's line set centred: the text, and a one-line row placed in the middle. */
internal fun TextStyle.forkCentre(centre: Boolean): TextStyle = if (centre) copy(textAlign = TextAlign.Center) else this

internal fun Modifier.forkCentreIn(centre: Boolean): Modifier = if (centre) wrapContentWidth(Alignment.CenterHorizontally) else this

/**
 * §4.2: a hand scroll of more than 0.5 px stops the panel following — a touch drag, and the mouse
 * wheel or a trackpad, which never start a drag (on the PC only a drag paused it before).
 */
@Composable
internal fun rememberForkFollowStop(onStop: () -> Unit): NestedScrollConnection {
    val stop by rememberUpdatedState(onStop)
    return remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && abs(available.y) > 0.5f) stop()
                return Offset.Zero
            }
        }
    }
}

/**
 * §4.3: the lyric list with a "Follow lyrics" button over its foot while following is off; a tap
 * brings it back (as do a tap on a line and showing the lyrics again; no timer does).
 */
@Composable
internal fun ForkLyricsFollowBox(
    modifier: Modifier,
    showButton: Boolean,
    onFollow: () -> Unit,
    content: @Composable BoxScope.(Modifier) -> Unit,
) {
    Box(modifier) {
        content(Modifier.fillMaxSize())
        if (showButton) ForkFollowButton(onFollow, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
    }
}

@Composable
private fun ForkFollowButton(onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.VerticalAlignCenter,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = stringResource(Res.string.fork_follow_lyrics),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}
