package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork: the player's two video switches, held here so the player reads them
 * live. `ForkSettings` persists both (the same flows), so Settings, Fork
 * settings and the player menu flip one value. Each goes by where a clip
 * plays, not where it comes from:
 */
object ForkCanvasSwitch {
    /** "Backdrop videos": a clip that fills the screen, the server's backdrops and Spotify's Canvas. */
    val backdrops = MutableStateFlow(true)

    /**
     * "Animated covers": a clip in the cover square, the server's cover clips
     * and the online services' motion covers (Apple Music, some of them the
     * tall full-picture kind, Tidal, the community's), and album headers.
     */
    val covers = MutableStateFlow(true)

    /**
     * Whether the player's window can run the still cover to the edges
     * ([forkBigArtFits]), so the PC's menu can say so instead of a switch that
     * quietly does nothing in a wide window.
     */
    val bigArtFits = MutableStateFlow(true)

    /**
     * The second stage of the player's clip lookup (`rememberCanvasArtwork`): the
     * clip already shown for the song, with a Spotify Canvas added as its
     * backdrop when "Backdrop videos" asks for one ([forkWithBackdrop]), or the
     * same clip back when there is none.
     *
     * Its own stage because Spotify is the slow one. The lookup used to wait for
     * it before answering at all, so an Apple Music motion cover found in a
     * moment stayed off the screen until Spotify answered: about a second, and
     * several when Spotify's token had to be fetched again first. Now the cover
     * clip shows as soon as it is found, and the backdrop joins it when it lands.
     *
     * A hook rather than a second `PlayerHost` method, so upstream's interface
     * and every host stay as they are: each platform's clip lookup
     * (`ForkCanvas`, `ForkDesktopCanvas`) puts itself here when it first loads,
     * which the player's `cachedCanvas` call does before it gets this far.
     * Anywhere nothing is put (the TV app), the shown clip comes straight back.
     *
     * Called from the lookup's own `LaunchedEffect`, so a song change, the album
     * landing or a flipped switch cancels the Spotify search exactly as it
     * cancelled the single-stage lookup.
     */
    @Volatile
    var backdropFor: suspend (song: Song, shown: CanvasArtwork?) -> CanvasArtwork? = { _, shown -> shown }
}

/**
 * Fork: [first] (the cover clip some service had) with a Spotify Canvas as its
 * backdrop: the Canvas plays where it can fill the screen, [first] in the cover
 * square everywhere else ([CanvasArtwork.sleeveUrl]). Either missing, [first]
 * stays as it is. The one shape both platforms build ([ForkCanvasSwitch.backdropFor]).
 */
fun forkWithBackdrop(first: CanvasArtwork?, backdrop: CanvasArtwork?): CanvasArtwork? = when {
    first == null || backdrop == null -> first
    else -> backdrop.copy(source = CanvasSource.SPOTIFY, sleeveUrl = first.url)
}

/** Fork: the clip [forkCanvases] last handed the sleeve, held across compositions (see [forkSteadyClip]). */
private class ForkLastClip {
    var clip: CanvasArtwork? = null
}

/**
 * Fork: the sleeve's clip, kept as the very object it already plays when the
 * backdrop joining it changes nothing the eye can see.
 *
 * The cover clip shows first and the Spotify backdrop arrives a moment later
 * ([ForkCanvasSwitch.backdropFor]). Where the backdrop can't fill the screen
 * (the PC's wide window, a phone on its side) the sleeve goes on playing the
 * same clip, but as [forkInSleeve]'s copy of the combined answer: the same
 * [CanvasArtwork.url], with the backdrop's names and no fallback or 3:4
 * rendition. Both platforms' video players key their state on the whole
 * [CanvasArtwork] (`remember(canvas)`, `LaunchedEffect(canvas)`), so that copy
 * reloaded the clip and it started over from its first frame behind a fresh
 * fade, for a picture that had not changed. [folded] says [next] is such a
 * copy; with the same url as [last], [last] goes on playing.
 *
 * Only then: an ordinary new answer with the same url (a 3:4 rendition found
 * later, say) is taken as it comes, as upstream does.
 */
internal fun forkSteadyClip(last: CanvasArtwork?, next: CanvasArtwork?, folded: Boolean): CanvasArtwork? =
    if (folded && last != null && next != null && last.url == next.url) last else next

/**
 * Fork: the clip the player shows for [raw], and the clip behind the landscape
 * player ([ForkLandscapeBackdrop]), read once from both switches.
 *
 * A backdrop (a Navidrome `.background.mp4` or a Spotify Canvas, both 9:16) is
 * made to fill a phone. It plays only where it can fill the screen
 * ([forkFillsScreen]) and while the backdrop switch is on; otherwise the song's
 * square cover clip plays in the sleeve ([CanvasArtwork.sleeveUrl]), or, without
 * one, the still cover. A backdrop never plays cropped in the cover square.
 *
 * Whatever fills the screen needs "Backdrop videos", whatever plays in the
 * square needs "Animated covers" ([ForkCanvasSwitch]). Both read live, so
 * flipping either mid-song changes the player at once.
 *
 * In portrait a backdrop fills the screen; landscape had none (the sleeve
 * played the cover clip instead, and upstream played a Spotify Canvas cropped in
 * the sleeve). There the second clip plays on the right of the screen, where the
 * controls are, while the cover stays on the left.
 */
@Composable
internal fun forkCanvases(raw: CanvasArtwork?, phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): Pair<CanvasArtwork?, CanvasArtwork?> {
    val backdrops by ForkCanvasSwitch.backdrops.collectAsStateWithLifecycle()
    val covers by ForkCanvasSwitch.covers.collectAsStateWithLifecycle()
    val fills = forkFillsScreen(raw, phoneWidth, windowWidth, windowHeight)
    val shown = raw.forkInSleeve(backdrops && fills)?.takeIf { if (it.source.fillsPhone && fills) backdrops else covers }
    val last = remember { ForkLastClip() }
    val folded = raw != null && shown != null && raw.source.fillsPhone && shown.url == raw.sleeveUrl
    val sleeve = forkSteadyClip(last.clip, shown, folded)
    SideEffect { last.clip = sleeve } // after a commit: an abandoned composition leaves it alone
    return sleeve to raw?.takeIf { backdrops && it.source.fillsPhone }
}

private fun CanvasArtwork?.forkInSleeve(backdropShown: Boolean): CanvasArtwork? = when {
    this == null || !source.fillsPhone || backdropShown -> this
    else -> sleeveUrl?.let { copy(url = it, fallbackUrl = null, source = CanvasSource.OTHER, sleeveUrl = null, portraitUrl = null) }
}

/**
 * Fork: whether a full-screen clip gets the screen. Upstream: only where the
 * player fills a phone-width window. A backdrop also in any portrait window
 * wider than that (a big phone, a tablet held upright, the 720 dp emulator),
 * where upstream kept it cropped in the sleeve. Never in a landscape window,
 * however narrow (split screen, a small phone): the landscape player draws
 * its own backdrop beside the cover ([ForkLandscapeBackdrop]).
 */
internal fun forkFillsScreen(canvas: CanvasArtwork?, phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): Boolean =
    windowHeight >= windowWidth && (phoneWidth || canvas?.source?.fillsPhone == true)

/**
 * Fork: where "Fullscreen cover art" runs the still cover to the edges. Upstream:
 * only a phone-width window. Also any portrait window wider than that, the same
 * windows a backdrop fills ([forkFillsScreen]), so the switch doesn't quietly do
 * nothing there.
 */
internal fun forkBigArtFits(phoneWidth: Boolean, windowWidth: Dp, windowHeight: Dp): Boolean =
    phoneWidth || windowHeight >= windowWidth

/** Longer than a track change or a short stall, which also read as not playing. */
private const val FORK_ART_SHRINK_DELAY_MS = 1_000L

/**
 * Fork: whether the player's clips, the paused cover and the big art treat the music as playing.
 * `isPlaying` is false while a song buffers too (a seek, a stall, a track's start), and a ~1 s
 * stall faded the paused cover in over the clip and out again and froze the clip with it: a
 * buffering player ([isLoading]) has not been paused, so only a real pause counts.
 */
internal fun forkPlaysOn(isPlaying: Boolean, isLoading: Boolean): Boolean = isPlaying || isLoading

/**
 * Fork: "Fullscreen cover art" while playing, the small cover while paused, as a paused
 * backdrop already brings the small cover back. Only a pause that lasts counts: a track
 * change also reads as not playing for a moment, and the art shouldn't jump for that.
 *
 * Not with a clip to show ([hasClip]): the banner's clip has its own player, and shrinking to
 * the small cover tore its decoder down and built the sleeve's (then the reverse on resume),
 * each on the main thread with the clip starting over, which upstream's banner is built to
 * avoid. A paused clip just holds its frame in the banner, as with the switch off.
 */
@Composable
internal fun rememberForkArtAwake(isPlaying: Boolean, hasClip: Boolean = false): Boolean {
    var awake by remember { mutableStateOf(isPlaying) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) delay(FORK_ART_SHRINK_DELAY_MS)
        awake = isPlaying
    }
    return awake || hasClip
}
