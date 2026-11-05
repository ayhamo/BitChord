package com.music.bitchord.fork

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.navidrome.ForkHandoff
import com.music.bitchord.data.navidrome.ForkHandoffStore
import com.music.bitchord.data.navidrome.NavidromePlatform
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.toSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: the phone's side of [ForkHandoff] ("continue on the other device").
 *
 * **Saving** runs in the playback service, not the screen: music keeps playing (and is paused from
 * the notification) long after Android has closed the Activity, and the note has to follow that.
 * [PlaybackService][com.music.bitchord.playback.PlaybackService] hands in its active player
 * ([attach]; the crossfade swaps players, so a provider, never a player) and reports pauses and
 * song changes ([onPlayingChanged], [onSongChanged]); the main screen reports the app leaving the
 * screen ([onBackground]). [ForkHandoff.Saver] decides when a write goes out.
 *
 * **Offering** is the main screen's: when it comes to the front it asks once ([checkOffer]) whether
 * the PC left a newer note, and shows the chip above the mini player ([offer]); a tap restores
 * through [ForkQueues] (the saved-queue path: queue, song, second, and here the origin too), a
 * close dismisses that device's queue while it plays on with it ([dismiss]). Nothing plays without
 * the tap, and playing here takes the chip away.
 *
 * **The playing copy**: a YouTube song playing as an addon's better copy is noted with that copy
 * (source + its own track id, [noteOpened] from `SourceResolver.streamBest`), so the PC asks it
 * first; a note from the PC sets the same preference here ([preferredStream], asked first by
 * `SourceResolver.substituteForYouTube`), once per song.
 */
object ForkHandoffPhone {

    private const val TAG = "BitChordFork"
    private const val PREFS = "bitchord_handoff"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_LAST_SAVE = "last_save_at"
    private const val KEY_DISMISSED = "dismissed"
    private const val KEY_ENABLED = "enabled"

    /** One offer check per return to the screen at most this often; a failed one waits longer. */
    private const val CHECK_GAP_MS = 20_000L
    private const val CHECK_BACKOFF_MS = 2 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(true)

    /** "Continue on other devices" (Settings, Navidrome group), on by default. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _offer = MutableStateFlow<ForkHandoff.Note?>(null)

    /** The other device's note while it is offered (the chip shows it), else null. */
    val offer: StateFlow<ForkHandoff.Note?> = _offer.asStateFlow()

    @Volatile private var player: (() -> Player?)? = null
    @Volatile private var lastCheckAt = 0L
    @Volatile private var checkBlockedUntil = 0L

    /**
     * Until when a restore is still on its way to its second ([ForkQueues.play] seeks once the new
     * queue's song is ready): its song change and start were written as the song at about 0:00.
     */
    @Volatile private var restoringUntil = 0L
    private const val RESTORE_HOLD_MS = 8_000L

    @Volatile private var playingHere = false // the player's, for the saver's hold

    private val saver = ForkHandoff.Saver(
        scope = scope,
        enabled = { _enabled.value && NavidromeSettings.isConfigured() && prefs != null },
        snapshot = { withContext(Dispatchers.Main) { snapshot() } },
        write = { note -> ForkHandoffStore.write(note) { Log.d(TAG, it) }.also { if (it) markSaved(note.savedAt) } },
        log = { Log.d(TAG, it) },
        offline = { NavidromePlatform.offline.value },
        held = { (_offer.value != null && !playingHere) || System.currentTimeMillis() < restoringUntil }, // an offer holds a paused phone's saves only; a playing one's are real
    )

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_DEVICE, null) == null) p.edit().putString(KEY_DEVICE, UUID.randomUUID().toString()).apply()
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
        prefs = p
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        if (!on) _offer.value = null
    }

    private val deviceId: String get() = prefs?.getString(KEY_DEVICE, null).orEmpty()
    private val device: ForkHandoff.Device get() = ForkHandoff.Device(deviceId, android.os.Build.MODEL.orEmpty().ifBlank { "Phone" }, ForkHandoff.Device.PHONE)

    private fun lastSaveAt(): Long = prefs?.getLong(KEY_LAST_SAVE, 0L) ?: 0L

    /**
     * When this device last wrote, or took up the other device's note: what a note has to be newer
     * than to be offered. Set on the attempt, not only on success: having played here since is what
     * counts, even if that write didn't reach the server.
     */
    private fun markSaved(at: Long) {
        prefs?.edit()?.putLong(KEY_LAST_SAVE, maxOf(at, lastSaveAt()))?.apply()
    }

    // ── The service's hooks ─────────────────────────────────────────────

    /** From the service's `onCreate`: its context and its active player, read at each save. */
    fun attach(context: Context, active: () -> Player?) {
        init(context)
        player = active
    }

    /**
     * From the service's `onDestroy` (main thread): the player is going. A pause or skip still
     * waiting out its debounce is written now, read while the player is still there; it was lost.
     */
    fun detach() {
        if (saver.hasPending) saver.flushPending(runCatching { snapshot()?.copy(playing = false) }.getOrNull())
        player = null
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        playingHere = isPlaying
        if (isPlaying) {
            markSaved(System.currentTimeMillis()) // playing here: a note older than now is not "newer"
            _offer.value = null // and the chip offering the other device's queue is out of date
        }
        saver.onPlayingChanged(isPlaying)
    }

    fun onSongChanged() = saver.onSongChanged()

    /** The app has left the screen. */
    fun onBackground() = saver.onBackground()

    /** The note as the service's player stands, on the main thread (where the player lives). */
    private fun snapshot(): ForkHandoff.Note? {
        val p = player?.invoke() ?: return null
        val count = p.mediaItemCount
        if (count == 0) return null
        val at = p.currentMediaItemIndex.coerceIn(0, count - 1)
        val from = (at - ForkHandoff.RADIUS).coerceAtLeast(0)
        val to = (at + ForkHandoff.RADIUS).coerceAtMost(count - 1)
        val songs = (from..to).map { p.getMediaItemAt(it).toSong() }
        val playing = songs[at - from]
        val hint = playingHint(p, playing)
        val (items, index, position) = ForkHandoff.snapshot(songs, at - from, p.currentPosition) { song ->
            ForkHandoff.itemFor(
                song,
                sourceRow = ::sourceRow,
                hint = if (song === playing) hint else null,
                pinned = OriginalVersion.isPinned(song.videoId),
            )
        } ?: return null
        return ForkHandoff.Note(
            savedAt = System.currentTimeMillis(),
            device = device,
            index = index,
            positionMs = position,
            origin = ForkHandoff.originOf(playing),
            items = items,
            playing = p.isPlaying,
        )
    }

    /** An addon / module / JioSaavn row (`src:<config>::<track>`) as the PC can find it. */
    private fun sourceRow(song: com.music.bitchord.data.model.Song): Pair<ForkHandoff.SourceRef, String>? {
        val (configId, trackId) = SourceRegistry.parseTrackKey(song.videoId) ?: return null
        val config = SourceRegistry.config(configId) ?: return null
        if (config.kind == SourceKind.YOUTUBE) return null
        return refOf(config) to trackId
    }

    private fun refOf(config: com.music.bitchord.data.sources.SourceConfig) =
        ForkHandoff.SourceRef(config.kind.name, ForkHandoff.hashBase(config.baseUrl), config.label.ifBlank { null })

    /**
     * The copy the playing YouTube song is really heard as: a quality upgrade's stream, else the
     * source the player recorded for it; and that source's track id when [noteOpened] saw it.
     */
    private fun playingHint(p: Player, song: com.music.bitchord.data.model.Song): ForkHandoff.Hint? {
        if (!ForkHandoff.isYouTubeId(song.videoId)) return null
        val uri = p.currentMediaItem?.localConfiguration?.uri
        val configId = uri?.let { QualityUpgrade.forcedStream(it)?.sourceConfigId }
            ?: NerdStats.exactSourceFor(song.videoId)?.let { name -> SourceRegistry.configs.value.firstOrNull { it.displayName == name }?.id }
            ?: return null
        val config = SourceRegistry.config(configId)?.takeIf { it.kind != SourceKind.YOUTUBE } ?: return null
        return ForkHandoff.Hint(refOf(config), opened[openedKey(song.title, song.artist, configId)])
    }

    // ── The playing copy ────────────────────────────────────────────────

    /** (title, artist, source) → the source's own track id it served, from `SourceResolver.streamBest`. */
    private val opened = object : java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }.let { java.util.Collections.synchronizedMap(it) }

    private fun openedKey(title: String, artist: String, configId: String) = "${matchKey(title, artist)}\u0000$configId"

    private fun matchKey(title: String, artist: String) = "${title.trim().lowercase()}\u0000${artist.trim().lowercase()}"

    /** Hook in `SourceResolver.streamBest`: [source] opened [trackId] for [target]. */
    fun noteOpened(target: TrackMatcher.Target, configId: String, trackId: String) {
        if (target.title.isBlank()) return
        opened[openedKey(target.title, target.artist, configId)] = trackId
    }

    /** title+artist → (this phone's source id, track id), set by a restore, used once. */
    private val preferred = ConcurrentHashMap<String, Pair<String, String>>()

    /**
     * Hook, first in `SourceResolver.substituteForYouTube`: the copy the PC was playing this song as,
     * asked of that source directly, once. Null (and the usual race) when there is none, the source
     * is off here, or it has nothing.
     */
    suspend fun preferredStream(target: TrackMatcher.Target): SourceStream? {
        if (preferred.isEmpty()) return null
        val (configId, trackId) = preferred.remove(matchKey(target.title, target.artist)) ?: return null
        val source = SourceRegistry.activeForPlayback().firstOrNull { it.configId == configId } ?: return null
        return try {
            source.stream(trackId, SourceResolver.requestForNow())?.copy(sourceConfigId = configId)
                ?.also { Log.d(TAG, "handoff: '${target.title}' asked of ${source.displayName} first, as the other device played it") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "handoff: ${source.displayName} had no copy (${e.javaClass.simpleName})")
            null
        }
    }

    /** This phone's sources, for [ForkHandoff.matchSource]: enabled ones only (a disabled one plays nothing). */
    private fun localSources() = SourceRegistry.configs.value.filter { it.enabled && it.kind != SourceKind.YOUTUBE }.map {
        ForkHandoff.LocalSource(it.id, it.kind.name, it.baseUrl.ifBlank { null }, it.label.ifBlank { null })
    }

    // ── Offering and restoring ──────────────────────────────────────────

    /** The main screen came to the front: one look at the server, at most every [CHECK_GAP_MS]. */
    fun checkOffer() {
        if (!_enabled.value || !NavidromeSettings.isConfigured() || prefs == null) return
        if (NavidromePlatform.offline.value) return // offline mode: nothing goes to the server
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < CHECK_GAP_MS || now < checkBlockedUntil) return
        lastCheckAt = now
        scope.launch {
            val note = try {
                ForkHandoffStore.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkBlockedUntil = System.currentTimeMillis() + CHECK_BACKOFF_MS
                Log.d(TAG, "handoff: could not read the note (${e.javaClass.simpleName}: ${e.message})")
                return@launch
            }
            if (ForkHandoff.shouldOffer(note, deviceId, lastSaveAt(), prefs?.getString(KEY_DISMISSED, null), System.currentTimeMillis())) {
                _offer.value = note
            }
        }
    }

    /**
     * The chip closed: that device's queue is not offered again while it plays on with it; a new
     * song or queue there, or a pause, is ([ForkHandoff.isDismissed]).
     */
    fun dismiss() {
        val note = _offer.value ?: return
        prefs?.edit()?.putString(KEY_DISMISSED, note.dismissKey)?.apply()
        _offer.value = null
    }

    /** How "Continue from other device" went, for its message. */
    enum class Outcome { PLAYED, NOTHING, UNREACHABLE, FAILED }

    /**
     * "Continue from other device" in the player's menu: the server's note whatever its age, if it
     * came from another device. A server that couldn't be read is [Outcome.UNREACHABLE], not
     * "nothing to continue", which it used to say.
     */
    fun restoreLatest(done: (Outcome) -> Unit) {
        scope.launch {
            val read = try {
                Result.success(ForkHandoffStore.read())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "handoff: could not read the note (${e.javaClass.simpleName}: ${e.message})")
                Result.failure(e)
            }
            val note = read.getOrNull()?.takeIf { it.device.id != deviceId && it.items.isNotEmpty() }
            withContext(Dispatchers.Main) {
                done(
                    when {
                        read.isFailure -> Outcome.UNREACHABLE
                        note == null -> Outcome.NOTHING
                        restore(note) -> Outcome.PLAYED
                        else -> Outcome.FAILED
                    },
                )
            }
        }
    }

    /** The chip's tap. The chip goes only once the queue is playing: a failed restore left nothing to tap again. */
    fun restoreOffered(): Boolean {
        val note = _offer.value ?: return false
        if (!restore(note)) return false
        _offer.value = null
        return true
    }

    /**
     * Replaces the queue with [note]'s through [ForkQueues] (main thread): the queue, the playing
     * song, its second, and the origin ("Playing from …", what AutoPlay continues). Addon rows the
     * phone has no addon for are dropped; the PC's pins and playing copy are taken up first.
     */
    fun restore(note: ForkHandoff.Note): Boolean {
        val here = localSources()
        val restored = ForkHandoff.songsFor(note) { item ->
            val hint = item.hint ?: return@songsFor null
            ForkHandoff.matchSource(hint.source, here)?.let { SourceRegistry.trackKey(it.id, item.id) }
        } ?: return false
        note.items.forEach { item ->
            if (item.kind != ForkHandoff.Item.Kind.YOUTUBE) return@forEach
            if (item.pinned) OriginalVersion.pin(item.id) // the listener's own "original version", carried
            val hint = item.hint ?: return@forEach
            val trackId = hint.trackId ?: return@forEach
            ForkHandoff.matchSource(hint.source, here)?.let { preferred[matchKey(item.title, item.artist)] = it.id to trackId }
        }
        val origin = note.origin
        val source = QueueSource(
            title = origin?.name ?: note.current?.title.orEmpty(),
            type = origin?.sourceType ?: PlaybackSourceType.QUEUE,
            id = origin?.id,
        )
        // Nothing written until the restore has reached its second (or given up): its song change and
        // start were otherwise saved as the song at about 0:00.
        restoringUntil = System.currentTimeMillis() + RESTORE_HOLD_MS
        val played = ForkQueues.playHandoff(restored.songs, restored.index, restored.positionMs, source) { restoringUntil = 0L }
        if (!played) {
            restoringUntil = 0L
            return false // not taken up: still offered, and still newer than this phone's own note
        }
        markSaved(note.savedAt) // taken up: not offered again
        Log.d(TAG, "handoff: continuing ${restored.songs.size} songs from ${note.device.name} at ${ForkHandoff.clock(restored.positionMs)}")
        return true
    }
}
