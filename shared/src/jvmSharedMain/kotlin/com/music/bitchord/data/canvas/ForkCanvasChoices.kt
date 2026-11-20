package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.ForkCanvasRules.Source
import java.io.File
import java.util.Properties

/**
 * Fork: what the listener says about one song's canvas, from the player's song menu (canvas.md §15,
 * Prism's `PlayerOptions`, `CanvasControls`, `CanvasSongSheets` and `ClipRepository`):
 *
 *  - a switch per source for this song ([Choice.loopOff], [Choice.spotifyOff], [Choice.coverOff];
 *    rules 1-2): off, that source is not looked up or shown for the song and the next in the order
 *    shows;
 *  - "Pick another part" ([pickPart], rules 3-4): the part itself is kept ([Choice.partMs], in the
 *    video [Choice.partVideo]), so a new lookup cuts the same part while that video still passes;
 *  - "Pick another video" ([pick], rules 5-6): that video first, as it is, and the song uses it even
 *    where another source comes first;
 *  - "Use" ([setUse], rule 7): which source a song with two shows, over the settings' order;
 *  - "Not this video" ([reject], rule 8): never that video again for the song.
 *
 * Kept in `fork-canvas-choices.properties` beside the saved lookups ([ForkCanvasStore]), one line per
 * song, and only for a song somebody chose something for (rule 9). A choice moves
 * [ForkCanvasSettings.generation] (rule 10 of §13), so the player asks again at once, and the loop's
 * key ([keySuffix]) is a new one, so each choice's loop stays saved for when it comes back.
 *
 * What the lookups found for a song (its loop's video, part and runners-up, the sources that had a
 * clip) are [Note]s, kept in memory only: the menu shows what the player has, and the lookup notes it
 * again whenever the song is looked at (a saved loop is read back from its framing file).
 */
object ForkCanvasChoices {

    private const val TAG = "ForkCanvasChoices"
    private const val FILE = "fork-canvas-choices.properties"
    private const val MAX_ENTRIES = 2000

    /** "Pick another part" offers the part playing and at most this many others (§15 rule 3). */
    const val MAX_OTHER_PARTS = 4

    /** "Pick another video" offers at most this many videos (§15 rule 5). */
    const val MAX_VIDEOS = 10

    /** One song's choices. */
    data class Choice(
        val loopOff: Boolean = false,
        val spotifyOff: Boolean = false,
        val coverOff: Boolean = false,
        /** The video the listener picked (Pick another video). */
        val picked: String? = null,
        /** The part the listener picked (Pick another part), ms of [partVideo]. */
        val partMs: Long? = null,
        val partVideo: String? = null,
        val rejected: Set<String> = emptySet(),
        /** Use: the source the song shows when it has two (null: the settings' order). */
        val use: Source? = null,
    ) {
        fun off(source: Source): Boolean = when (source) {
            Source.YOUTUBE_MUSIC_VIDEO -> loopOff
            Source.SPOTIFY -> spotifyOff
            Source.APPLE_MUSIC -> coverOff
        }

        fun line(): String = listOf(
            "2",
            (if (loopOff) "v" else "") + (if (spotifyOff) "s" else "") + (if (coverOff) "a" else ""),
            picked.orEmpty(),
            partMs?.toString().orEmpty(),
            partVideo.orEmpty(),
            rejected.joinToString(","),
            use?.let(ForkCanvasSettings::id).orEmpty(),
        ).joinToString("|")

        companion object {
            /**
             * A stored line; anything it can't read is the default. A line from before the per-source
             * switches (`<off>|<picked>|<part>|<rejected>|<video>`) keeps its "off" as all three off and
             * its picked and turned-away videos; its part (an index into a list no longer kept) is let go.
             */
            fun parse(line: String?): Choice {
                if (line.isNullOrBlank()) return Choice()
                val p = line.split('|')
                if (p[0] != "2") {
                    val off = p.getOrNull(0) == "1"
                    return Choice(
                        loopOff = off, spotifyOff = off, coverOff = off,
                        picked = p.getOrNull(1)?.takeIf { it.isNotBlank() },
                        rejected = ids(p.getOrNull(3)),
                    )
                }
                val flags = p.getOrNull(1).orEmpty()
                val part = p.getOrNull(3)?.toLongOrNull()?.takeIf { it >= 0 }
                val partVideo = p.getOrNull(4)?.takeIf { it.isNotBlank() }
                return Choice(
                    loopOff = 'v' in flags, spotifyOff = 's' in flags, coverOff = 'a' in flags,
                    picked = p.getOrNull(2)?.takeIf { it.isNotBlank() },
                    partMs = part.takeIf { partVideo != null },
                    partVideo = partVideo.takeIf { part != null },
                    rejected = ids(p.getOrNull(5)),
                    use = p.getOrNull(6)?.takeIf { it.isNotBlank() }?.let(ForkCanvasSettings::sourceOf),
                )
            }

            private fun ids(s: String?) = s.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        }
    }

    // ---- the pure rules (canvas-cases.json songMenu, pickPart, videoChoices) ------------------

    /** Which of the song menu's canvas rows show (§15 rules 1, 3, 5 and 8). */
    data class Menu(val canvasSwitch: Boolean, val pickVideo: Boolean, val pickPart: Boolean, val notThisVideo: Boolean)

    /**
     * §15: [canvasOn] the Canvas master switch, [useVideos] the music video source on, [isSong] (§5
     * rule 7), [hasLoop] a loop from the music video is known for the song, [loopOff] switched off for
     * it, [loopShows] that loop is what the song shows now. Prism's `canvasLoopSwitchShown` and
     * `canvasVideoPickShown`; the part and "Not this video" only while the loop shows.
     */
    fun menu(canvasOn: Boolean, useVideos: Boolean, isSong: Boolean, hasLoop: Boolean, loopOff: Boolean, loopShows: Boolean): Menu {
        val base = canvasOn && useVideos && isSong
        val shows = base && loopShows && !loopOff
        return Menu(
            canvasSwitch = base && (hasLoop || loopOff),
            pickVideo = base && !loopOff,
            pickPart = shows,
            notThisVideo = shows,
        )
    }

    /**
     * §15 rule 4 (Prism's `pickPart`): the loop's start and the other parts after [pickMs] is picked
     * from a loop at [startMs] with [others]. The part playing picked: nothing moves. A runner-up: the
     * old part takes its place. Any other start: the old part goes first among the others.
     */
    fun pickPart(startMs: Long, others: List<Long>, pickMs: Long): Pair<Long, List<Long>> {
        if (pickMs == startMs) return startMs to others
        val at = others.indexOf(pickMs)
        val moved = if (at >= 0) others.toMutableList().apply { set(at, startMs) } else listOf(startMs) + others
        return pickMs to moved.filter { it != pickMs }.distinct()
    }

    /**
     * §15 rule 5 (Prism's `musicVideoChoices` and `choices`): YouTube's own [pairing] first when the
     * search [found] it (a pairing the search didn't find is not added), then the search's order,
     * each once; at most [MAX_VIDEOS]; then the turned-away ones ([rejected]) left out.
     */
    fun <V> videoChoices(pairing: String?, found: List<V>, rejected: Set<String>, id: (V) -> String): List<V> =
        (found.filter { id(it) == pairing } + found.filter { id(it) != pairing })
            .distinctBy(id).take(MAX_VIDEOS).filter { id(it) !in rejected }

    /**
     * Everything the song menu shows for one song's canvas (§15), both apps: each switch and whether
     * it is checked (rules 1-2), Pick another video / part, Not this video ([menu]), and Use with its
     * choices (rule 7), and which source shows now ([showing]).
     */
    data class Rows(
        val loopSwitch: Boolean, val loopChecked: Boolean,
        val spotifySwitch: Boolean, val spotifyChecked: Boolean,
        val coverSwitch: Boolean, val coverChecked: Boolean,
        val pickVideo: Boolean, val pickPart: Boolean, val notThisVideo: Boolean,
        val use: Boolean, val useOptions: List<Source>, val showing: Source?,
    )

    /**
     * The rows for a song with [choice] and [note], [isSong], given [order]: the settings' order as
     * the player asks it (only the sources on, under their master switch on; [canvasOn]: Canvas).
     */
    fun rows(choice: Choice, note: Note, isSong: Boolean, canvasOn: Boolean, order: List<Source>): Rows {
        val songOrder = orderFor(choice, order)
        val showing = showing(songOrder, note.had)
        val hasLoop = note.video != null
        val useVideos = Source.YOUTUBE_MUSIC_VIDEO in order
        val m = menu(canvasOn, useVideos, isSong, hasLoop, choice.loopOff, showing == Source.YOUTUBE_MUSIC_VIDEO)
        fun switch(s: Source) = isSong && s in order && (s in note.had || choice.off(s))
        val spotifySwitch = switch(Source.SPOTIFY)
        val coverSwitch = switch(Source.APPLE_MUSIC)
        val bothLoops = hasLoop && Source.SPOTIFY in note.had && !choice.loopOff && !choice.spotifyOff && m.canvasSwitch && spotifySwitch
        val hasCover = (Source.SPOTIFY in note.had && !choice.spotifyOff && Source.SPOTIFY in order) ||
            (Source.APPLE_MUSIC in note.had && !choice.coverOff && Source.APPLE_MUSIC in order)
        val use = isSong && canvasOn && useVideos && hasLoop && !choice.loopOff && hasCover
        val options = listOf(Source.YOUTUBE_MUSIC_VIDEO, Source.SPOTIFY, Source.APPLE_MUSIC)
            .filter { it in order && it in note.had && !choice.off(it) }
        return Rows(
            loopSwitch = m.canvasSwitch, loopChecked = if (bothLoops) showing == Source.YOUTUBE_MUSIC_VIDEO else !choice.loopOff,
            spotifySwitch = spotifySwitch, spotifyChecked = if (bothLoops) showing == Source.SPOTIFY else !choice.spotifyOff,
            coverSwitch = coverSwitch, coverChecked = !choice.coverOff,
            pickVideo = m.pickVideo, pickPart = m.pickPart && note.startMs != null, notThisVideo = m.notThisVideo,
            use = use, useOptions = options, showing = showing,
        )
    }

    /**
     * A tap on [source]'s switch for this song (§15 rules 1-2): checked, it is switched off; else on,
     * and where both loops have a switch (Spotify's canvas and the music video's) it becomes the
     * song's choice (its Use).
     */
    fun toggle(songId: String, source: Source, rows: Rows) {
        val checked = when (source) {
            Source.YOUTUBE_MUSIC_VIDEO -> rows.loopChecked
            Source.SPOTIFY -> rows.spotifyChecked
            Source.APPLE_MUSIC -> rows.coverChecked
        }
        if (checked) return setOff(songId, source, true)
        val paired = source != Source.APPLE_MUSIC && rows.loopSwitch && rows.spotifySwitch
        update(songId) { c ->
            val on = when (source) {
                Source.YOUTUBE_MUSIC_VIDEO -> c.copy(loopOff = false)
                Source.SPOTIFY -> c.copy(spotifyOff = false)
                Source.APPLE_MUSIC -> c.copy(coverOff = false)
            }
            if (paired) on.copy(use = source) else on
        }
    }

    /** The source a song shows: the first in its [order] that has a clip for it ([had]). */
    fun showing(order: List<Source>, had: Set<Source>): Source? = order.firstOrNull { it in had }

    /**
     * The order [songId]'s sources are asked and shown in: [order] (the settings', §13) without the
     * sources switched off for the song, and its Use (or its picked video, rule 6) first.
     */
    fun orderFor(songId: String, order: List<Source>): List<Source> = orderFor(of(songId), order)

    fun orderFor(choice: Choice, order: List<Source>): List<Source> {
        val on = order.filter { !choice.off(it) }
        val first = choice.use ?: Source.YOUTUBE_MUSIC_VIDEO.takeIf { choice.picked != null }
        return if (first != null && first in on) listOf(first) + (on - first) else on
    }

    // ---- what the lookups found (memory only) ------------------------------------------------

    /**
     * What the player found for a song: the video its loop was cut from ([video]), the loop's part
     * ([startMs], null for a whole video) and the runners-up ([others]), and the sources that had a
     * clip for it ([had]).
     */
    data class Note(
        val video: String? = null,
        val startMs: Long? = null,
        val others: List<Long> = emptyList(),
        val had: Set<Source> = emptySet(),
    )

    private val notes = object : LinkedHashMap<String, Note>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Note>) = size > 100
    }

    fun noteOf(songId: String): Note = synchronized(notes) { notes[songId] } ?: Note()

    private fun changeNote(songId: String, change: (Note) -> Note) {
        synchronized(notes) { notes[songId] = change(notes[songId] ?: Note()) }
    }

    /** The loop found for [songId]: cut from [video] (null: no loop), at [startMs], with [others]. */
    fun noteLoop(songId: String, video: String?, startMs: Long?, others: List<Long>) = changeNote(songId) {
        it.copy(
            video = video, startMs = startMs.takeIf { video != null }, others = if (video != null) others.take(MAX_OTHER_PARTS) else emptyList(),
            had = if (video != null) it.had + Source.YOUTUBE_MUSIC_VIDEO else it.had - Source.YOUTUBE_MUSIC_VIDEO,
        )
    }

    /** [source] had a clip for [songId] (Spotify's canvas, an animated cover). */
    fun noteHad(songId: String, source: Source) {
        if (source in noteOf(songId).had) return
        changeNote(songId) { it.copy(had = it.had + source) }
    }

    // ---- the stored choices ------------------------------------------------------------------

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
    private fun put(songId: String, choice: Choice) {
        val p = props ?: return
        if (choice == Choice()) p.remove(songId) else p.setProperty(songId, choice.line())
        while (p.size > MAX_ENTRIES) p.remove(p.keys.first())
        save()
    }

    private fun update(songId: String, change: (Choice) -> Choice) {
        val before = of(songId)
        val after = change(before)
        if (after == before) return
        put(songId, after)
        ForkCanvasSettings.changed()
    }

    /** A source switched off (or back on) for this song (§15 rules 1-2). */
    fun setOff(songId: String, source: Source, off: Boolean) = update(songId) { c ->
        when (source) {
            Source.YOUTUBE_MUSIC_VIDEO -> c.copy(loopOff = off)
            Source.SPOTIFY -> c.copy(spotifyOff = off)
            Source.APPLE_MUSIC -> c.copy(coverOff = off)
        }.let { if (off && it.use == source) it.copy(use = null) else it }
    }

    /** Use (§15 rule 7): the source this song shows (null: the settings' order). */
    fun setUse(songId: String, source: Source?) = update(songId) { it.copy(use = source) }

    /**
     * "Pick another part" (§15 rule 4): [ms] of the loop's video becomes the listener's part. The note
     * follows at once ([pickPart]), so the menu offers the parts as they now are.
     */
    fun pickPart(songId: String, ms: Long) {
        val note = noteOf(songId)
        val video = note.video ?: return
        val start = note.startMs ?: return
        val (now, others) = pickPart(start, note.others, ms)
        changeNote(songId) { it.copy(startMs = now, others = others.take(MAX_OTHER_PARTS)) }
        update(songId) { it.copy(partMs = now, partVideo = video) }
    }

    /** What [pick] stores for [videoId]: that video, no part yet, not turned away, used first (§15 rule 6). */
    fun picking(choice: Choice, videoId: String): Choice =
        choice.copy(picked = videoId, partMs = null, partVideo = null, rejected = choice.rejected - videoId, loopOff = false, use = Source.YOUTUBE_MUSIC_VIDEO)

    /** "Pick another video", once [videoId] was found to make a loop (§15 rule 6). */
    fun pick(songId: String, videoId: String) = update(songId) { picking(it, videoId) }

    /**
     * "Not this video" (§15 rule 8): the loop's video goes on the song's turned-away list for good; a
     * pick or part of it goes too. Returns the video turned away, or null when the song has no loop.
     */
    fun reject(songId: String): String? {
        val v = noteOf(songId).video ?: return null
        update(songId) { c ->
            c.copy(
                rejected = c.rejected + v,
                picked = c.picked.takeIf { it != v },
                partMs = c.partMs.takeIf { c.partVideo != v },
                partVideo = c.partVideo.takeIf { it != v },
                use = c.use.takeIf { c.picked != v },
            )
        }
        noteLoop(songId, null, null, emptyList())
        return v
    }

    /**
     * The loop's key suffix for [songId]: the picked video, the picked part and the turned-away videos,
     * so each choice is its own saved answer (§15 rule 9). Empty for a song with none of them.
     */
    fun keySuffix(songId: String): String = keySuffixOf(of(songId))

    fun keySuffixOf(c: Choice): String {
        if (c.picked == null && c.partMs == null && c.rejected.isEmpty()) return ""
        return "|pick=${c.picked.orEmpty()}" + (c.partMs?.let { "|part=${c.partVideo}@$it" } ?: "") +
            "|not=${c.rejected.sorted().joinToString(",").hashCode()}"
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
        synchronized(notes) { notes.clear() }
        if (dir != null) init(dir)
    }

    // ---- display text, the same on both apps -------------------------------------------------

    /** The song's switch for [source]. */
    fun switchTitle(source: Source): String = when (source) {
        Source.YOUTUBE_MUSIC_VIDEO -> "Music video canvas"
        Source.SPOTIFY -> "Spotify canvas"
        Source.APPLE_MUSIC -> "Animated cover"
    }

    /** A source's name under Use. */
    fun useName(source: Source?): String = when (source) {
        null -> "As in settings"
        Source.YOUTUBE_MUSIC_VIDEO -> "Music video"
        Source.SPOTIFY -> "Spotify canvas"
        Source.APPLE_MUSIC -> "Animated cover"
    }

    const val PART_TITLE = "Pick another part"
    const val PART_PLAYING = "Playing"
    const val VIDEO_TITLE = "Pick another video"
    const val VIDEO_IN_USE = "In use"
    const val NOT_THIS_TITLE = "Not this video"
    const val USE_TITLE = "Use"
    const val NO_VIDEOS = "No videos found for this song"
    const val CANT_LOOP = "That video can't make a loop"
    const val TRYING = "Making a loop of that video"

    /** A video's picture (§15 rule 5). */
    fun thumbnail(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
}
