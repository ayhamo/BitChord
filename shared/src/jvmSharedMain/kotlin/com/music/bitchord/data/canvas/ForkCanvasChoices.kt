package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import java.io.File
import java.util.Properties

/**
 * Fork: what the listener chose for one song's canvas, from the player's menu (Prism's per-song
 * canvas rows, audit 2026-11-20 part C 7): canvas off for this song, another part of its music
 * video, another video picked from a list, or "not this video".
 *
 * Kept in `fork-canvas-choices.properties` beside the saved lookups ([ForkCanvasStore]), one line
 * per song: `<songId>=<off>|<picked video>|<part>|<rejected,...>|<video shown>`. A choice moves
 * [ForkCanvasSettings.generation] (rule 10), so the player asks again at once, and the loop's key
 * ([keySuffix]) is a new one, so the loop already cut stays saved for when the choice comes back.
 */
object ForkCanvasChoices {

    private const val TAG = "ForkCanvasChoices"
    private const val FILE = "fork-canvas-choices.properties"
    private const val MAX_ENTRIES = 2000

    /** One song's choices. [part]: which of the judged parts (0 the best, then the runners-up). */
    data class Choice(
        val off: Boolean = false,
        val picked: String? = null,
        val part: Int = 0,
        val rejected: Set<String> = emptySet(),
        /** The video the song's loop was last cut from, so the menu knows a loop is there. */
        val video: String? = null,
    ) {
        fun line(): String = listOf(
            if (off) "1" else "0", picked.orEmpty(), part.toString(), rejected.joinToString(","), video.orEmpty(),
        ).joinToString("|")

        companion object {
            /** A stored line; anything it can't read is the default. */
            fun parse(line: String?): Choice {
                if (line.isNullOrBlank()) return Choice()
                val p = line.split('|')
                return Choice(
                    off = p.getOrNull(0) == "1",
                    picked = p.getOrNull(1)?.takeIf { it.isNotBlank() },
                    part = p.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    rejected = p.getOrNull(3).orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                    video = p.getOrNull(4)?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    @Volatile private var file: File? = null
    private var props: Properties? = null

    /** Beside the saved lookups; before any lookup. */
    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, FILE)
        val p = Properties()
        if (f.exists()) runCatching { f.inputStream().use(p::load) }.onFailure { Log.w(TAG, "read failed: ${it.message}") }
        props = p
        file = f
    }

    @Synchronized
    fun of(songId: String): Choice = Choice.parse(props?.getProperty(songId))

    @Synchronized
    private fun put(songId: String, choice: Choice, announce: Boolean = true) {
        val p = props ?: return
        if (choice == Choice()) p.remove(songId) else p.setProperty(songId, choice.line())
        while (p.size > MAX_ENTRIES) p.remove(p.keys.first())
        save()
        if (announce) ForkCanvasSettings.changed()
    }

    private fun update(songId: String, announce: Boolean = true, change: (Choice) -> Choice) {
        val before = of(songId)
        val after = change(before)
        if (after != before) put(songId, after, announce)
    }

    /** "Canvas off for this song": no canvas is looked up or shown for it. */
    fun setOff(songId: String, off: Boolean) = update(songId) { it.copy(off = off) }

    /** "Pick another part": the next of the parts the judge found, round to the best again. */
    fun nextPart(songId: String) = update(songId) { it.copy(part = it.part + 1) }

    /** "Pick another video": [videoId] is cut, whatever its title or type says, and kept on a later search. */
    fun pick(songId: String, videoId: String) = update(songId) { it.copy(picked = videoId, part = 0, rejected = it.rejected - videoId) }

    /** "Not this video": the video shown is never offered again for the song; another is looked for. */
    fun reject(songId: String) = update(songId) { c ->
        val v = c.video ?: return@update c
        c.copy(rejected = c.rejected + v, picked = c.picked.takeIf { it != v }, part = 0, video = null)
    }

    /** The video a loop was cut from (no change to the choice's key: not announced). */
    fun noteVideo(songId: String, videoId: String?) = update(songId, announce = false) { it.copy(video = videoId) }

    /**
     * The loop's key suffix for [songId]: the picked video, the part and the rejected videos, so each
     * choice is its own saved answer. Empty for a song nobody chose anything for.
     */
    fun keySuffix(songId: String): String {
        val c = of(songId)
        if (c.picked == null && c.part == 0 && c.rejected.isEmpty()) return ""
        return "|pick=${c.picked.orEmpty()}|part=${c.part}|not=${c.rejected.sorted().joinToString(",").hashCode()}"
    }

    private fun save() {
        val f = file ?: return
        val p = props ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { p.store(it, null) }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { Log.w(TAG, "write failed: ${it.message}") }
    }

    /** Lets a test start empty, in [dir]. */
    @Synchronized
    internal fun resetForTest(dir: File?) {
        file = null
        props = null
        if (dir != null) init(dir)
    }

    // ---- display text, the same on both apps -------------------------------------------------

    const val OFF_TITLE = "Canvas for this song"
    const val PART_TITLE = "Pick another part"
    const val VIDEO_TITLE = "Pick another video"
    const val NOT_THIS_TITLE = "Not this video"
    const val NO_VIDEOS = "No other videos found for this song"
}
