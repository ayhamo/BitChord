package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.ForkHandoff
import com.music.bitchord.data.navidrome.ForkHandoffStore
import com.music.bitchord.data.navidrome.NavidromeSettings
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fork: the PC's side of [ForkHandoff] ("continue on the other device"), the twin of the phone's
 * `ForkHandoffPhone`.
 *
 * Everything it needs from the window is already lent to fork code: the queue and its second
 * ([ForkQueues.currentQueue], [ForkQueues.playingAtMs]), the engine ([ForkDebugBridge.engine],
 * set whether or not the bridge is on) and the saved-queue restore ([ForkQueues.restore]). So the
 * window carries no hook for it: [Host] runs inside [ForkNotice.Host], watches the engine for
 * pauses and song changes and the window for going to the tray ([DesktopWindowVisibility]), and
 * draws the banner ("Continue from phone: …, 2:14") when the phone left a newer queue. A click on
 * Continue restores it; nothing plays without that click.
 *
 * Its own Preferences node (`com/music/bitchord/handoff`), not the fork switches' one, so the
 * device id and the "last saved" time stay out of Export data: a backup moved to another PC must
 * not make that PC this one.
 */
internal object ForkHandoffDesktop {

    const val SETTING_TITLE = "Continue on other devices"
    const val SETTING_SUBTITLE = "Leaves your queue on your Navidrome server when you pause or hide the window, so the phone can pick it up at the same song and second"
    const val MENU_TITLE = "Continue from other device"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_LAST_SAVE = "last_save_at"
    private const val KEY_DISMISSED = "dismissed"
    private const val CHECK_GAP_MS = 20_000L
    private const val OFFER_CHECK_MS = 2L * 60 * 1000
    private const val CHECK_BACKOFF_MS = 2 * 60_000L

    private val prefs = Preferences.userRoot().node("com/music/bitchord/handoff")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _offer = MutableStateFlow<ForkHandoff.Note?>(null)
    val offer: StateFlow<ForkHandoff.Note?> = _offer.asStateFlow()

    @Volatile private var lastCheckAt = 0L
    @Volatile private var checkBlockedUntil = 0L

    private val deviceId: String by lazy {
        prefs.get(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            prefs.put(KEY_DEVICE, it)
            flush()
        }
    }

    private val deviceName: String by lazy {
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "PC"
    }

    private val saver = ForkHandoff.Saver(
        scope = scope,
        enabled = { _enabled.value && NavidromeSettings.isConfigured() },
        // The source list is read here, on IO (a DPAPI unprotect and a JSON parse), once per save, not per row on the UI thread.
        snapshot = { val all = configs(); withContext(Dispatchers.Main) { snapshot(all) } },
        write = { note -> ForkHandoffStore.write(note, DesktopTrackLog::log).also { if (it) markSaved(note.savedAt) } },
        log = DesktopTrackLog::log,
    )

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.putBoolean(KEY_ENABLED, on)
        flush()
        if (!on) _offer.value = null
    }

    private fun flush() {
        runCatching { prefs.flush() }
    }

    private fun lastSaveAt() = prefs.getLong(KEY_LAST_SAVE, 0L)

    /** When this PC last wrote or took up a note (the attempt counts: having played here is what matters). */
    private fun markSaved(at: Long) {
        prefs.putLong(KEY_LAST_SAVE, maxOf(at, lastSaveAt()))
        flush()
    }

    // ── Watching the window ─────────────────────────────────────────────

    /**
     * The banner and the watchers, inside [ForkNotice.Host]. The engine is lent to
     * [ForkDebugBridge.engine] a moment after the window first composes, so it is waited for.
     */
    @Composable
    fun Host() {
        LaunchedEffect(Unit) {
            var engine = ForkDebugBridge.engine
            while (engine == null) {
                delay(500)
                engine = ForkDebugBridge.engine
            }
            var playing = false
            var song: String? = null
            engine.state.map { it.isPlaying to it.song?.videoId }.distinctUntilChanged().collect { (isPlaying, id) ->
                if (isPlaying != playing) {
                    playing = isPlaying
                    if (isPlaying) markSaved(System.currentTimeMillis()) // playing here: an older note is not "newer"
                    saver.onPlayingChanged(isPlaying)
                }
                if (id != song) {
                    if (song != null && id != null) saver.onSongChanged()
                    song = id
                }
            }
        }
        val visible by DesktopWindowVisibility.visible.collectAsState()
        LaunchedEffect(visible) {
            if (visible) checkOffer() else saver.onBackground() // the window back from the tray, or the start; hidden to the tray
            // A PC usually stays open while the phone plays, so a window that never left the screen
            // must look too: every 2 minutes while it shows and plays nothing (one small read each).
            while (visible) {
                delay(OFFER_CHECK_MS)
                if (ForkDebugBridge.engine?.state?.value?.isPlaying != true) checkOffer()
            }
        }
        Banner()
    }

    @Composable
    private fun Banner() {
        val note by offer.collectAsState()
        val shown = note ?: return
        Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 72)) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.clip(shape).desktopCard(shape).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Devices, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Continue from ${if (shown.device.kind == ForkHandoff.Device.PHONE) "phone" else shown.device.name}: " +
                        "${shown.current?.title.orEmpty()}, ${ForkHandoff.clock(shown.positionMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
                TextButton(onClick = { restoreOffered() }) { Text("Continue") }
                TextButton(onClick = ::dismiss) { Text("Not now", color = DesktopSecondary) }
            }
        }
    }

    // ── Saving ──────────────────────────────────────────────────────────

    /** The note as the window's queue stands, on the UI thread. */
    private fun snapshot(all: List<DesktopSourceConfig>): ForkHandoff.Note? {
        val queue = ForkQueues.currentQueue()
        if (queue.songs.isEmpty()) return null
        val playing = queue.current ?: return null
        val state = ForkDebugBridge.engine?.state?.value
        val hint = playingHint(playing, state?.takeIf { it.song?.videoId == playing.videoId }?.streamSourceId, all)
        val (items, index, position) = ForkHandoff.snapshot(queue.songs, queue.index, ForkQueues.playingAtMs()) { song ->
            ForkHandoff.itemFor(
                song,
                sourceRow = { sourceRow(it, all) },
                hint = if (song === playing) hint else null,
                pinned = DesktopOriginalVersion.isPinned(song.videoId),
            )
        } ?: return null
        return ForkHandoff.Note(
            savedAt = System.currentTimeMillis(),
            device = ForkHandoff.Device(deviceId, deviceName, ForkHandoff.Device.PC),
            index = index,
            positionMs = position,
            origin = ForkHandoff.originOf(playing),
            items = items,
        )
    }

    private fun configs() = runCatching { DesktopPersistence().sourceConfigs() }.getOrDefault(emptyList())

    private fun refOf(config: DesktopSourceConfig) =
        ForkHandoff.SourceRef(config.kind.name, ForkHandoff.hashBase(config.baseUrl), config.label.ifBlank { null })

    /**
     * A source's own row as the phone can find it: an addon (`addon:<source><track>`), a module
     * (`module:<source>::<module>::<track>`, the phone's track id being `<module>::<track>`), or
     * JioSaavn (`jiosaavn:<track>`).
     */
    private fun sourceRow(song: Song, all: List<DesktopSourceConfig>): Pair<ForkHandoff.SourceRef, String>? {
        DesktopAddonSource.parseTrack(song.videoId)?.let { ref ->
            val config = all.firstOrNull { it.id == ref.sourceId } ?: return null
            return refOf(config) to ref.trackId
        }
        DesktopModuleSource.parseTrack(song.videoId)?.let { ref ->
            val config = all.firstOrNull { it.id == ref.sourceId }
                ?: all.firstOrNull { it.kind == DesktopSourceKind.CUSTOM_MODULE || it.kind == DesktopSourceKind.MODULE }
                ?: return null
            return refOf(config) to "${ref.moduleId}::${ref.trackId}"
        }
        if (song.videoId.startsWith(JIOSAAVN)) {
            val config = all.firstOrNull { it.kind == DesktopSourceKind.JIOSAAVN } ?: return null
            return refOf(config) to song.videoId.removePrefix(JIOSAAVN)
        }
        return null
    }

    /** This PC's key for a source's row of [trackId], or null when it has no such source (switched on). */
    private fun localKey(source: ForkHandoff.SourceRef, trackId: String, all: List<DesktopSourceConfig>): Pair<String, String>? {
        val enabled = all.filter { it.enabled && it.kind != DesktopSourceKind.YOUTUBE }
        val local = ForkHandoff.matchSource(source, enabled.map { ForkHandoff.LocalSource(it.id, it.kind.name, it.baseUrl.ifBlank { null }, it.label.ifBlank { null }) })
            ?: return null
        val config = enabled.first { it.id == local.id }
        val key = when (config.kind) {
            DesktopSourceKind.ADDON -> DesktopAddonSource.trackKey(config.id, trackId)
            DesktopSourceKind.MODULE, DesktopSourceKind.CUSTOM_MODULE -> "module:${config.id}::$trackId"
            DesktopSourceKind.JIOSAAVN -> JIOSAAVN + trackId
            DesktopSourceKind.YOUTUBE -> return null
        }
        return config.id to key
    }

    /** The copy the playing YouTube song is heard as: the engine's serving source, and its row when [noteOpened] saw it. */
    private fun playingHint(song: Song, servingSourceId: String?, all: List<DesktopSourceConfig>): ForkHandoff.Hint? {
        if (!ForkHandoff.isYouTubeId(song.videoId) || servingSourceId == null) return null
        val config = all.firstOrNull { it.id == servingSourceId }?.takeIf { it.kind != DesktopSourceKind.YOUTUBE } ?: return null
        val row = opened[openedKey(song, servingSourceId)]
        val trackId = row?.let { sourceRow(song.copy(videoId = it), all)?.second }
        return ForkHandoff.Hint(refOf(config), trackId)
    }

    private const val JIOSAAVN = "jiosaavn:"

    // ── The playing copy ────────────────────────────────────────────────

    private val opened = java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    })

    private fun matchKey(song: Song) = "${song.title.trim().lowercase()}\u0000${song.artist.trim().lowercase()}"
    private fun openedKey(song: Song, sourceId: String) = "${matchKey(song)}\u0000$sourceId"

    /** Hook in `DesktopSourceRegistry.openBest`: [sourceId] opened its row [rowId] for [song]. */
    fun noteOpened(song: Song, sourceId: String, rowId: String) {
        if (song.title.isBlank()) return
        opened[openedKey(song, sourceId)] = rowId
    }

    /** title+artist → (this PC's source id, its row key), set by a restore, taken once. */
    private val preferred = ConcurrentHashMap<String, Pair<String, String>>()

    /** Hook, first in `DesktopSourceRegistry.resolveLive`: the source and row the phone played [song] as, once. */
    fun takePreferred(song: Song): Pair<String, String>? {
        if (preferred.isEmpty() || !ForkHandoff.isYouTubeId(song.videoId)) return null
        return preferred.remove(matchKey(song))?.also { DesktopTrackLog.log("handoff: '${song.title}' asked of its source first, as the other device played it") }
    }

    // ── Offering and restoring ──────────────────────────────────────────

    private fun checkOffer() {
        if (!_enabled.value || !NavidromeSettings.isConfigured()) return
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
                DesktopTrackLog.log("handoff: could not read the note (${e.javaClass.simpleName}: ${e.message})")
                return@launch
            }
            if (ForkHandoff.shouldOffer(note, deviceId, lastSaveAt(), prefs.get(KEY_DISMISSED, null), System.currentTimeMillis())) {
                _offer.value = note
            }
        }
    }

    fun dismiss() {
        val note = _offer.value ?: return
        prefs.put(KEY_DISMISSED, note.key)
        flush()
        _offer.value = null
    }

    /** The banner's Continue: the restore runs off the UI thread ([restore] reads the source list on IO). */
    private fun restoreOffered() {
        val note = _offer.value ?: return
        _offer.value = null
        scope.launch {
            if (!restore(note)) withContext(Dispatchers.Main) { ForkNotice.show("Could not continue that queue here") }
        }
    }

    /** The menu row: the server's note from another device, whatever its age. */
    fun restoreLatest() {
        scope.launch {
            val note = runCatching { ForkHandoffStore.read() }.getOrNull()?.takeIf { it.device.id != deviceId && it.items.isNotEmpty() }
            val message = when {
                note == null -> "Nothing to continue from another device"
                !restore(note) -> "Could not continue that queue here"
                else -> null
            }
            message?.let { withContext(Dispatchers.Main) { ForkNotice.show(it) } }
        }
    }

    /**
     * [note]'s queue through the saved-queue restore ([ForkQueues.restore]): the songs (addon rows
     * this PC has no addon for dropped), the playing one and its second, each row carrying the
     * note's origin. The phone's pins and playing copy are taken up first.
     *
     * The source list is read on IO (a DPAPI unprotect and a JSON parse, as for the saver's
     * snapshot), once, not per row; only the rest runs on the UI thread.
     */
    private suspend fun restore(note: ForkHandoff.Note): Boolean {
        val all = withContext(Dispatchers.IO) { configs() }
        return withContext(Dispatchers.Main) { restoreWith(note, all) }
    }

    private fun restoreWith(note: ForkHandoff.Note, all: List<DesktopSourceConfig>): Boolean {
        val restored = ForkHandoff.songsFor(note) { item -> item.hint?.let { localKey(it.source, item.id, all)?.second } } ?: return false
        note.items.forEach { item ->
            if (item.kind != ForkHandoff.Item.Kind.YOUTUBE) return@forEach
            if (item.pinned) DesktopOriginalVersion.pin(item.id)
            val hint = item.hint ?: return@forEach
            val trackId = hint.trackId ?: return@forEach
            localKey(hint.source, trackId, all)?.let { preferred["${item.title.trim().lowercase()}\u0000${item.artist.trim().lowercase()}"] = it }
        }
        // A queue from no page in particular still says where it came from.
        val songs = restored.songs.map { song ->
            (if (note.origin == null) song.copy(playbackSource = "From ${note.device.name}", playbackSourceType = PlaybackSourceType.QUEUE) else song)
                .asQueueEntry(QueueTier.CONTEXT) // each its own queue entry, as a saved queue's rows are
        }
        markSaved(note.savedAt)
        DesktopTrackLog.log("handoff: continuing ${songs.size} songs from ${note.device.name} at ${ForkHandoff.clock(restored.positionMs)}")
        ForkQueues.restore(songs, restored.index, restored.positionMs)
        return true
    }

    /** `/handoff-note` on the debug bridge: the note on the server as this PC reads it (no login in it). */
    suspend fun describe(): String {
        val note = ForkHandoffStore.read() ?: return """{"note":null}"""
        return buildJsonObject {
            put("savedAt", note.savedAt)
            put("device", "${note.device.name} (${note.device.kind})")
            put("fromThisPc", note.device.id == deviceId)
            put("songs", note.items.size)
            put("index", note.index)
            put("playing", note.current?.title)
            put("positionMs", note.positionMs)
            put("origin", note.origin?.name)
            put("offered", ForkHandoff.shouldOffer(note, deviceId, lastSaveAt(), prefs.get(KEY_DISMISSED, null), System.currentTimeMillis()))
        }.toString()
    }
}

/** Fork: "Continue from other device" in the player's song menu, after Saved queues. */
@Composable
internal fun ForkHandoffMenuRow() {
    val on by ForkHandoffDesktop.enabled.collectAsState()
    val url by NavidromeSettings.url.collectAsState()
    val user by NavidromeSettings.username.collectAsState()
    if (!on || url.isBlank() || user.isBlank()) return
    DesktopMenuRow(
        icon = Icons.Rounded.Devices,
        label = ForkHandoffDesktop.MENU_TITLE,
        value = "",
        onClick = ForkHandoffDesktop::restoreLatest,
    )
}
