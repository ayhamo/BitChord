package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.MAX_INCOMING_RATE
import com.music.bitchord.playback.smart.MIN_INCOMING_RATE
import com.music.bitchord.playback.smart.TransitionPlan
import kotlin.math.sqrt

/**
 * Fork: the PC decks' tempo window for a Sync or Glide the user picked (editor.md §4, ruled 2026-11-26 [all]: a picked
 * Sync or Glide beat-matches however far apart the tempos). The planner folds a tempo gap into one octave, so such a
 * plan asks for at most √2 either way (about 0.71..1.41); every other plan keeps the shared planner's 0.9..1.25
 * ([MIN_INCOMING_RATE]..[MAX_INCOMING_RATE]), Automix's own included.
 *
 * The stretcher ([DesktopAudioSpeed]) was measured clean at both ends (DesktopAudioSpeedTest): a 28 ms hop drifts
 * 11.5 ms a window at 1.41, inside its 12 ms search, and 8.1 ms at 0.71.
 */
internal object ForkPickedRate {
    /** The widest a picked Sync or Glide can ask for: a folded tempo gap of √2, plus a little for a playlist speed's rounding. */
    const val MIN = 0.70
    const val MAX = 1.42

    /** The plan's beat-matched mix with a Sync or Glide the user picked. */
    fun picked(plan: TransitionPlan?): Boolean = plan?.forkV2?.plan?.pickedTempo == true

    /** [rate] inside the window [plan] may use. */
    fun clamp(rate: Double, plan: TransitionPlan?): Double =
        if (picked(plan)) rate.coerceIn(MIN, MAX) else rate.coerceIn(MIN_INCOMING_RATE, MAX_INCOMING_RATE)

    init {
        check(MIN <= 1 / sqrt(2.0) && MAX >= sqrt(2.0))
    }
}
