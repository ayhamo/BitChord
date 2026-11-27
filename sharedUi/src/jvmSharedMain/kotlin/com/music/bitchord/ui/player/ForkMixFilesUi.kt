package com.music.bitchord.ui.player

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.automixv2.ForkV2EditorFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.music.bitchord.playback.smart.automixv2.ForkV2MixFile
import com.music.bitchord.playback.smart.automixv2.ForkV2OverrideStore
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fork: importing and exporting mix files (spec/automix/mix-file.md) from the transition editor and the Automix
 * settings. The owner imports ready-made mixes rather than designing them: "i import them, so i do not design, just
 * use then, create a playlist with them and listen". A port of the extension's automix-overview.js importFile and
 * exportMixes, with its messages; the file format is [ForkV2MixFile].
 *
 * The file side is each app's: the phone's system document picker, the PC's file dialog ([open], [saveAs], set when
 * the app starts). A saved edit is replaced only after a confirm that lists what changes ([Confirm], rule 5).
 */
object ForkMixFiles {
    /** Opens a file chooser; calls [onText] with the file's text, or [onFail] with why it couldn't be read. Nothing on cancel. */
    var open: ((onText: (String) -> Unit, onFail: (String) -> Unit) -> Unit)? = null

    /** Asks where to save [text] under the suggested [name]; [done]: true written, false failed, null cancelled. */
    var saveAs: ((name: String, text: String, done: (Boolean?) -> Unit) -> Unit)? = null

    /** What the last import or export did (rule 7), shown under the buttons until the next one. */
    var status by mutableStateOf<String?>(null)
        internal set

    /** An import waiting on the replace question. */
    internal class Confirm(val read: ForkV2MixFile.Read, val plan: ForkV2MixFile.Plan, val text: String)

    internal var confirm by mutableStateOf<Confirm?>(null)

    val available: Boolean get() = open != null

    /**
     * The app's queue for Play these mixes (rule 7), set when the app starts. [playingId] null: nothing is playing.
     * [fresh] replaces the whole queue with [songs] in order and plays the first; [keep]: the first is the playing song,
     * which plays on (only the rest of the queue is replaced). A refusal (a party's host holds the queue) is a reason;
     * null goes ahead.
     */
    interface Queue {
        fun refusal(): String?
        fun playingId(): String?
        fun fresh(songs: List<Song>, keep: Boolean)
    }

    var queue: Queue? = null

    /** The last imported file's songs in its order and how many mixes it had (kept while the app is open). */
    class LastImport(val ids: List<String>, val n: Int, val playlist: Boolean = false)

    var lastImport by mutableStateOf<LastImport?>(null)
        internal set
    private var queuing by mutableStateOf(false)

    /** Play these mixes can be pressed: a file was imported, and no queue request is on its way. */
    val canPlay: Boolean get() = queue != null && lastImport?.ids?.isNotEmpty() == true && !queuing

    /**
     * A song's title, artist and artwork by its id, for songs this session hasn't seen (an export's, a queued one's):
     * YouTube Music's watch entry, 3 s at most as the extension waits on its metadata. Tests set their own.
     */
    internal var lookup: suspend (String) -> Song? = { id ->
        withTimeoutOrNull(3_000) { withContext(Dispatchers.IO) { YtMusicRepository.trackLinks(id).getOrNull() } }
    }

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    /**
     * The songs' titles and artists this app has seen (the editor's songs, imported files), for an export's songs.
     * Concurrent: the editor adds to it from the UI while an export or Play these mixes reads it from [scope]'s
     * coroutines and their lookups (audit 2026-11-26 3a #7; it was a plain HashMap). Typed as a MutableMap on purpose:
     * on a ConcurrentHashMap `id in songs` would call its legacy `contains`, which looks for a value.
     */
    private val songs: MutableMap<String, ForkV2MixFile.Song> = java.util.concurrent.ConcurrentHashMap()

    /** The editor's songs, so an export names them (rule 8: titles and artists where the app knows them). */
    fun know(videoId: String, title: String?, artist: String?) {
        if (title.isNullOrBlank()) return
        songs[videoId] = ForkV2MixFile.Song(videoId, title, artist?.takeIf { it.isNotBlank() })
    }

    private fun songOf(id: String): ForkV2MixFile.Song = songs[id] ?: ForkV2MixFile.Song(id)

    /** A's tempo as this app reads it, when its analysis is at hand (the pair the editor shows). */
    private fun bpmOf(fromId: String): Double? =
        ForkV2EditorFeed.planned.value?.takeIf { it.fromId == fromId }?.gridOut?.bpm?.takeIf { it > 0 }

    // ---------------------------------------------------------------------------------------------
    // import

    fun importMixes() {
        val pick = open ?: return
        // A file of up to 2 MB is parsed off the UI thread (audit 2026-11-26 3a #5); what it changes is applied on it.
        pick({ text -> scope.launch { imported(withContext(Dispatchers.Default) { ForkV2MixFile.read(text) }) } }, { why -> status = "Not imported: $why." })
    }

    internal fun imported(text: String) = imported(ForkV2MixFile.read(text))

    private fun imported(r: ForkV2MixFile.Read) {
        if (r.refused != null) {
            status = "Not imported: ${r.refused}."
            return
        }
        for (m in r.mixes) for (s in listOf(m.from, m.to)) if (s.title != null) songs[s.videoId] = s
        r.queue?.forEach { s -> if (s.title != null) songs[s.videoId] = s } // fork: a Spotify export's playlist songs
        // rule 7: the readable mixes' songs, for Play these mixes (also those kept as saved); a Spotify export queues
        // its playlist in order, songs in no mix too (spotify-mixes.md rule 8)
        queueIds(r).takeIf { it.isNotEmpty() }?.let { lastImport = LastImport(it, r.mixes.size, r.queue != null) }
        val plan = ForkV2MixFile.importPlan(r.mixes) { key -> ForkV2OverrideStore.idsOf(key)?.let { ForkV2OverrideStore.get(it.first, it.second) } }
        if (plan.replace.isEmpty()) {
            status = applied(r, plan, replace = true)
            return
        }
        fun name(m: ForkV2MixFile.Mix) = "${m.from.label} → ${m.to.label}"
        val n = plan.replace.size
        val lines = plan.replace.joinToString("\n\n") { "${name(it.mix)}:\n  " + it.changes.joinToString("\n  ") }
        val ask = (if (n == 1) "One pair already has" else "$n pairs already have") + " an edit. Replace with the file's?\n\n" + lines +
            (if (plan.add.isNotEmpty()) "\n\n(${plan.add.size} new pair${if (plan.add.size == 1) "" else "s"} are added either way.)" else "")
        confirm = Confirm(r, plan, ask)
    }

    /** Saves the new pairs, and the replacing ones when [replace] (rule 6), and says what happened (rule 7). */
    internal fun applied(r: ForkV2MixFile.Read, plan: ForkV2MixFile.Plan, replace: Boolean): String {
        val list = plan.add + (if (replace) plan.replace.map { it.mix } else emptyList())
        val saved = ArrayList<String>()
        val refused = ArrayList<String>()
        val now = System.currentTimeMillis()
        for (m in list) {
            val ov = m.override.copy()
            // saved as the editor saves: this app's engine, savedAt now, the caps (the file's own would put an
            // import first in line for the unlocked cap)
            ov.engine = ForkV2OverrideStore.ENGINE
            ov.savedAt = now
            val why = ForkV2OverrideStore.put(m.from.videoId, m.to.videoId, ov)
            if (why == null) saved += m.key else refused += "${m.key}: $why"
        }
        val parts = ArrayList<String>()
        parts += "Imported ${saved.size} mix${if (saved.size == 1) "" else "es"}"
        if (plan.same.isNotEmpty()) parts += "${plan.same.size} already saved as they are"
        val kept = r.mixes.size - list.size - plan.same.size
        if (kept > 0) parts += "$kept kept as saved"
        parts += refused
        for (x in r.skipped) parts += "mix ${x.n} skipped: ${x.why}"
        parts += r.notes // a Spotify export: what didn't carry over (spotify-mixes.md rule 9)
        for (m in r.mixes) {
            if (m.key !in saved) continue
            ForkV2MixFile.foldNote(m.override, bpmOf(m.from.videoId))?.let { parts += "${m.from.label}: $it" }
        }
        return parts.joinToString(" · ") + "." + (if (queueIds(r).isNotEmpty()) " Play these mixes queues them." else "")
    }

    /** The songs Play these mixes queues: a Spotify export's playlist, else the file's mixes' songs. */
    private fun queueIds(r: ForkV2MixFile.Read): List<String> = r.queue?.map { it.videoId } ?: ForkV2MixFile.mixQueue(r.mixes)

    // ---------------------------------------------------------------------------------------------
    // Play these mixes (rule 7)

    fun playImported() {
        if (!canPlay) return
        queuing = true
        status = "Queuing…"
        scope.launch {
            try {
                status = playNow()
            } finally {
                queuing = false
            }
        }
    }

    /**
     * A fresh queue of the last imported file's songs in its order (mix-file.md rule 7, spotify-mixes.md rule 8): the
     * queue before it is replaced, the playing song too unless it is the file's first, which plays on; the first song
     * plays. A second press starts the same fresh queue again. (Inserting after the playing song, as before, left the
     * file's order to whatever was queued already.) Says what happened (the extension's words).
     */
    internal suspend fun playNow(): String {
        val q = queue ?: return "Not queued: this app has no queue."
        val last = lastImport ?: return "Not queued: import a mix file first."
        q.refusal()?.let { return "Not queued: $it." }
        val playing = q.playingId()
        val songs = resolved(last.ids)
        // the queue may have moved on while the songs were looked up
        if (q.playingId() != playing) return "Not queued: the song changed meanwhile; press Play these mixes again."
        q.fresh(songs, keep = playing != null && playing == last.ids.firstOrNull())
        val n = last.n
        return "Playing ${if (n == 1) "the mix" else "the $n mixes"}: ${last.ids.size} songs in the queue, in the ${if (last.playlist) "playlist" else "file"}'s order."
    }

    /** [ids] as songs: YouTube Music's watch entry, else what the file said (title, artist) and the video's artwork. */
    private suspend fun resolved(ids: List<String>): List<Song> = coroutineScope {
        // eight at a time: a 300-mix file is up to 600 songs
        ids.chunked(8).flatMap { chunk ->
            chunk.map { id -> async { lookupOrNull(id)?.takeIf { it.videoId == id } ?: fallback(id) } }.awaitAll()
        }
    }

    /**
     * [lookup]'s song, null when it failed; a cancellation goes on up. `runCatching` swallowed it, so a cancelled
     * Play these mixes or export went on looking up and then queued or wrote the fallbacks (audit 2026-11-26 3a #7).
     */
    private suspend fun lookupOrNull(id: String): Song? = try {
        lookup(id)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun fallback(id: String): Song {
        val s = songs[id]
        return Song(videoId = id, title = s?.title ?: id, artist = s?.artist ?: "", thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg")
    }

    // ---------------------------------------------------------------------------------------------
    // export

    /** Every saved edit, or only [fromId] > [toId] (the pair on screen) when given. */
    fun exportMixes(fromId: String? = null, toId: String? = null) {
        if (saveAs == null) return
        scope.launch { exportNow(fromId, toId) }
    }

    /** [exportMixes]' work: the songs this session hasn't seen are looked up (rule 8, as the extension's export does). */
    internal suspend fun exportNow(fromId: String? = null, toId: String? = null) {
        val write = saveAs ?: return
        val all = ForkV2OverrideStore.overrides.value
        val keys = if (fromId != null && toId != null) listOf(ForkV2OverrideStore.pairKey(fromId, toId)).filter { it in all } else all.keys.toList()
        if (keys.isEmpty()) {
            status = if (fromId != null) "These two songs have no edit to export." else "No edited pairs to export."
            return
        }
        val pairs = keys.mapNotNull { k -> ForkV2OverrideStore.idsOf(k)?.let { k to it } }
        val unknown = pairs.flatMap { listOf(it.second.first, it.second.second) }.distinct().filter { it !in songs }
        if (unknown.isNotEmpty()) {
            for (s in resolved(unknown)) if (s.title != s.videoId) know(s.videoId, s.title, s.artist)
        }
        val entries = pairs.mapNotNull { (k, ids) ->
            val (a, b) = ids
            val saved = all[k] ?: return@mapNotNull null
            // the tempo the beats count on (rule 8): as saved, else this app's for A; the beat match as it plays (rule 6)
            ForkV2MixFile.Mix(songOf(a), songOf(b), ForkV2MixFile.forExport(saved, if (saved.gridBpm > 0) null else bpmOf(a)))
        }
        val n = ForkV2MixFile.writable(entries)
        if (n == 0) {
            status = "Nothing to export: no valid edit."
            return
        }
        val one = if (n == 1) entries.first { ForkV2MixFile.writable(listOf(it)) == 1 } else null
        val fname = (if (one != null) "${slug(one.from.label)}-${slug(one.to.label)}" else "mixes-${LocalDate.now(ZoneOffset.UTC)}") + ".json" // plain .json, read by its content (mix-file.md rules 1 and 8, extension 45841b0)
        write(fname, ForkV2MixFile.write(entries)) { ok ->
            when (ok) {
                true -> status = "Exported $n mix${if (n == 1) "" else "es"} as $fname."
                false -> status = "Not exported: the file could not be written."
                null -> Unit
            }
        }
    }

    /** A title in a file name: lower case, accents dropped, letters and digits only, 24 at most; "song" when nothing is left. */
    internal fun slug(t: String): String =
        Normalizer.normalize(t.lowercase(), Normalizer.Form.NFKD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("[^a-z0-9]+"), "")
            .take(24)
            .ifEmpty { "song" }
}

/** The replace question for an import that meets saved edits (rule 5): what changes, per pair. */
@Composable
fun ForkMixImportConfirm() {
    val c = ForkMixFiles.confirm ?: return
    fun answer(replace: Boolean) {
        ForkMixFiles.confirm = null
        ForkMixFiles.status = ForkMixFiles.applied(c.read, c.plan, replace)
    }
    AlertDialog(
        // dismissed: as declining, the saved edits kept and the new pairs added
        onDismissRequest = { answer(false) },
        title = { Text(if (c.plan.replace.size == 1) "Replace a saved edit?" else "Replace ${c.plan.replace.size} saved edits?") },
        text = { Text(c.text, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text("Keep mine") } },
    )
}
