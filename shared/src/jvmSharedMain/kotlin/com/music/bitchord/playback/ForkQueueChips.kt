package com.music.bitchord.playback

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.ForkQueueChipsParser
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.QueueChip
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isNavidrome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * What a platform does to put a chip's songs (the candidates) in place of
 * AutoPlay's rows. Called on a background thread; it switches to its own main
 * thread, checks the second argument (is this tap still the latest?) there and
 * makes no edit if it is false. True only when the queue was changed.
 */
typealias ForkQueueChipsReplacer = suspend (candidates: List<Song>, isCurrent: () -> Boolean) -> Boolean

/**
 * Fork: YouTube Music's own queue chips over AutoPlay (owner, 2026-11-06),
 * for the phone and the PC alike.
 *
 * **Where the chips come from.** The radio `next` call AutoPlay already makes
 * (`YtMusicRepository.radio`, playlist `RDAMVM<videoId>`) carries them; that
 * call hands its answer to [record], so no request is added. Each radio answer
 * replaces the chips: AutoPlay seeds on the song playing, so the chips are the
 * ones for the latest seed, "All" selected, as YouTube sends them.
 *
 * **A tap** ([select]) asks `next` for that chip's own queue (its `RDAT…`
 * playlist and params, seeded on the radio's song) and has the platform's
 * [ForkQueueChipsReplacer] swap AutoPlay's rows after the current song for it. Only
 * AutoPlay's rows: what the listener queued and the album or playlist being
 * played stay where they are ([tailFor]). Every tap and every new radio answer
 * bumps a generation, and an answer for an older one is dropped — a fast
 * double tap ends on the second chip, never on whichever request was slower.
 * The generation is checked again inside the platform's edit, on its main
 * thread, so a tap made while the edit was being handed over still wins. A
 * failed request or an edit that couldn't be made keeps the old tail and the
 * old selected chip; nothing retries.
 *
 * **Afterwards** a picked chip (any but "All") stays the station AutoPlay tops
 * up from ([stationFor]) while what plays is still that station: its seed, one
 * of its songs, the song that was playing when it was picked, or a song the
 * listener queued by hand in between. Without that, the next top-up would ask
 * the plain radio of the next song and the queue would drift straight back to
 * "All" with the picked chip still drawn as selected. When the station has
 * nothing left that isn't queued or played, one more page of the same chip is
 * asked for (seeded on its last queued song); once even that brings nothing
 * new, or another queue is playing, it is dropped and AutoPlay asks the radio
 * again, whose chips then replace these.
 *
 * Not for Navidrome songs (their AutoPlay is the server's, never a YouTube
 * radio) and not in a Listen Together party, whose queue is the server's.
 */
object ForkQueueChips {

    /** AutoPlay's tail as a chip leaves it; both apps keep ten AutoPlay rows ahead. */
    const val TAIL_SIZE = 10


    data class State(
        /** The song the radio, and so every chip, was seeded on. */
        val seedVideoId: String,
        val chips: List<QueueChip>,
        val selected: Int,
        /** Ids the radio and the picked chips offered: what tells AutoPlay's tail is this station's. */
        val offeredIds: Set<String>,
        /** The chip being fetched, drawn as busy; null when none is. */
        val loading: Int? = null,
    )

    /**
     * A picked chip AutoPlay tops up from. [refilledFrom] is the song the last
     * extra page was seeded on, so the same seed is never asked twice.
     */
    private data class Station(
        val chipIndex: Int,
        val chip: QueueChip,
        val songs: List<Song>,
        val anchors: Set<String>,
        val refilledFrom: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private val _available = MutableStateFlow(false)

    /** Whether this app can change its queue at all; no row is drawn before it can. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _repeatAll = MutableStateFlow(false)

    /**
     * Repeat-all is on: AutoPlay's rows are stashed for its length (and both
     * apps refuse a chip's edit then), so the row is not drawn at all rather
     * than offering taps that would do nothing. Set by each app's repeat control.
     */
    val repeatAll: StateFlow<Boolean> = _repeatAll.asStateFlow()

    fun setRepeatAll(on: Boolean) {
        _repeatAll.value = on
    }

    @Volatile
    private var replacer: ForkQueueChipsReplacer? = null

    private val lock = Any()
    private var generation = 0L
    private var station: Station? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The phone's playback service or the PC's window installs its queue edit; null takes it away. */
    fun install(replacer: ForkQueueChipsReplacer?) {
        this.replacer = replacer
        _available.value = replacer != null
    }

    /**
     * Read by `YtMusicRepository.radio` before it asks, and handed back to
     * [record] with the answer: a tap made while the radio was in flight is newer
     * than that answer, so the answer must not undo the picked chip.
     */
    fun radioToken(): Long = synchronized(lock) { generation }

    /**
     * Hook from `YtMusicRepository.radio`: [response] is the radio's raw answer
     * for [seedVideoId], [songs] what was parsed from it, [token] what
     * [radioToken] said when the request went out. An answer that a tap (or a
     * newer radio) has overtaken is ignored, so a top-up that started just
     * before a tap can't put "All" back and drop the station (chips audit,
     * 2026-11-06); its rows give way too (both AutoPlay hooks ask [stationFor]
     * again after the radio answers).
     */
    fun record(seedVideoId: String, response: JsonElement, songs: List<Song>, token: Long) {
        val chips = runCatching { ForkQueueChipsParser.parse(response) }.getOrDefault(emptyList())
        synchronized(lock) {
            if (generation != token) return
            // A new radio is a new station: a tap still in flight was for the old one.
            generation++
            station = null
            _state.value = if (chips.size < 2) {
                null
            } else {
                State(
                    seedVideoId = seedVideoId,
                    chips = chips,
                    selected = chips.indexOfFirst { it.selected }.coerceAtLeast(0),
                    offeredIds = songs.mapTo(HashSet()) { it.videoId } + seedVideoId,
                )
            }
        }
    }

    /**
     * Whether the chip row belongs over this queue: chips to show, an app that
     * can act on them, not a party, not repeat-all, not a server song, and AutoPlay's tail (or
     * the song playing) is the station the chips were read from.
     */
    fun shows(state: State?, available: Boolean, queue: List<Song>, currentIndex: Int, inParty: Boolean, repeatAll: Boolean = false): Boolean {
        if (state == null || !available || inParty || repeatAll || state.chips.size < 2) return false
        val current = queue.getOrNull(currentIndex) ?: return false
        if (current.isNavidrome) return false
        if (current.videoId == state.seedVideoId || current.videoId in state.offeredIds) return true
        return (currentIndex + 1 until queue.size).any {
            queue[it].queueTier == QueueTier.AUTOPLAY && queue[it].videoId in state.offeredIds
        }
    }

    /** Asks for the chip at [index] and, once it answers, replaces AutoPlay's tail with it. */
    fun select(index: Int) {
        val replacer = replacer ?: return
        val (tapped, at) = synchronized(lock) {
            val now = _state.value ?: return
            if (index == now.selected && now.loading == null) return
            if (index !in now.chips.indices) return
            _state.value = now.copy(loading = index)
            ++generation to now
        }
        val chip = at.chips[index]
        val isCurrent = { synchronized(lock) { generation == tapped } }
        scope.launch {
            val songs = try {
                InnertubeParser.parseWatchQueue(
                    Innertube.nextPlaylist(chip.playlistId, videoId = at.seedVideoId, params = chip.params),
                    preferAudio = true,
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                Log.w(TAG, "chip '${chip.title}' failed: ${failure.message}")
                null
            }
            if (songs.isNullOrEmpty()) {
                settle(tapped, at, picked = null)
                return@launch
            }
            // Set before the edit: removing AutoPlay's rows re-arms the phone's
            // top-up, which must already read this station rather than ask the
            // plain radio. Put back if the edit isn't made.
            val picked = if (chip.isAll) null else Station(index, chip, songs, anchors(at, songs))
            val previous = synchronized(lock) {
                if (generation != tapped) return@launch
                station.also { station = picked }
            }
            val replaced = try {
                replacer(songs, isCurrent)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                Log.w(TAG, "chip '${chip.title}' could not be queued: ${failure.message}")
                false
            }
            if (!replaced) {
                // Only over this tap's own station: a top-up may have dropped or continued it meanwhile.
                synchronized(lock) { if (generation == tapped && station === picked) station = previous }
                settle(tapped, at, picked = null)
                return@launch
            }
            Log.d(TAG, "chip '${chip.title}' queued from ${songs.size} songs")
            settle(tapped, at, picked = index, offered = songs)
        }
    }

    /** Ends a tap: the new chip selected, or on failure the old one left as it was. */
    private fun settle(tapped: Long, at: State, picked: Int?, offered: List<Song> = emptyList()) {
        synchronized(lock) {
            if (generation != tapped) return
            val now = _state.value ?: return
            if (now.seedVideoId != at.seedVideoId) return
            _state.value = now.copy(
                selected = picked ?: now.selected,
                loading = null,
                offeredIds = now.offeredIds + offered.map { it.videoId },
            )
        }
    }

    /** What keeps a picked station alive: its seed and its songs. The playing song is added by the platform hook. */
    private fun anchors(at: State, songs: List<Song>): Set<String> =
        songs.mapTo(HashSet()) { it.videoId } + at.seedVideoId

    /**
     * Hook from both apps' AutoPlay top-up: the picked chip's songs to top up
     * from instead of the radio of [seedSong], or null to ask the radio as
     * usual. Null, and the station dropped, once [seedSong] isn't the
     * station's (another queue is playing).
     *
     * When nothing in the station is left that isn't already in [existing],
     * one more page of the same chip is asked for, seeded on the last of the
     * station's songs still queued, as YouTube Music carries on a chip's queue;
     * only if that brings nothing new is the station dropped. A chip's queue is
     * about 25 songs and AutoPlay's artist spread often leaves fewer than ten of
     * them, so without this the first top-up after a tap ran dry, asked the
     * plain radio, and its answer put "All" back a second after the tap (seen
     * on the emulator, 2026-11-06). Each seed is asked at most once, so this
     * never repeats a request; a failed page just ends the station.
     */
    suspend fun stationFor(seedSong: Song, existing: List<Song>, limit: Int, preferVideos: Boolean = false): List<Song>? {
        val (live, token) = synchronized(lock) {
            val live = station ?: return null
            // A song queued by hand keeps the station only while the station is still
            // in the queue around it; otherwise an old pick would come back for
            // unrelated music. A server song ends it like any other queue would.
            val ours = !seedSong.isNavidrome && (
                seedSong.videoId in live.anchors ||
                    (seedSong.queueTier == QueueTier.USER_QUEUE && existing.any { it.videoId in live.anchors })
                )
            if (!ours) {
                station = null
                // The radio is asked next, and its answer brings this seed's chips.
                return null
            }
            // Checked as the caller will extend (videos or songs), so a live station never yields nothing.
            if (QueueBuilder.extend(existing, live.songs, limit, preferVideos = preferVideos).isNotEmpty()) return live.songs
            live to generation
        }
        val from = refillSeed(live.songs, existing) ?: seedSong.videoId
        val more = if (from == live.refilledFrom) {
            null
        } else {
            try {
                InnertubeParser.parseWatchQueue(
                    Innertube.nextPlaylist(live.chip.playlistId, videoId = from, params = live.chip.params),
                    preferAudio = true,
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                Log.w(TAG, "chip '${live.chip.title}' has no more songs: ${failure.message}")
                null
            }
        }
        synchronized(lock) {
            // A tap or a new radio while the page was out: this top-up asks the radio as usual,
            // the next one reads whatever station is live then (checked against its own seed).
            if (generation != token || station !== live) return null
            val songs = (live.songs + more.orEmpty()).distinctBy { it.videoId }
            if (more.isNullOrEmpty() || QueueBuilder.extend(existing, songs, limit, preferVideos = preferVideos).isEmpty()) {
                station = null
                return null
            }
            station = live.copy(songs = songs, anchors = live.anchors + more.map { it.videoId }, refilledFrom = from)
            // The row keeps showing over the station's new songs.
            _state.value = _state.value?.let { now ->
                if (now.selected == live.chipIndex) now.copy(offeredIds = now.offeredIds + more.map { it.videoId }) else now
            }
            Log.d(TAG, "chip '${live.chip.title}' continued from $from with ${more.size} songs")
            return songs
        }
    }

    /**
     * What the next page of a used-up station is seeded on: of the station's
     * [songs] already queued or played (in [existing]), the one furthest down
     * the chip's own list, which is where YouTube Music carries a chip's queue
     * on from. By the chip's order rather than [existing]'s, because with
     * "Don't repeat suggestions" on both apps pass the queue followed by the
     * session's history, whose last entries are the playing song and earlier
     * suggestions; read from the end of that list the seed was often the
     * tap's own song, and the page asked for was the tap's request again (chips
     * audit, 2026-11-06). Null when none of them is queued or played.
     */
    internal fun refillSeed(songs: List<Song>, existing: List<Song>): String? {
        val present = existing.mapTo(HashSet()) { it.videoId }
        return songs.lastOrNull { it.videoId in present }?.videoId
    }

    /**
     * Marks the song playing when a chip was picked as part of its station, so
     * the top-up its own edit sets off (seeded on that song, which may be an
     * album track ahead of AutoPlay) keeps the station rather than dropping it.
     * Called by the platform inside its edit.
     */
    fun anchor(videoId: String) {
        synchronized(lock) {
            val live = station ?: return
            station = live.copy(anchors = live.anchors + videoId)
        }
    }

    /**
     * AutoPlay's new rows after [currentIndex]: [candidates] that aren't the
     * song playing, history, or anything the listener or the album still has
     * queued, at most [limit], with AutoPlay's usual spread of artists. The rows
     * AutoPlay had are left out of the comparison, since they are what goes.
     */
    fun tailFor(queue: List<Song>, currentIndex: Int, candidates: List<Song>, limit: Int = TAIL_SIZE): List<Song> {
        if (currentIndex !in queue.indices) return emptyList()
        val kept = queue.filterIndexed { i, song -> i <= currentIndex || song.queueTier != QueueTier.AUTOPLAY }
        return QueueBuilder.extend(kept, candidates, limit)
    }

    /** For tests: back to no chips, no station. */
    internal fun reset() {
        synchronized(lock) {
            generation++
            station = null
            _state.value = null
        }
    }

    private const val TAG = "ForkQueueChips"
}
