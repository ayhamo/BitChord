package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow


/**
 * Upstream's pause shrink (`ARTWORK_PAUSE_SHRINK_SCALE`, private in NowPlayingScreen.kt), copied so
 * upstream's file stays untouched. `ForkWidePlayerShrinkTest` (desktopApp) reads upstream's private
 * value and fails if the two drift apart, so a changed factor shows at the next merge, not as a conflict.
 */
const val FORK_PAUSE_SHRINK_SCALE = 0.88f

/** Fork: how the PC's landscape player places its two blocks (the PC's Settings, Appearance). */
enum class ForkWideLayout {
    /** Upstream's: the two side by side in a centred row of at most 1100 dp. */
    Classic,

    /** The cover block centred in the left half, the controls in the right ([ForkWidePlayerColumns]). */
    Halves,

    /**
     * As [Halves], with the sleeve at the size the pause shrink takes it to
     * ([FORK_PAUSE_SHRINK_SCALE]) while playing; paused, the same shrink
     * applies on top of that.
     */
    HalvesSmallCover,
}

/** Fork: the PC's chosen [ForkWideLayout], set from its saved setting at start. */
object ForkWidePlayer {
    val layout = MutableStateFlow(ForkWideLayout.Halves)
}

/**
 * Fork: whether this platform offers [ForkWideLayout] at all. True on the PC;
 * false on the phone, whose landscape player stays upstream's.
 */
internal expect val forkWidePlayer: Boolean

/** Whether the landscape player takes [ForkWidePlayerColumns] in place of upstream's row. */
@Composable
internal fun forkWideActive(): Boolean {
    if (!forkWidePlayer) return false
    val layout by ForkWidePlayer.layout.collectAsState()
    return layout != ForkWideLayout.Classic
}

/** The narrowest the controls block gets while its half has room for it. */
private val FORK_WIDE_MIN_INFO = 320.dp

/** As upstream's private `LANDSCAPE_HANDLE_STRIP`: room above for the drag handle. */
private val FORK_WIDE_HANDLE_STRIP = 24.dp

/**
 * Fork: the PC's landscape player, the same blocks as [LandscapePlayerLayout]
 * placed by the window's halves. The landscape backdrop ([ForkLandscapeBackdrop])
 * puts its clip in the right half with a seam at the middle; upstream's centred
 * row started the controls right at that seam, so the title and "Playing from…"
 * sat across it. Here the cover block (sleeve, then the lyrics / output / queue
 * row, as wide as the sleeve) is centred in the left half and the controls
 * block (or the lyrics / queue) in the right half, each with equal margins
 * either side, so the two mirror each other around the seam.
 *
 * The sleeve is square: the smaller of the left half less [gutter] each side
 * and the height left once the row under it has had its own. The controls
 * block is as wide as the sleeve, held between [FORK_WIDE_MIN_INFO] and
 * [PLAYER_MAX_WIDTH] (the main pane's own cap) and to its half less [gutter]
 * each side. The cover block is centred down the window; the right block fills
 * its height and centres the controls itself, as upstream's does. In
 * [ForkWideLayout.HalvesSmallCover] the cover block is [FORK_PAUSE_SHRINK_SCALE]
 * of that width, sleeve and row alike, and the pause shrink still applies on top.
 */
@Composable
internal fun ForkWidePlayerColumns(
    pane: PlayerPane,
    compact: Boolean,
    gutter: Dp,
    artwork: @Composable (Modifier) -> Unit,
    actions: @Composable () -> Unit,
    mainPane: @Composable (compact: Boolean) -> Unit,
    lyricsPane: @Composable () -> Unit,
    queuePane: @Composable () -> Unit,
) {
    val belowArt = if (compact) 12.dp else 24.dp
    val layoutMode by ForkWidePlayer.layout.collectAsState()
    SubcomposeLayout(
        Modifier
            .fillMaxSize()
            .padding(top = FORK_WIDE_HANDLE_STRIP, bottom = if (compact) 8.dp else 20.dp),
    ) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val g = gutter.roundToPx()
        val leftHalf = width / 2
        val rightHalf = width - leftHalf
        val coverByWidth = (leftHalf - 2 * g).coerceAtLeast(0)

        // The row's height only, to know what is left for the sleeve: it is
        // laid out by width (a BoxWithConstraints, so no intrinsics), and its
        // height doesn't change with it. Composed apart and never placed.
        val actionsHeight = subcompose("actionsProbe") { Column { actions() } }
            .firstOrNull()
            ?.measure(Constraints(maxWidth = coverByWidth))
            ?.height ?: 0
        val cover = minOf(coverByWidth, height - belowArt.roundToPx() - actionsHeight).coerceAtLeast(0)
        val info = cover
            .coerceAtLeast(FORK_WIDE_MIN_INFO.roundToPx())
            .coerceAtMost(PLAYER_MAX_WIDTH.roundToPx())
            .coerceAtMost((rightHalf - 2 * g).coerceAtLeast(0))
        // Layout 3: the sleeve block at the pause shrink's size while playing (its row as wide as
        // it), so it sits further in from the half's edges; the controls keep Layout 2's width.
        val smallCover = layoutMode == ForkWideLayout.HalvesSmallCover
        val block = if (smallCover) (cover * FORK_PAUSE_SHRINK_SCALE).toInt() else cover
        val coverDp = block.toDp()

        val left = subcompose("left") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                artwork(Modifier.size(coverDp))
                Spacer(Modifier.height(belowArt))
                actions()
            }
        }.first().measure(Constraints(minWidth = block, maxWidth = block, maxHeight = height))

        val right = subcompose("right") {
            AnimatedContent(
                targetState = pane,
                transitionSpec = {
                    // Upstream's LANDSCAPE_PANE_FADE_* (220 ms after 90, out in 140).
                    fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(140))
                },
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
                label = "forkWidePane",
            ) { shown ->
                when (shown) {
                    // No side padding: the half's margins are the gutter.
                    PlayerPane.Main -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        mainPane(compact)
                    }
                    // As upstream: these reach back across PLAYER_GUTTER themselves.
                    PlayerPane.Lyrics -> Box(Modifier.fillMaxSize().padding(horizontal = PLAYER_GUTTER)) {
                        lyricsPane()
                    }
                    PlayerPane.Queue -> Box(Modifier.fillMaxSize().padding(horizontal = PLAYER_GUTTER)) {
                        queuePane()
                    }
                }
            }
        }.first().measure(Constraints.fixed(info, height))

        layout(width, height) {
            left.place((leftHalf - block) / 2, ((height - left.height) / 2).coerceAtLeast(0))
            right.place(leftHalf + (rightHalf - info) / 2, 0)
        }
    }
}
