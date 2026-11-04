package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork: the phone's "Track length tolerance" (`AppSettings.upgradeLengthSlackSeconds`, Settings >
 * Sources > Source matching): how far a better copy's runtime may sit from the playing track's
 * before a mid-song swap onto it is refused. Same key, range (0 to 10 s) and default (5 s) as the
 * phone, so a backup carries it either way ([ForkBackup]).
 *
 * The phone asks it in three places, all on the path that cuts into a track already playing:
 * choosing among an upgrade's candidates (`SourceResolver.upgradeFor`), the live lookup that lands
 * late (`QualityUpgrade`), and the audition of the opened copy against the decoder's own length
 * (`PlaybackService.auditionVerdict`). The PC has the last two as one path: both the late live
 * race and the second look end in the engine's `take`, then `swapStream` opens the copy. So:
 *
 *  - [catalogueAgrees], in `take`, before anything is opened: the runtime the catalogue gave for
 *    the copy ([DesktopStream.durationSec]) against the playing track's. Unlike the phone's
 *    `sameRecordingAs`, an unknown side is a pass here: only the addon source states a runtime
 *    on the PC, and refusing every copy without one would turn JioSaavn and the modules' upgrades
 *    off. The decoder check below still catches those.
 *  - [refuses], in `swapStream` once the copy is open: the decoder's length against what is
 *    playing, the phone's audition. Only for a copy [take][expect] handed over, so a re-open or
 *    "Revert to original" (the same `swapStream`) is never refused for being a different cut.
 *
 * Choosing among a source's rows is left as it is: the PC's `DesktopSourceRegistry.preferred`
 * tries rows within 2 s first but falls back to every row, so it never threw a candidate out
 * before the setting was asked, which was the phone's bug (see its `upgradeSlackSec`).
 */
internal object ForkUpgradeLength {

    const val KEY = "upgrade_length_slack_seconds"
    const val DEFAULT_SECONDS = 5
    const val MIN_SECONDS = 0
    const val MAX_SECONDS = 10

    private val _seconds = MutableStateFlow(read())
    val seconds: StateFlow<Int> = _seconds

    private fun read(): Int = runCatching {
        DesktopPersistence().int(KEY, DEFAULT_SECONDS)
    }.getOrDefault(DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun set(value: Int) {
        val normalized = value.coerceIn(MIN_SECONDS, MAX_SECONDS)
        DesktopPersistence().saveInt(KEY, normalized)
        _seconds.value = normalized
    }

    /** The playing track's runtime: the decoder's when it has one, else the row's. */
    private fun playingMs(song: Song, playingDurationMs: Long): Long =
        playingDurationMs.takeIf { it > 0 } ?: (song.durationText.durationSeconds() * 1_000).toLong()

    /** Whether the catalogue's runtime for [better] is within the tolerance; an unknown side passes. */
    fun catalogueAgrees(song: Song, better: DesktopStream, playingDurationMs: Long): Boolean {
        val candidateMs = better.durationSec?.takeIf { it > 0 }?.times(1_000L) ?: return true
        val playing = playingMs(song, playingDurationMs).takeIf { it > 0 } ?: return true
        val slack = _seconds.value
        if (abs(candidateMs - playing) <= slack * 1_000L) return true
        DesktopTrackLog.log(
            "'${song.title}': ${DesktopMusicSources.sourceNameFor(better)}'s copy runs ${candidateMs / 1_000}s " +
                "against ${playing / 1_000}s playing, outside the ±${slack}s tolerance; searching again",
        )
        return false
    }

    /** The copies `take` handed to `swapStream`, by identity: a data class equal to a re-open's must not count. */
    private val expected: MutableSet<DesktopStream> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

    fun expect(stream: DesktopStream) {
        // A copy that failed to open, or whose song was skipped first, is never asked about; a few
        // of those are kept rather than tracked, and the set is dropped before it could grow.
        if (expected.size >= 16) expected.clear()
        expected += stream
    }

    /**
     * Whether the opened copy [stream] (an upgrade's, see [expect]) runs outside the tolerance by its
     * decoder's own length. Unknown on either side is a pass, as the phone's audition passes a
     * previous duration it doesn't know.
     */
    fun refuses(song: Song, stream: DesktopStream, decoderDurationUs: Long?, playingDurationMs: Long): Boolean {
        if (!expected.remove(stream)) return false
        val length = decoderDurationUs?.takeIf { it > 0 }?.div(1_000) ?: return false
        val playing = playingMs(song, playingDurationMs).takeIf { it > 0 } ?: return false
        val slack = _seconds.value
        if (abs(length - playing) <= slack * 1_000L) return false
        DesktopTrackLog.log(
            "keeping '${song.title}' on what is playing — the replacement is ${length}ms against ${playing}ms, " +
                "outside the ±${slack}s tolerance",
        )
        return true
    }
}

/**
 * Settings > Sources, under the source list: the phone's "Source matching" group, with its one
 * slider and footer, worded as the phone's.
 */
@Composable
internal fun ForkSourceMatchingGroup() {
    val stored by ForkUpgradeLength.seconds.collectAsState()
    var dragged by remember(stored) { mutableIntStateOf(stored) }
    val title = DesktopStrings["upgrade_length_slack", "Track length tolerance"]
    val subtitle = DesktopStrings["upgrade_length_slack_subtitle", "Maximum duration difference allowed for a replacement stream"]
    SettingsGroup(DesktopStrings["source_matching", "Source matching"]) {
        if (settingsRowVisible(title, subtitle)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                DesktopBareSlider(
                    value = dragged.toFloat(),
                    onValueChange = { dragged = it.roundToInt() },
                    onValueChangeFinished = { ForkUpgradeLength.set(dragged) },
                    valueRange = ForkUpgradeLength.MIN_SECONDS.toFloat()..ForkUpgradeLength.MAX_SECONDS.toFloat(),
                    steps = ForkUpgradeLength.MAX_SECONDS - ForkUpgradeLength.MIN_SECONDS - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    DesktopStrings.format("seconds_short", dragged, fallback = "%1\$d s"),
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    DesktopStrings[
                        "upgrade_length_slack_footer",
                        "A larger tolerance accepts more source matches; a smaller tolerance is stricter. The default is 5 seconds.",
                    ],
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
