package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.AutomixStyle
import com.music.bitchord.playback.smart.ForkMixPicks

/**
 * Fork: the loop-mix refusals that depend on the Automix style, shared by the phone
 * (`CrossfadeController.loopRefusalFor`) and the PC (`ForkLoopMixRules.refusal` and the armed-mix
 * re-check in `DesktopAudioEngine.forkLoopStep`), so both apps turn the same pairs away.
 *
 * Under Automix v2 the extension is the reference: its tick (crossfade.js, the loop-mix branch of the
 * `amx.state === 'idle'` planner) refuses a prepared loop mix only when the pair has a non-loop pick
 * (`noLoop`) or when the loop's first bar is no longer ahead of the hand-over lead. Its "Blend length"
 * setting (`automixBars`, automix.js `barsSetting`) sizes the blend only: `requestedBars` is read by
 * the blend planner, never by `planLoopMix` or `loopOrBlend`. A fixed 8 or 16-bar overlap chosen in
 * Settings therefore leaves the loop mix free to win, and the loop keeps its own length.
 *
 * Under BitChord's style the fixed length keeps refusing the loop mix, as Desktop Revived d150d26f made
 * it on the PC and the fork mirrored on the phone: there the length is a promise about the whole
 * transition, and BitChord's style is meant to behave as it did.
 */
object ForkV2LoopGate {

    /**
     * Whether a fixed Automix overlap ([phraseBars] 8 or 16; 0 is Auto) keeps the pair off the loop
     * mix: only under BitChord's style.
     */
    fun fixedLengthRefuses(phraseBars: Int, style: AutomixStyle = ForkMixPicks.style): Boolean =
        phraseBars != 0 && style != AutomixStyle.V2

    /** The refusal's wording, as both apps log it and the Automix overview shows it on a greyed Loop. */
    fun fixedLengthReason(phraseBars: Int): String = "a fixed $phraseBars-bar overlap is chosen"
}
