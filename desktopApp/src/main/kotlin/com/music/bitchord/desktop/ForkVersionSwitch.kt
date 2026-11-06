package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicVideo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.playback.smart.ForkVersionAlignment
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Fork: "Convert to video" / "Convert to audio" in the player's song menu, the phone's version switch
 * (`MainActivity.switchToMusicOnly` / `switchToVideo`, the row in `SongActionsMenu`,
 * `PlaybackService.smoothSwapCurrentTrackVersion`), with its "Smart audio alignment"
 * (`VersionAudioAligner`, maths in shared [ForkVersionAlignment]).
 *
 * As on the phone: offered for the playing song wherever its menu opens (not a server song, a
 * station, a file or in a party), the other version looked up by YouTube Music's own pairing then a
 * search (`YtMusicRepository.resolveAudio` / `resolveVideo`), a song with none says so and its row
 * stays greyed out, the original row is kept so switching back lands on the exact upload it left,
 * and the queue entry keeps its place and identity. Where the switch lands: YouTube's own time map
 * for an officially paired cut, else with Smart audio alignment on (the default) the two openings'
 * energy envelopes cross-correlated (45 s each, ±40 s), else the same second. A measurement that
 * fails switches with no shift, as on the phone.
 *
 * The PC's: the measurement decodes both openings from their streams (the phone reads its cache);
 * the engine crosses with the quality upgrade's 200 ms cross (`DesktopPlaybackEngine.forkSwitchVersion`).
 * History and scrobbling carry the listen across (Desktop Revived 465c39c4), the queue row is
 * replaced in place (Desktop Revived 7a5509af / `DesktopQueue.withMediaVersion`); the music video's
 * picture follows by itself, `ForkMusicVideo` keys on the playing song.
 */
internal object ForkVersionSwitch {

    const val TO_AUDIO = "Convert to audio"
    const val TO_VIDEO = "Convert to video"
    const val NO_AUDIO = "No audio version found for this video"
    const val NO_VIDEO = "No video version found for this song"
    const val IN_PARTY = "Everyone in Listen Together hears the same version"
    const val MID_MIX = "Not while a mix is playing"
    const val FAILED = "Couldn't switch to that version"
    const val SMART_TITLE = "Smart audio alignment"
    const val SMART_SUBTITLE = "Analyze audio waveforms to match the exact corresponding playback moment"

    private const val KEY_SMART = "smart_version_alignment"
    /** The phone's `ALIGNMENT_MEASURE_TIMEOUT_MS`. */
    private const val MEASURE_TIMEOUT_MS = 30_000L

    private val persistence by lazy { DesktopPersistence() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _smartAlignment = MutableStateFlow(persistence.boolean(KEY_SMART, true))
    val smartAlignment: StateFlow<Boolean> = _smartAlignment.asStateFlow()

    fun setSmartAlignment(on: Boolean) {
        _smartAlignment.value = on
        persistence.saveBoolean(KEY_SMART, on)
    }

    /** The resolve, the measurement and the hand-over; the player's scrubber shows it as loading. */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /** Songs whose other version was looked for and not found: their row says so from then on. */
    private val missing = MutableStateFlow<Set<String>>(emptySet())

    /** The row a switch came from, by the id it switched to: the way back is that exact row. */
    private val origins = ConcurrentHashMap<String, Song>()

    /** Measured shifts, (from, to) -> ms, kept both ways, as the phone's aligner caches them. */
    private val offsets = ConcurrentHashMap<Pair<String, String>, Long>()

    /** Set by the window. */
    @Volatile var engine: DesktopPlaybackEngine? = null

    /** Set by the window: puts [to] in [from]'s place in the queue, the selection and the history. */
    @Volatile var onSwitched: (from: Song, to: Song) -> Unit = { _, _ -> }

    /** The song menu shows the row for [song] only while it plays, as on the phone. */
    fun offers(song: Song, playingId: String?): Boolean =
        song.videoId == playingId && song.localUri == null && song.localPath == null &&
            DesktopSearchClient.isVideoId(song.videoId) &&
            !NavidromeSettings.isNavidromeId(song.videoId) && !NavidromeRadio.isRadio(song.videoId)

    /** Which cut [song] is, so the row offers the other one. */
    fun isAudioVersion(song: Song): Boolean = !song.isVideo

    fun isMissing(song: Song): Boolean = song.videoId in missing.value

    /** The row's tap. */
    fun toggle(song: Song) {
        if (DesktopListenTogether.state.value.inParty) {
            ForkNotice.show(DesktopStrings["version_switch_in_party", IN_PARTY])
            return
        }
        val engine = engine ?: return
        if (_switching.value || engine.state.value.song?.videoId != song.videoId) return
        _switching.value = true
        scope.launch {
            try {
                switch(engine, song)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                DesktopTrackLog.log("version switch failed for '${song.title}': ${failure.message}")
                ForkNotice.show(FAILED)
            } finally {
                DesktopPlayerSettings.versionAlignmentInProgress.value = false
                _switching.value = false
            }
        }
    }

    private suspend fun switch(engine: DesktopPlaybackEngine, song: Song) {
        val wantAudio = song.isVideo
        val back = origins[song.videoId]
        val target = back ?: run {
            DesktopTrackLog.log("version switch: looking for the ${if (wantAudio) "audio" else "video"} of '${song.title}'")
            val found = withContext(Dispatchers.IO) {
                if (wantAudio) YtMusicRepository.resolveAudio(song) else YtMusicRepository.resolveVideo(song)
            }
            if (found == null || found.videoId == song.videoId) {
                missing.update { it + song.videoId }
                ForkNotice.show(if (wantAudio) DesktopStrings["no_audio_version", NO_AUDIO] else DesktopStrings["no_video_version", NO_VIDEO])
                return
            }
            // The queue entry keeps its place and identity; an audio cut from a video says where it came from.
            found.copy(
                isVideoOrigin = if (wantAudio) true else found.isVideoOrigin,
                queueTier = song.queueTier,
                queueEntryId = song.queueEntryId,
                radioName = song.radioName,
                playbackSource = song.playbackSource,
                playbackSourceType = song.playbackSourceType,
                playbackSourceId = song.playbackSourceId,
            )
        }
        // The listener's own choice outranks "Prefer music-only version" (the phone's `MusicOnlyPlayback.keep` / `letGo`)
        if (wantAudio) ForkMusicOnly.letGo(song.videoId) else ForkMusicOnly.keep(target.videoId)
        handOver(engine, song, target, back)
    }

    /**
     * "Prefer music-only version" turned on with a music video audible: [target], its audio version,
     * crossed in like this menu's switch, as the phone's `applyMusicOnly` hands the playing row to
     * `smoothSwapCurrentTrackVersion`. Not video-origin, so Automix treats it as any song.
     */
    fun convertPlaying(song: Song, target: Song) {
        if (DesktopListenTogether.state.value.inParty) return
        val engine = engine ?: return
        if (_switching.value || engine.state.value.song?.videoId != song.videoId) return
        _switching.value = true
        scope.launch {
            try {
                handOver(engine, song, target, back = null)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                DesktopTrackLog.log("music-only: switch failed for '${song.title}': ${failure.message}")
            } finally {
                DesktopPlayerSettings.versionAlignmentInProgress.value = false
                _switching.value = false
            }
        }
    }

    /** The way back from [audio] (a music-only conversion) is [video], the exact row it replaced. */
    fun rememberOrigin(audio: Song, video: Song) {
        origins[audio.videoId] = video
    }

    /** [target] in [song]'s place on the engine, lined up; the window told after. */
    private suspend fun handOver(engine: DesktopPlaybackEngine, song: Song, target: Song, back: Song?) {
        if (engine.state.value.song?.videoId != song.videoId) return
        val live = DesktopMusicSources.resolveLive(target, engine.forkQuality).getOrElse {
            DesktopTrackLog.log("version switch: no stream for '${target.title}': ${it.message}")
            ForkNotice.show(FAILED)
            return
        }
        val map = alignment(engine, song, target, live.stream)
        if (engine.state.value.song?.videoId != song.videoId) {
            live.pendingSubstitute?.cancel()
            return
        }
        val taken = engine.forkSwitchVersion(song.videoId, target, live, map)
        if (!taken) {
            if (engine.state.value.song?.videoId == song.videoId) ForkNotice.show(MID_MIX)
            return
        }
        if (back != null) origins.remove(song.videoId) else origins[target.videoId] = song
        javax.swing.SwingUtilities.invokeLater { onSwitched(song, target) } // the window's state, on its thread
    }

    /**
     * Where a moment of [from] lands in [to]: YouTube's own map for a paired cut (used whether or not
     * smart alignment is on, as on the phone), else the measured shift, else the same moment.
     */
    private suspend fun alignment(engine: DesktopPlaybackEngine, from: Song, to: Song, toStream: DesktopStream): (Long) -> Long {
        YtMusicRepository.versionTimeMap(from.videoId, to.videoId)?.let { official ->
            DesktopPlayerSettings.versionAlignmentInProgress.value = true
            DesktopTrackLog.log("version switch: YouTube's own time map")
            return { pos -> official.map(pos).coerceAtLeast(0L) }
        }
        if (!_smartAlignment.value) return { it }
        DesktopPlayerSettings.versionAlignmentInProgress.value = true
        val shift = offsets[from.videoId to to.videoId] ?: run {
            val fromStream = engine.forkPlayingStream(from.videoId) ?: return@run null
            val started = System.nanoTime()
            val measured = withTimeoutOrNull(MEASURE_TIMEOUT_MS) { measure(fromStream, toStream) }
            DesktopTrackLog.log(
                "version switch: '${from.title}' -> '${to.title}' measured ${measured?.let { "$it ms" } ?: "nothing"} " +
                    "in ${(System.nanoTime() - started) / 1_000_000} ms",
            )
            measured?.also {
                offsets[from.videoId to to.videoId] = it
                offsets[to.videoId to from.videoId] = -it
            }
        } ?: 0L
        return { pos -> (pos + shift).coerceAtLeast(0L) }
    }

    /** Both openings decoded at once, then the phone's correlation. */
    private suspend fun measure(from: DesktopStream, to: DesktopStream): Long? = coroutineScope {
        val a = async(Dispatchers.IO) { head(from) }
        val b = async(Dispatchers.IO) { head(to) }
        val source = a.await() ?: return@coroutineScope null
        val target = b.await() ?: return@coroutineScope null
        ForkVersionAlignment.offsetMs(source.samples, source.rate, target.samples, target.rate, source.count, target.count)
    }

    private class Head(val samples: FloatArray, val count: Int, val rate: Double)

    /** The first [ForkVersionAlignment.HEAD_SECONDS] of [stream], mono at its own rate. */
    private suspend fun head(stream: DesktopStream): Head? {
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(
                url = stream.url,
                headers = stream.headers,
                requested = DesktopPcmFormat(44_100, channels = 1, bytesPerSample = 4, isFloat = true),
                windowed = stream.windowedReads,
                transport = stream.transport,
            ).getOrElse {
                DesktopTrackLog.log("version switch: could not open a stream to measure: ${it.message}")
                return null
            }
            val rate = decoder.outputFormat.sampleRate.toDouble().takeIf { it > 0 } ?: return null
            val limit = (ForkVersionAlignment.HEAD_SECONDS * rate).toInt()
            val out = FloatArray(limit)
            var total = 0
            while (total < limit) {
                coroutineContext.ensureActive()
                val block = decoder.readSamples() ?: break
                val count = minOf(decoder.sampleCount, limit - total)
                if (count <= 0) continue
                System.arraycopy(block, 0, out, total, count)
                total += count
            }
            return if (total == 0) null else Head(out, total, rate)
        } finally {
            decoder.close()
        }
    }

    /**
     * From the audio thread, the moment the other version is heard: the listen carries across
     * (Desktop Revived 465c39c4), before the window's song-keyed effects see the new id.
     */
    fun onEngineSwitched(fromId: String, to: Song) {
        DesktopPlaybackTracker.onVersionSwapped(fromId, to.videoId)
        DesktopScrobbling.onVersionSwapped(fromId, to.videoId)
    }
}

/** [queue] with [to] in [from]'s place, when [from] is still the queue's current row (Desktop Revived 7a5509af). */
internal fun forkQueueWithVersion(queue: DesktopQueue, from: Song, to: Song): DesktopQueue {
    if (queue.current?.videoId != from.videoId) return queue
    return queue.copy(songs = queue.songs.toMutableList().also { it[queue.index] = to })
}

/** [history] with [to] where [from] was, once. */
internal fun forkHistoryWithVersion(history: List<Song>, from: Song, to: Song): List<Song> =
    history.map { if (it.videoId == from.videoId) to else it }.distinctBy { it.videoId }

/** For `NowPlayingScreen(audioVersionSwitching = …)`. */
@Composable
internal fun forkVersionSwitching(): Boolean {
    val switching by ForkVersionSwitch.switching.collectAsState()
    return switching
}

/**
 * The menu row, right under Revert to original / Upgrade quality as on the phone ("which recording,
 * not which quality"), for the playing song only.
 */
@Composable
internal fun ForkVersionRow(song: Song, onDismiss: () -> Unit) {
    val engine = ForkVersionSwitch.engine ?: return
    val playing by engine.state.collectAsState()
    if (!ForkVersionSwitch.offers(song, playing.song?.videoId)) return
    val switching by ForkVersionSwitch.switching.collectAsState()
    val audio = ForkVersionSwitch.isAudioVersion(song)
    val missing = ForkVersionSwitch.isMissing(song)
    DesktopMenuRow(
        icon = if (audio) Icons.Rounded.MusicVideo else BitChordIcons.MusicNote,
        label = when {
            missing && audio -> DesktopStrings["no_video_version", ForkVersionSwitch.NO_VIDEO]
            missing -> DesktopStrings["no_audio_version", ForkVersionSwitch.NO_AUDIO]
            audio -> DesktopStrings["convert_to_video", ForkVersionSwitch.TO_VIDEO]
            else -> DesktopStrings["convert_to_audio", ForkVersionSwitch.TO_AUDIO]
        },
        enabled = !missing && !switching,
        busy = switching,
    ) {
        ForkVersionSwitch.toggle(song)
        onDismiss()
    }
    DesktopMenuDivider()
}

/** Settings > Advanced, the phone's row there. */
@Composable
internal fun ForkSmartAlignmentRow() {
    val on by ForkVersionSwitch.smartAlignment.collectAsState()
    SettingsToggle(
        DesktopStrings["smart_version_alignment", ForkVersionSwitch.SMART_TITLE],
        DesktopStrings["smart_version_alignment_subtitle", ForkVersionSwitch.SMART_SUBTITLE],
        on,
        ForkVersionSwitch::setSmartAlignment,
    )
}
