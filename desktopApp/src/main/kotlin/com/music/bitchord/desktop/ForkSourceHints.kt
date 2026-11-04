package com.music.bitchord.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Fork: the phone's per-source status lines on Settings > Sources (`SourcesScreen`'s `SourceRow`,
 * `statusLine` and the order group's footer), on the PC.
 *
 * The PC's rows said "Checking…" for every addon or module until its editor's Test was pressed,
 * because nothing on the page ever asked a source how it was; the phone probes each one with a
 * server once every time the screen opens and keeps the answer as a [Health]: answering (with what
 * it calls itself), unreachable ("Can’t reach it right now · …", worth leaving on, it will be back),
 * or rejected (its URL or manifest is wrong, red, since no retry fixes it). [probe] is that pass,
 * one request per source per opening of the page, no retries of its own; the PC's addon client
 * keeps its own 429 handling, as the phone's does.
 *
 * The other lines are worked out from what the page already knows: a source switched on that the
 * stream ceiling leaves out ("Not used on … · quality is set to …", the phone's
 * `AudioQuality.permits`, the PC's [DesktopAudioQuality.permits]), JioSaavn's warning that it can
 * match the wrong version, and the footer's "No enabled source can provide lossless audio" when
 * nothing switched on can serve lossless. The PC has one quality for every connection and is never
 * metered, so the phone's "Wi-Fi" / "Mobile data" in the skipped line is "this computer" here.
 */
internal object ForkSourceHints {

    /** The phone's `SourceHealth`. */
    sealed interface Health {
        data class Ok(val detail: String? = null) : Health
        data class Unreachable(val reason: String) : Health
        data class Rejected(val reason: String) : Health
    }

    private val _health = MutableStateFlow<Map<String, Health>>(emptyMap())
    val health: StateFlow<Map<String, Health>> = _health

    /** The last pass, so scrolling the settings list (which re-composes the group) doesn't ask again. */
    @Volatile private var lastProbe: Pair<String, Long>? = null

    /**
     * What the phone keys its probe on (`probeKey`): the sources with a server and their addresses.
     * A source added, removed or pointed elsewhere is asked again; nothing else is.
     */
    internal fun probeKey(configs: List<DesktopSourceConfig>): String =
        configs.filter { it.kind.needsServer && it.isComplete }.joinToString { "${it.id}@${it.baseUrl}" }

    /**
     * One pass over every source with a server, as the phone's `LaunchedEffect(probeKey)`: enabled
     * or not (a source switched off still says whether it would work if switched back on). The same
     * key within [FRESH_MS] is not asked again: the PC's settings list is lazy, and the group
     * leaving the screen and coming back is not the page being opened again.
     */
    /** A manual Test is starting: drop the page's older probe answer so the Test's own result shows. */
    fun forget(id: String) = _health.update { it - id }

    suspend fun probe(configs: List<DesktopSourceConfig>) {
        val key = probeKey(configs)
        val now = System.currentTimeMillis()
        lastProbe?.let { (lastKey, at) -> if (lastKey == key && now - at < FRESH_MS) return }
        lastProbe = key to now
        try {
            configs.filter { it.kind.needsServer && it.isComplete }.forEach { config ->
                val found = withContext(Dispatchers.IO) {
                    runCatching { healthOf(config) }.getOrElse {
                        // The page closing mid-pass is not the source failing.
                        if (it is CancellationException) throw it
                        Health.Unreachable(it.message ?: "Failed")
                    }
                }
                _health.update { it + (config.id to found) }
            }
        } catch (cancelled: CancellationException) {
            lastProbe = null // unfinished: the next opening asks again
            throw cancelled
        }
    }

    /** Asks [config] once, read the way the phone's `AddonSource.health` / `ModuleSource.health` read it. */
    private suspend fun healthOf(config: DesktopSourceConfig): Health = when (config.kind) {
        DesktopSourceKind.ADDON -> DesktopAddonSource.health(config).fold(
            // The PC names a manifest without a name "Addon ready"; the phone says nothing then.
            onSuccess = { Health.Ok(it.takeUnless { text -> text == "Addon ready" }) },
            onFailure = ::addonFailure,
        )
        else -> DesktopModuleSource.health(config).fold(
            onSuccess = { Health.Ok(it.removeSuffix(" available")) },
            onFailure = ::moduleFailure,
        )
    }

    /**
     * The phone's split: an `AddonException` (the URL or the manifest is wrong) is a rejection,
     * anything else a server having a bad minute. The PC's [DesktopAddonUnavailable] (HTTP 5xx, rate
     * limited) extends [DesktopAddonException] where the phone's `AddonUnavailable` doesn't, so it is
     * taken out here.
     */
    internal fun addonFailure(failure: Throwable): Health {
        val reason = failure.message ?: "Could not reach the addon"
        return if (failure is DesktopAddonException && failure !is DesktopAddonUnavailable) {
            Health.Rejected(reason)
        } else {
            Health.Unreachable(reason)
        }
    }

    /** An index that answered with no modules is the phone's one rejection; any other failure is unreachable. */
    internal fun moduleFailure(failure: Throwable): Health =
        if (failure.message == NO_MODULES) {
            Health.Rejected("The index answered but listed no modules — check the URL")
        } else {
            Health.Unreachable(failure.message ?: "Could not reach the module index")
        }

    /**
     * The row's second line, the phone's order: skipped by the ceiling first (a source that won't
     * be asked isn't usefully described by a probe), then [statusLine].
     */
    fun line(
        config: DesktopSourceConfig,
        health: Health?,
        tested: String?,
        quality: DesktopAudioQuality,
        skippedByQuality: Boolean,
    ): String = if (skippedByQuality) skippedLine(quality) else statusLine(config, health, tested)

    /**
     * The phone's `statusLine`. [tested] is what the PC's editor's Test said for this source, used
     * only until the page's own probe has answered. Unfinished keeps the PC's "Setup required" (the
     * phone's "Tap to finish setting up" is a touch screen's).
     */
    fun statusLine(config: DesktopSourceConfig, health: Health?, tested: String? = null): String = when {
        !config.isComplete -> "Setup required"
        config.kind == DesktopSourceKind.JIOSAAVN -> DesktopStrings[
            "jiosaavn_mismatch_warning",
            "May match the wrong version of a song. Enable at your own risk.",
        ]
        health is Health.Ok -> listOfNotNull(health.detail, config.kind.labels.take(3).joinToString(" · "))
            .joinToString(" · ")
        health is Health.Rejected -> health.reason
        health is Health.Unreachable -> DesktopStrings.format(
            "source_unreachable",
            health.reason,
            fallback = "Can’t reach it right now · %1\$s",
        )
        tested != null -> tested
        config.kind.needsServer -> DesktopStrings["checking", "Checking…"]
        else -> config.kind.labels.take(3).joinToString(" · ")
    }

    fun skippedLine(quality: DesktopAudioQuality): String = DesktopStrings.format(
        "source_skipped_by_quality",
        DesktopStrings["d_this_computer", "this computer"],
        DesktopStrings[quality.name.lowercase(), quality.label],
        fallback = "Not used on %1\$s · quality is set to %2\$s",
    )

    /** Only a rejection is coloured: a server that is merely down will be back without anyone acting. */
    fun rejected(health: Health?, skippedByQuality: Boolean): Boolean = !skippedByQuality && health is Health.Rejected

    /** Whether the ceiling in force would cap a lossless stream anyway (the phone's `cappedByQuality`). */
    fun cappedByQuality(quality: DesktopAudioQuality): Boolean = quality != DesktopAudioQuality.LOSSLESS

    /** The phone's `anyLosslessSource`: asked of the kinds, so a source added later answers on its own terms. */
    fun anyLosslessSource(configs: List<DesktopSourceConfig>): Boolean =
        configs.any { it.enabled && it.isComplete && it.kind.supportsLossless }

    /** The order group's footer: the order note, then the ceiling's note or the no-lossless warning. */
    fun footer(configs: List<DesktopSourceConfig>, quality: DesktopAudioQuality): String = buildString {
        append(
            DesktopStrings[
                "sources_order_footer",
                "If a source does not have a track or cannot be reached, BitChord tries the next one. " +
                    "Sources above YouTube can replace its recording when they return a better version.",
            ],
        )
        if (cappedByQuality(quality)) {
            append("\n\n")
            append(
                DesktopStrings[
                    "sources_quality_capped",
                    "This connection is set below High quality, so streams are transcoded to fit that limit. Downloads are unaffected.",
                ],
            )
        } else if (!anyLosslessSource(configs)) {
            append("\n\n")
            append(
                DesktopStrings[
                    "sources_no_lossless",
                    "No enabled source can provide lossless audio. Add an addon to play lossless files without transcoding.",
                ],
            )
        }
    }

    private const val FRESH_MS = 30_000L

    /** `DesktopModuleSource.health`'s message for an empty index. */
    internal const val NO_MODULES = "The index listed no modules"
}

/** Starts the page's probe; inside the Sources group, so only while that page is showing. */
@Composable
internal fun ForkSourceProbe(configs: List<DesktopSourceConfig>) {
    val key = ForkSourceHints.probeKey(configs)
    LaunchedEffect(key) { ForkSourceHints.probe(configs) }
}

/** The phone's footer under the sources, in the PC's caption style. */
@Composable
internal fun ForkSourcesFooter(configs: List<DesktopSourceConfig>, quality: DesktopAudioQuality) {
    // A note under the rows, not a row: hidden while Settings is being searched, so it doesn't
    // top every search's results (the rows above it filter themselves the same way).
    if (LocalSettingsQuery.current.isNotBlank()) return
    Text(
        ForkSourceHints.footer(configs, quality),
        color = DesktopSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
    )
}
