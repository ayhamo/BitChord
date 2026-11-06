package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.data.navidrome.NavidromeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * Fork: upstream v1.9.1's "Prefer music-only version" on the PC (5ad63194: the phone's
 * `MusicOnlyPlayback`, `PlaybackService.convertVideosAround` / `lookUpMusicOnly` / `applyMusicOnly`,
 * `AppSettings.preferMusicOnly`, key `prefer_music_only`, off by default). A YouTube music video is
 * heard as its catalogue song, and never starts as the video first.
 *
 * As on the phone, the playing row and the next one are converted, so a blend into the next track is
 * planned against the song rather than found to be a video when it arrives: a converted row is an
 * ordinary audio row ([asMusicOnlyOf]: not video-origin), which is all Automix, crossfade and the
 * source upgrade need to treat it like any song. The audio version is the one the player menu's
 * "Convert to audio" finds (`YtMusicRepository.resolveAudio`, cached there), so the two agree. A
 * video with no audio version, or none within [ANSWER_TIMEOUT_MS], plays as itself without its
 * picture ([settle]); a later play asks again ([forgetExcept]). The menu's own switch outranks the
 * setting ([keep], [letGo]). Never in a party, where everyone hears the one cut.
 *
 * The PC's: the phone holds a video's bytes above its cache and replaces the ExoPlayer row; here the
 * engine is told songs, not rows, so the two holds sit where the engine takes a song:
 * - the playing song: [convertedForLoad], first thing in the engine's load resolve. It waits there
 *   (the player shows loading, as the phone's buffering) and on an answer the engine loads the audio
 *   version instead ([loadConverted]) and the window's queue row, selection and history follow
 *   ([onConverted], the version switch's own `onSwitched`).
 * - the next song: [holdsNext], in `prepareNext` right after the old next deck is cleared, so a
 *   video is neither read ahead nor analysed (the phone's `prefetchAround` filter and
 *   `nextStreamReady`). On an answer the window's row is replaced, and the window's own prepare
 *   effect asks for the audio version; with none the video is prepared after all.
 * - turned on with a video audible: crossed in as the player menu's switch does
 *   ([ForkVersionSwitch.convertPlaying]), the phone's `smoothSwapCurrentTrackVersion`.
 *
 * Autoplay needs no change here: the phone's 5ad63194 stops a video seed from asking for a station
 * of videos, and the PC's station (`DesktopAutoplay.tracksFor`) never asks for videos.
 */
internal object ForkMusicOnly {

    /** The phone's `ANSWER_TIMEOUT_MS`: how long a row waits for its audio version. */
    const val ANSWER_TIMEOUT_MS = 15_000L

    /** The hold's own limit, past [ANSWER_TIMEOUT_MS], as the phone's `HOLD_LIMIT_MS`. */
    private const val HOLD_LIMIT_MS = ANSWER_TIMEOUT_MS + 5_000L

    private const val KEY = "prefer_music_only"

    private val persistence by lazy { DesktopPersistence() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _on = MutableStateFlow(persistence.boolean(KEY, false))
    val on: StateFlow<Boolean> = _on.asStateFlow()

    /** Videos the listener switched to by hand, which stay videos. */
    private val kept = MutableStateFlow<Set<String>>(emptySet())

    /** Videos playing as themselves for now, because no audio version was found. */
    private val settled = MutableStateFlow<Set<String>>(emptySet())

    /** Audio-version lookups running, by video id; null answer = none. */
    private val lookups = ConcurrentHashMap<String, Deferred<Song?>>()

    /** The last song the window asked the engine to prepare next, held or not. */
    @Volatile private var askedNext: Song? = null

    /** Set by the window: puts [to] in [from]'s queue row (and the selection and history, when it is the current one). */
    @Volatile var onConverted: (from: Song, to: Song) -> Unit = { _, _ -> }

    fun applies(): Boolean = _on.value && !DesktopListenTogether.state.value.inParty

    /** For `PlayerHost.showsMusicVideoPicture`: a video playing as audio shows its artwork. */
    fun showsPicture(videoId: String): Boolean = !applies() || videoId in kept.value

    fun keep(videoId: String) = kept.update { it + videoId }

    fun letGo(videoId: String) = kept.update { it - videoId }

    fun setOn(value: Boolean) {
        if (_on.value == value) return
        _on.value = value
        persistence.saveBoolean(KEY, value)
        // Nothing decided under the old value stands, and the queue is judged again at once.
        kept.value = emptySet()
        settled.value = emptySet()
        rejudge()
    }

    /** A YouTube music video, not a download, a server song or a station: what the phone's `bitchord://watch?…&m=1` rows are. */
    private fun eligible(song: Song): Boolean =
        song.isVideo && song.localUri == null && song.localPath == null &&
            DesktopSearchClient.isVideoId(song.videoId) &&
            !NavidromeSettings.isNavidromeId(song.videoId) && !NavidromeRadio.isRadio(song.videoId) &&
            DesktopDownloadManager.savedFile(song) == null

    /** Whether [song] is a music video still owed its answer. */
    fun holds(song: Song): Boolean =
        applies() && eligible(song) && song.videoId !in kept.value && song.videoId !in settled.value

    private fun settle(videoId: String) = settled.update { it + videoId }

    /** A later play asks again, as a failed lookup may only have been the network. */
    private fun forgetExcept(ids: Set<String>) = settled.update { it intersect ids }

    /** One lookup per video at a time; null when there is no audio version (then settled). */
    private fun lookup(video: Song): Deferred<Song?> {
        lookups[video.videoId]?.let { return it }
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                val found = withTimeoutOrNull(ANSWER_TIMEOUT_MS) {
                    runCatching { YtMusicRepository.resolveAudio(video) }.getOrNull()
                }?.takeIf { it.videoId != video.videoId && !it.isVideo }
                if (found == null) {
                    DesktopTrackLog.log("music-only: no audio version of '${video.title}', playing the video's own audio")
                    settle(video.videoId)
                }
                found
            } finally {
                lookups.remove(video.videoId)
            }
        }
        lookups.putIfAbsent(video.videoId, job)?.let { job.cancel(); return it }
        job.start()
        return job
    }

    /**
     * From the engine's load resolve: the audio version to load instead of [song], or null to go on
     * with [song]. Waits while a music video is owed its answer; released at once if the setting is
     * turned off meanwhile.
     */
    suspend fun convertedForLoad(song: Song): Song? {
        forgetExcept(setOfNotNull(song.videoId, askedNext?.videoId))
        if (!holds(song)) return null
        DesktopTrackLog.log("music-only: holding '${song.title}' for its audio version")
        val answer = withTimeoutOrNull(HOLD_LIMIT_MS) {
            coroutineScope {
                val found = lookup(song)
                val released = async<Song?> { _on.first { !it }; null }
                select<Song?> {
                    found.onAwait { it }
                    released.onAwait { it }
                }.also { released.cancel() }
            }
        }
        if (answer == null || !applies()) return null
        return answer.asMusicOnlyOf(song)
    }

    /**
     * The engine loads [audio] in [video]'s place, from where [video] was asked to start (YouTube's
     * own time map when it has one, as the phone's `applyMusicOnly`), and the window's queue follows.
     * On the window's thread, where loads come from, and only if [video] is still what it is loading.
     */
    fun loadConverted(engine: DesktopPlaybackEngine, video: Song, audio: Song, playWhenReady: Boolean, startAtMs: Long) {
        SwingUtilities.invokeLater {
            if (engine.state.value.song?.videoId != video.videoId) return@invokeLater
            DesktopTrackLog.log("music-only: playing '${audio.title}' (${audio.videoId}) for '${video.title}'")
            rememberPair(video, audio)
            val start = if (startAtMs > 0L) {
                YtMusicRepository.versionTimeMap(video.videoId, audio.videoId)?.map(startAtMs)?.coerceAtLeast(0L) ?: startAtMs
            } else {
                0L
            }
            onConverted(video, audio)
            engine.load(audio, playWhenReady, start)
            scope.launch { DesktopScrobbling.updateNowPlaying(audio) }
        }
    }

    /**
     * From the engine's `prepareNext`: true when [song] is a music video owed its answer, which is
     * then neither read ahead nor analysed. Its row is converted when the answer comes (the window
     * then asks for the audio version), or with none [song] is prepared after all.
     */
    /** Next songs whose answer [holdsNext] is already waiting on. */
    private val waitingNext: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A next song asked for while the engine defers it (mid-blend), so [rejudge] asks for the right one. */
    fun noteNext(song: Song?) {
        askedNext = song
    }

    fun holdsNext(song: Song?, engine: DesktopPlaybackEngine): Boolean {
        askedNext = song
        if (song == null || !holds(song)) return false
        if (!waitingNext.add(song.videoId)) return true // asked again while its answer is still coming
        DesktopTrackLog.log("music-only: next '${song.title}' waits for its audio version, not read ahead")
        scope.launch {
            val audio = try { lookup(song).await() } finally { waitingNext.remove(song.videoId) }
            SwingUtilities.invokeLater {
                if (askedNext?.videoId != song.videoId) return@invokeLater
                if (audio != null && applies()) {
                    DesktopTrackLog.log("music-only: next '${song.title}' becomes '${audio.title}' (${audio.videoId})")
                    rememberPair(song, audio)
                    onConverted(song, audio.asMusicOnlyOf(song))
                } else {
                    engine.prepareNext(song)
                }
            }
        }
        return true
    }

    /** "Convert to video" on the converted row goes back to this exact upload, as the phone's `rememberVideoVersion`. */
    private fun rememberPair(video: Song, audio: Song) {
        YtMusicRepository.rememberVideoVersion(audio.videoId, video)
        ForkVersionSwitch.rememberOrigin(audio, video)
    }

    /** The setting changed: the playing video and the held or prepared next song are judged again. */
    private fun rejudge() {
        val engine = ForkVersionSwitch.engine ?: return
        val playing = engine.state.value
        val song = playing.song
        if (song != null && !playing.isLoading && holds(song)) {
            scope.launch {
                val audio = lookup(song).await() ?: return@launch
                if (!applies() || engine.state.value.song?.videoId != song.videoId) return@launch
                SwingUtilities.invokeLater { ForkVersionSwitch.convertPlaying(song, audio.asMusicOnlyOf(song)) }
            }
        }
        // Prepared again: a video read ahead is dropped and held, a held one is read ahead after all.
        askedNext?.let { engine.prepareNext(it) }
    }
}

/**
 * This audio version, standing in [video]'s place in the queue (the phone's `asMusicOnlyOf`). Not
 * video-origin, unlike the player menu's switch: that flag keeps Automix off a row, and the point of
 * converting ahead is to have it on.
 */
internal fun Song.asMusicOnlyOf(video: Song): Song = copy(
    isVideoOrigin = false,
    queueTier = video.queueTier,
    queueEntryId = video.queueEntryId,
    radioName = video.radioName,
    playbackSource = video.playbackSource,
    playbackSourceType = video.playbackSourceType,
    playbackSourceId = video.playbackSourceId,
)

/**
 * [queue] with [to] in [from]'s row: the first one from the playing row on with the same id and
 * queue entry (the next row is usually just after it), else anywhere (repeat all's wrap).
 */
internal fun forkQueueWithConverted(queue: DesktopQueue, from: Song, to: Song): DesktopQueue {
    fun Song.isRow() = videoId == from.videoId && queueEntryId == from.queueEntryId
    val at = (queue.index until queue.songs.size).firstOrNull { queue.songs[it].isRow() }
        ?: queue.songs.indexOfFirst { it.isRow() }.takeIf { it >= 0 }
        ?: return queue
    return queue.copy(songs = queue.songs.toMutableList().also { it[at] = to })
}

/** The first row of the PC's Content Preferences group (`ForkContentPreferencesGroup`), the phone's under "Home and Videos". */
@Composable
internal fun ForkPreferMusicOnlyRow() {
    val on by ForkMusicOnly.on.collectAsState()
    SettingsToggle(
        DesktopStrings["prefer_music_only", "Prefer music-only version"],
        DesktopStrings[
            "prefer_music_only_subtitle",
            "Music videos play as their audio version, and the next track in the queue is converted ahead of time so Automix and crossfade work",
        ],
        on,
        ForkMusicOnly::setOn,
    )
}
