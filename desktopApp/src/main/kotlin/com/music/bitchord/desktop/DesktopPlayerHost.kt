package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.ui.player.AudioOutputDevice
import com.music.bitchord.ui.player.AudioOutputKind
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.music.bitchord.ui.player.LyricsRomanizationResult
import com.music.bitchord.ui.player.LyricsTranslationResult
import com.music.bitchord.ui.player.MAX_LYRICS_OFFSET_MS
import com.music.bitchord.ui.player.MIN_LYRICS_OFFSET_MS
import com.music.bitchord.ui.player.OutputFormatUi
import com.music.bitchord.ui.player.PartyUi
import com.music.bitchord.ui.player.PlayerHost
import com.music.bitchord.ui.player.PlayerSettingsSource
import com.music.bitchord.ui.player.SystemVolume
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The desktop's answers to the shared player's [PlayerHost].
 *
 * The player itself is the phone's, line for line; this is only what sits
 * under it here — the desktop's settings, its FFmpeg Canvas decoder, its mixers
 * as outputs, its party, its translation client. The window keeps the few
 * pieces of live state it owns ([volumeLevel], [pipelineDialog]) up to date.
 */
internal object DesktopPlayerHost : PlayerHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val settings: DesktopPlayerSettings = DesktopPlayerSettings

    override fun cachedCanvas(song: Song): CanvasArtwork? = ForkClipColours.offer(song, ForkDesktopCanvas.cached(song)) // fork: was DesktopCanvasClient.cached(song)?.toShared(); with a known Spotify backdrop; told to ForkClipColours

    override suspend fun canvasFor(song: Song): CanvasArtwork? = ForkClipColours.offer(song, ForkDesktopCanvas.canvasFor(song)) // fork: kept clips, else the cover clip at once; a Spotify backdrop follows (ForkCanvasSwitch.backdropFor); told to ForkClipColours

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = DesktopCanvasVideo(spec, modifier)

    override val musicVideoSupported: Boolean get() = ForkMusicVideo.supported // fork: music videos, opt-in (ForkMusicVideo)
    @Composable override fun MusicVideo(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier) = ForkMusicVideoSurface(videoId, fit, onRenderedChanged, modifier) // fork
    override val musicVideoAspect: StateFlow<Float> get() = ForkMusicVideo.aspect // fork
    override val musicVideoQuality: StateFlow<com.music.bitchord.ui.player.MusicVideoQualityUi> get() = ForkMusicVideo.quality // fork
    override fun setMusicVideoQuality(height: Int?) = ForkMusicVideo.setQuality(height) // fork

    // Local files carry their art on the row already; there is no remote store to ask.
    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? = song?.thumbnailUrl

    /** The player's volume, 0..1, as the window holds it. */
    val volumeLevel = MutableStateFlow(1f)

    /** Where a drag on the player's volume bar goes — set by the window. */
    @Volatile
    var onVolumeChange: (Float) -> Unit = { volumeLevel.value = it }

    override val volume: SystemVolume = object : SystemVolume {
        override val level: StateFlow<Float> = volumeLevel.asStateFlow()
        override fun set(level: Float) = onVolumeChange(level.coerceIn(0f, 1f))
        override fun refresh() = Unit
    }

    /** The last list handed to the drawer, for turning a row's id back into a mixer. */
    @Volatile
    private var listed: List<String> = emptyList()

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> {
        val selected by DesktopAudioDevices.selected.collectAsState()
        val changes by DesktopAudioDevices.changes.collectAsState()
        val devices = remember(changes) { DesktopAudioDevices.available() }
        return remember(selected, devices) {
            val ids = listOf(DesktopAudioDevices.SYSTEM_DEFAULT) + devices.map { it.id }
            listed = ids
            ids.mapIndexed { index, id ->
                val name = if (id == DesktopAudioDevices.SYSTEM_DEFAULT) {
                    DesktopStrings["d_system_default", "System default"]
                } else {
                    devices.first { it.id == id }.name
                }
                AudioOutputDevice(
                    id = index,
                    name = name,
                    kind = kindOf(name),
                    isActive = id == selected,
                )
            }
        }
    }

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = onOpen

    override fun selectAudioOutput(id: Int?) {
        val device = id?.let { listed.getOrNull(it) } ?: DesktopAudioDevices.SYSTEM_DEFAULT
        DesktopAudioDevices.select(device)
    }

    /** The engine's output stage, kept current by the window. */
    val pipeline = MutableStateFlow(DesktopAudioPipeline())

    override val outputFormat: StateFlow<OutputFormatUi> = pipeline
        .map { it.toOutputFormat() }
        .stateIn(scope, SharingStarted.Eagerly, OutputFormatUi())

    override val party: StateFlow<PartyUi> = DesktopListenTogether.state
        .map {
            PartyUi(
                inParty = it.inParty,
                controlsLocked = it.controlsLocked,
                members = it.members,
                you = it.you,
                code = it.code,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, PartyUi())

    override suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult =
        when (val result = LyricsTranslation.translate(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.Result.Translated -> LyricsTranslationResult.Translated(result.lines)
            is LyricsTranslation.Result.SameLanguage -> LyricsTranslationResult.SameLanguage(result.language)
            LyricsTranslation.Result.Unavailable -> LyricsTranslationResult.Unavailable
        }

    override suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult =
        when (val result = LyricsTranslation.romanize(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.RomanizationResult.Romanized -> LyricsRomanizationResult.Romanized(result.lines)
            LyricsTranslation.RomanizationResult.AlreadyRomanized -> LyricsRomanizationResult.AlreadyRomanized
            LyricsTranslation.RomanizationResult.Unavailable -> LyricsRomanizationResult.Unavailable
        }

    /** The newest notice, for the window to show and clear. */
    val messages = MutableStateFlow<String?>(null)

    override fun showMessage(message: String) {
        messages.value = message
    }

    /** The desktop's signal-chain readout, supplied by the window that has the engine. */
    @Volatile
    var pipelineDialog: (@Composable (onDismiss: () -> Unit) -> Unit)? = null

    @Composable
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) {
        pipelineDialog?.invoke(onDismiss)
    }

    override val lyricsShareAvailable: Boolean get() = true // fork: the phone's lyrics card (ForkLyricsShare)
    @Composable override fun LyricsShareSheet(hazeState: HazeState, request: com.music.bitchord.ui.player.LyricsShareRequest, onDismiss: () -> Unit) = ForkLyricsShareSheet(hazeState, request, onDismiss) // fork
}

/**
 * The shared player's settings, as flows the desktop window feeds from its own
 * state. What the desktop has no setting for keeps the phone's default.
 */
internal object DesktopPlayerSettings : PlayerSettingsSource {
    private val persistence by lazy { DesktopPersistence() }

    override val animatedCanvas = MutableStateFlow(true)
    override val audioQualityCellular = MutableStateFlow(AudioQuality.LOSSLESS)
    override val audioQualityWifi = MutableStateFlow(AudioQuality.LOSSLESS)
    override val canvasOverCellular = MutableStateFlow(true)
    override val fullBleedArtwork = MutableStateFlow(false)
    override val hideSongStatus: StateFlow<Boolean> get() = ForkPlayerSettings.hideSongStatus // fork: was MutableStateFlow(false); its Settings row
    override val showQueueHistory: StateFlow<Boolean> get() = ForkPlayerSettings.showQueueHistory // fork: was MutableStateFlow(false); its Settings row
    override val hideVolumeBar: StateFlow<Boolean> get() = DesktopAppearanceSettings.hideVolumeBar // fork: was MutableStateFlow(false), so the row did nothing
    // A desktop window has no status bar to hide.
    override val hideStatusBar = MutableStateFlow(false)
    override val lastPlayerScreen = MutableStateFlow(
        runCatching { LastPlayerScreen.valueOf(persistence.string(KEY_LAST_SCREEN, "MAIN")) }
            .getOrDefault(LastPlayerScreen.MAIN),
    )
    override val playerTheme: StateFlow<com.music.bitchord.data.settings.PlayerTheme> get() = ForkPlayerTheme.theme // fork: v1.9.1's Player theme, replacing legacyMeshGradient
    override val lyricsBlur = MutableStateFlow(true)
    override val lyricsOffsetMs = MutableStateFlow(
        persistence.int(KEY_LYRICS_OFFSET, 0).coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS),
    )
    override val lyricsSourceOrder = MutableStateFlow<List<LyricsSource>>(LyricsSource.offered)

    // A desktop is never on a metered link as far as this app can tell.
    override val meteredConnection = MutableStateFlow<Boolean?>(false)
    override val preferUsbDac = MutableStateFlow(false)
    override val prioritizeSpotifyCanvas: StateFlow<Boolean> get() = ForkSpotifyCanvasFirst.prioritized // fork: was MutableStateFlow(false); its row under Spotify Canvas setup
    override val reduceAnimation: StateFlow<Boolean> get() = DesktopAppearanceSettings.reduceAnimation // fork: was MutableStateFlow(false)
    override val reduceDynamicBlur: StateFlow<Boolean> get() = DesktopAppearanceSettings.reduceDynamicBlur // fork: was MutableStateFlow(false), so the row did nothing
    override val disableBlendEffects: StateFlow<Boolean> get() = ForkPlayerTheme.disableBlend // fork: v1.9.1's switch (ForkPlayerTheme)
    override val showNerdStats = MutableStateFlow(false)
    override val smartAnalysis = MutableStateFlow(SmartAnalysis())
    override val smartFadeEnabled = MutableStateFlow(false)
    override val smartMixInProgress = MutableStateFlow(false)
    override val smartMixBlend = MutableStateFlow<MixBlend?>(null)
    // fork: members the phone's player (sharedUi) asks for. No watch caps the stream here; the
    // output / Listen Together pill as on the phone; the stats line, the mix popup's view and its
    // progress are written by the engine's audio thread as the phone's CrossfadeController writes
    // them (ForkAutomixReporter, ForkAutomixReport.kt).
    override val streamCeilingOverride = MutableStateFlow<AudioQuality?>(null)
    override val showListenTogetherButton: StateFlow<Boolean> get() = ForkPlayerSettings.showListenTogetherButton // fork: was MutableStateFlow(true); its row, the phone's default (off)
    override val automixDetail = MutableStateFlow<String?>(null)
    override val smartMixView = MutableStateFlow<com.music.bitchord.data.settings.MixTransitionView?>(null)
    override val smartMixProgress = MutableStateFlow(0f)
    // fork: v1.9.1's Automix overview (the mix popup opened between blends). The phone's
    // CrossfadeController lays out the next planned transition while the popup is open; the PC
    // has no preview yet, so the popup shows its empty state between blends (STATUS.md, later list).
    override val smartMixPreview = MutableStateFlow<com.music.bitchord.data.settings.MixTransitionView?>(null)
    val mixViewOpen = MutableStateFlow(false) // fork
    override val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
    override val spotifyCanvasAutoHide: StateFlow<Boolean> get() = ForkPlayerSettings.spotifyCanvasAutoHide // fork: was true, then false; its row, off by default as the phone's fork has it
    override val syncedLyrics = MutableStateFlow(true)
    override val translationLanguage: StateFlow<String> get() = ForkPlayerSettings.translationLanguage // fork: was MutableStateFlow(""), so the row did nothing
    override val versionAlignmentInProgress = MutableStateFlow(false)

    override fun setMixViewOpen(open: Boolean) { mixViewOpen.value = open } // fork: v1.9.1

    override fun setLastPlayerScreen(value: LastPlayerScreen) {
        lastPlayerScreen.value = value
        persistence.saveString(KEY_LAST_SCREEN, value.name)
    }

    override fun setLyricsOffsetMs(value: Int) {
        val clamped = value.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        lyricsOffsetMs.value = clamped
        persistence.saveInt(KEY_LYRICS_OFFSET, clamped)
    }

    private const val KEY_LAST_SCREEN = "last_player_screen"
    private const val KEY_LYRICS_OFFSET = "lyrics_offset_ms"
}

/** Which glyph a mixer gets, from the only thing Java Sound says about it: its name. */
private fun kindOf(name: String): AudioOutputKind {
    val n = name.lowercase(Locale.ROOT)
    return when {
        "bluetooth" in n || "airpods" in n || "buds" in n -> AudioOutputKind.BLUETOOTH
        "usb" in n || "dac" in n -> AudioOutputKind.USB
        "hdmi" in n || "display" in n || "nvidia" in n -> AudioOutputKind.HDMI
        "headphone" in n || "headset" in n -> AudioOutputKind.WIRED
        else -> AudioOutputKind.OTHER
    }
}

private fun DesktopAudioPipeline.toOutputFormat(): OutputFormatUi {
    val depth = when {
        outputIsFloat == true -> "32-bit float"
        outputBytesPerSample != null -> "${outputBytesPerSample * 8}-bit PCM"
        else -> null
    }
    val rate = outputSampleRateHz?.takeIf { it > 0 }
        ?.let { "${"%.1f".format(Locale.ROOT, it / 1000f).removeSuffix(".0")} kHz" }
    return OutputFormatUi(
        summary = listOfNotNull(depth, rate).joinToString(" · "),
    )
}

/** The shared provider a desktop source name refers to, matched on its label. */
internal fun lyricsSourceNamed(name: String): LyricsSource? =
    LyricsSource.entries.firstOrNull { it.label.equals(name, ignoreCase = true) }
        ?: LyricsSource.entries.firstOrNull { it.name.equals(name.replace(' ', '_'), ignoreCase = true) }
