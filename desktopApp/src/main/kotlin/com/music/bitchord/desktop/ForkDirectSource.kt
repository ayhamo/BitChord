package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fork: the phone's "Direct Source Stream" (Settings > Sources, off by default; upstream's
 * `DirectSourceGate`), on the PC. With it on and a source ranked above YouTube:
 *
 *  1. A song is played from an addon from its first note: the resolve waits up to
 *     [LOOKUP_TIMEOUT_MS] for the addons instead of starting on whichever of YouTube and the addons
 *     answers first. An addon's answer plays to the end (no second look, as the phone's
 *     `QualityUpgrade` skips one). No answer in time: YouTube, with the lookup still running handed
 *     over as the usual mid-song upgrade.
 *  2. Nothing is fetched early: the next song is held (every song, not only an addon's, as the
 *     phone's gate holds every resolve) and opened [WINDOW_MS] before its transition, the phone's
 *     window, where the PC otherwise arms an addon song 15 s before.
 *
 * Off in a Listen Together party, as on the phone (a held fetch would leave this computer behind at
 * each sync). The phone also waits for the playhead to run on its own past a seek before opening
 * the window; the PC arms from its position tick, as it already does for addon songs.
 *
 * Desktop Revived has an opt-in `DesktopDirectSource` (bf61fac6) with the same 15 s / 25 s figures;
 * this follows the phone's wording and default instead and keeps the YouTube leg resolving in the
 * background meanwhile, so the fallback starts without a second round trip.
 */
internal object ForkDirectSource {

    const val TITLE_KEY = "direct_source_stream"
    const val TITLE = "Direct Source Stream"
    const val IN_PARTY_KEY = "direct_source_stream_in_party"
    const val IN_PARTY = "Off while you're in a Listen Together party"
    /** The PC row's line under the title; the phone's row has none (its section header says it). */
    const val SUBTITLE = "Plays a song from a source ranked above YouTube from the start, waiting up to 15 s for it, and fetches the next song only 25 s before it plays"

    /** The phone's `DirectSourceGate.WINDOW_MS`. */
    const val WINDOW_MS = 25_000L
    /** The phone's `DirectSourceGate.LOOKUP_TIMEOUT_MS`. */
    const val LOOKUP_TIMEOUT_MS = 15_000L

    private const val KEY = "direct_source_stream"
    private val persistence by lazy { DesktopPersistence() }

    private val _enabled = MutableStateFlow(persistence.boolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        _enabled.value = on
        persistence.saveBoolean(KEY, on)
        addonsCheckedAt = 0L
        activeCached() // read now, off this thread, so the next song is held from the next prepare
    }

    @Volatile private var addonsCheckedAt = 0L
    @Volatile private var hasAddons = false
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Whether it is on, out of a party, and a source outranks YouTube, read fresh when the last
     * reading is stale. Reads the saved sources (DPAPI-sealed on Windows), so never on the audio thread.
     */
    fun active(): Boolean {
        if (!_enabled.value || DesktopListenTogether.state.value.inParty) return false
        if (System.currentTimeMillis() - addonsCheckedAt > ADDONS_RECHECK_MS) refresh()
        return hasAddons
    }

    /**
     * [active] for the audio thread: the last reading, with a stale one re-read on a thread of its
     * own (a DPAPI call and a preferences read every 2 s used to run between two audio blocks).
     */
    private fun activeCached(): Boolean {
        if (!_enabled.value || DesktopListenTogether.state.value.inParty) return false
        if (System.currentTimeMillis() - addonsCheckedAt > ADDONS_RECHECK_MS && refreshing.compareAndSet(false, true)) {
            kotlin.concurrent.thread(name = "BitChord direct source", isDaemon = true) {
                try { refresh() } finally { refreshing.set(false) }
            }
        }
        return hasAddons
    }

    private fun refresh() {
        hasAddons = runCatching {
            persistence.sourceConfigs().any { it.enabled && it.isComplete && it.kind != DesktopSourceKind.YOUTUBE }
        }.getOrDefault(false)
        addonsCheckedAt = System.currentTimeMillis()
    }

    /**
     * From the engine's `prepareNext` (which a blend's end runs on the audio thread): the next song is
     * held until [armLeadMs] before it plays. The reading the current song's resolve just took.
     */
    fun holdsNext(): Boolean = activeCached()

    /** How far before its transition a held next song is opened; asked on the audio thread's position tick. */
    fun armLeadMs(otherwise: Long): Long = if (activeCached()) WINDOW_MS else otherwise

    /**
     * From the registry's race: the addons' answer, waited for up to [LOOKUP_TIMEOUT_MS], or null for
     * the YouTube fallback (the lookup keeps running and is handed over as the upgrade).
     */
    suspend fun awaitAddon(lookup: Deferred<DesktopStream?>): DesktopStream? =
        withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { runCatching { lookup.await() }.getOrNull() }

    private const val ADDONS_RECHECK_MS = 2_000L
}

/** The Sources group's row, after "Add a source", the phone's sub-row. */
@Composable
internal fun ForkDirectSourceRow() {
    val on by ForkDirectSource.enabled.collectAsState()
    val party by DesktopListenTogether.state.collectAsState()
    SettingsToggle(
        DesktopStrings[ForkDirectSource.TITLE_KEY, ForkDirectSource.TITLE],
        if (party.inParty) DesktopStrings[ForkDirectSource.IN_PARTY_KEY, ForkDirectSource.IN_PARTY] else ForkDirectSource.SUBTITLE,
        on && !party.inParty,
    ) { if (!party.inParty) ForkDirectSource.set(it) }
}
