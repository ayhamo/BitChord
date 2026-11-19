package com.music.bitchord.data.canvas

/**
 * Fork: the canvas rulebook's source order, saved lookups and display arithmetic, as pure functions
 * both apps call and the shared canvas cases (`spec/canvas/canvas-cases.json`) run against.
 *
 * The rulebook (`YT Music Extension/spec/canvas/canvas.md`) is what the owner's phone extension and
 * PC extension follow, with Metrolist, Prism and BitChord itself as the originals. These are its
 * §1 (source order), §2 (cache), §8 (display) and §11 (badge band), kept free of Android, Compose and
 * the network so a JVM test can run every case.
 */
object ForkCanvasRules {

    // ---- §1 Source order ------------------------------------------------------------------

    /**
     * The three rulebook sources, named as Metrolist's `AnimatedCoverSource` names them (the cases
     * use these names for a custom order, the enabled set and the ones still loading).
     */
    enum class Source(val canvas: Boolean) {
        /** The song's own music video, cut to a loop: Canvas. */
        YOUTUBE_MUSIC_VIDEO(canvas = true),

        /** Spotify's Canvas: Canvas. */
        SPOTIFY(canvas = true),

        /** Apple's motion art: an animated cover, with its own switch. */
        APPLE_MUSIC(canvas = false),
    }

    /** Metrolist's `defaultOrder` (AnimatedCoverSelection.kt:28): video, Spotify, Apple. */
    val DEFAULT_ORDER: List<Source> = listOf(Source.YOUTUBE_MUSIC_VIDEO, Source.SPOTIFY, Source.APPLE_MUSIC)

    /**
     * The order the sources are asked in (rule 1.4): [custom] first, in its order, then every
     * source it left out in the default order. Only the [enabled] ones, and of those only the ones
     * their switch allows (rule 1.3): video and Spotify are Canvas ([canvasOn]), Apple is an
     * animated cover ([coversOn]). Spotify only when [spotifyAvailable].
     */
    fun order(
        custom: List<Source> = emptyList(),
        enabled: Set<Source> = Source.entries.toSet(),
        canvasOn: Boolean = true,
        coversOn: Boolean = true,
        spotifyAvailable: Boolean = true,
    ): List<Source> = (custom.distinct() + DEFAULT_ORDER.filter { it !in custom })
        .filter { it in enabled }
        .filter { if (it.canvas) canvasOn else coversOn }
        .filter { it != Source.SPOTIFY || spotifyAvailable }

    /**
     * Metrolist's `selectAnimatedCoverUrl` (AnimatedCoverSelection.kt:119-143): the first source in
     * [order] with a clip that hasn't [failed] to play; null while one before it is still [pending]
     * (rule 1.2: nothing from a later source shows until an earlier one has answered), or when none
     * has a clip.
     */
    fun select(
        found: Map<Source, String?>,
        order: List<Source>,
        failed: Set<String> = emptySet(),
        pending: Set<Source> = emptySet(),
    ): String? {
        for (source in order) {
            if (source in pending) return null
            val url = found[source] ?: continue
            if (url !in failed) return url
        }
        return null
    }

    // ---- §2 Saved lookups -----------------------------------------------------------------

    /** How long a miss is kept (rule 2.1): under a day it is still a miss, at a day it is asked again. */
    const val MISS_MS = 24L * 60 * 60 * 1000

    /** What a saved entry says. */
    sealed interface Saved {
        /** A clip was found; [value] is what was saved with it. Kept at any age. */
        data class Clip(val value: String) : Saved

        /** Nothing was found, less than [MISS_MS] ago. */
        data object Miss : Saved
    }

    /**
     * A saved entry `"<saved at ms>|<value>"` read at [nowMs] (the phone's `CanvasCache.read`):
     * a clip whose file is still there ([exists]) at any age; a miss (empty value) under a day old;
     * null (look it up again) for everything else: an older miss, a clip whose file is gone (rule
     * 2.3), and a damaged entry (rule 2.2: unknown, never an error).
     */
    fun readSaved(entry: String?, exists: (String) -> Boolean, nowMs: Long): Saved? {
        if (entry == null) return null
        val bar = entry.indexOf('|')
        if (bar < 0) return null
        val at = entry.substring(0, bar).toLongOrNull() ?: return null
        val value = entry.substring(bar + 1)
        if (value.isNotEmpty()) return if (runCatching { exists(value) }.getOrDefault(false)) Saved.Clip(value) else null
        return if (nowMs - at < MISS_MS) Saved.Miss else null
    }

    /** The entry [readSaved] reads back: a clip's [value], or a miss when null. */
    fun saved(value: String?, nowMs: Long): String = "$nowMs|${value.orEmpty()}"

    /** Rule 2.4: a song's music-video loop is saved by the song's id. */
    fun videoKey(songId: String): String = "mv:$songId"

    /** Rule 2.4: Spotify's answer is saved by title and artist. */
    fun spotifyKey(title: String, artist: String): String = "sp:$title|$artist"

    /** Rule 2.4: Apple's by title and artist; the tall (3:4) clip asked for is another answer. */
    fun appleKey(title: String, artist: String, tall: Boolean = false): String =
        "$title|$artist" + if (tall) "|tall" else ""

    // ---- §8 Display -----------------------------------------------------------------------

    /**
     * Rule 8.1: a w x h clip is Apple's tall (3:4) cover, the only kind that stops at its own edge:
     * height over width above 1.2 and under 1.45. Everything else fills the player.
     */
    fun tallClip(width: Int, height: Int): Boolean {
        if (width <= 0) return false
        val ratio = height / width.toFloat()
        return ratio > 1.2f && ratio < 1.45f
    }

    /** [tallClip] for a width-over-height aspect (the player's `canvasAspect`); 0 or less: unknown. */
    fun tallAspect(aspect: Float): Boolean {
        if (!aspect.isFinite() || aspect <= 0f) return false
        val ratio = 1f / aspect
        return ratio > 1.2f && ratio < 1.45f
    }

    /** BitChord's `HERO_FADE_FRACTION`: the banner's lowest 0.42 fades. */
    const val HERO_FADE_FRACTION = 0.42f

    /** Rule 8.2: the tall clip's fade, the banner height x 0.42 over where the clip ends, 0..1 (0 without an end). */
    fun clipFade(heroHeight: Float, clipBottom: Float): Float =
        if (clipBottom > 0f) (heroHeight * HERO_FADE_FRACTION / clipBottom).coerceIn(0f, 1f) else 0f

    /**
     * Rule 8.3 (the phone extension's `letterboxZoom`, Metrolist's `detectLetterboxZoom`): the zoom
     * that takes black rows above and below the picture out of view, from one ARGB frame of w x h.
     * Rows under grey 24, each band at least 1/16 of the height, the picture left at least half
     * the height; zoom 1 to 1.6. A frame whose middle row is black (a fade) is no letterbox.
     */
    fun letterboxZoom(argb: IntArray, width: Int, height: Int): Float {
        if (width < 4 || height < 4 || blackRow(argb, width, height / 2)) return 1f
        var top = 0
        while (top < height / 3 && blackRow(argb, width, top)) top++
        var bottom = 0
        while (height - 1 - bottom > 2 * height / 3 && blackRow(argb, width, height - 1 - bottom)) bottom++
        val min = maxOf(1, height / 16)
        if (top < min || bottom < min) return 1f
        val active = height - top - bottom
        if (active < height / 2 || active >= height) return 1f
        return (height / active.toFloat()).coerceIn(1f, MAX_LETTERBOX_ZOOM)
    }

    const val MAX_LETTERBOX_ZOOM = 1.6f

    private fun blackRow(argb: IntArray, width: Int, y: Int): Boolean {
        val step = maxOf(1, width / 16)
        var sum = 0
        var n = 0
        var x = 0
        while (x < width) {
            val c = argb[y * width + x]
            sum += (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            n++
            x += step
        }
        return n > 0 && sum / n < 24
    }

    // ---- §11 Badge ------------------------------------------------------------------------

    /**
     * Rule 11.1: where the shimmer band's centre is at [progress] (0..1) of its sweep across a
     * label [width] wide: from -band to width + band, the band 0.6 x the width each side.
     */
    fun bandCentre(progress: Float, width: Float): Float {
        val band = width * 0.6f
        return -band + progress * (width + 2 * band)
    }
}
