package com.music.bitchord.playback.smart.automixv2

/**
 * Fork: the listener's pause during a mix, playback.md "The queue during a mix" rule 5 (the extension's
 * e9697cc7), on both apps. From the hand-over to the take-over what is heard is the app's (the decks, a blend's
 * second player), so a press of play/pause in any phase means "pause": the mix is called off at once and not
 * marked (rule 2), the decks fade out (the phone's leaving player over its 120 ms bail ramp; the PC's single sink
 * stops at once, as any pause there), and the player is left paused at the song and spot the mix had reached. It
 * is never started by that press.
 */
object ForkMixPause {

    /** Where a listener's pause leaves the player. */
    enum class Keep {
        /** No mix is heard (none, or one armed and not yet heard): an ordinary pause, the mix as it was. */
        AS_IS,

        /** Before the hand-over to the incoming song (the phone's handoff, the PC's midpoint): the outgoing song, at its heard spot. */
        OUTGOING,

        /** After it: the incoming song, at its heard spot. */
        INCOMING,
    }

    /** What a pause does: [heard] a mix is audible (a blend fading or bailing, a loop mix spliced in), [switched] past its hand-over. */
    fun keep(heard: Boolean, switched: Boolean): Keep = when {
        !heard -> Keep.AS_IS
        switched -> Keep.INCOMING
        else -> Keep.OUTGOING
    }

    /**
     * Whether a press is the listener's pause of a running mix: a pause always is; a play only when the player
     * shows paused ([playerPlaying] false, the phone's hold at the outgoing song's end), and it is then a pause too,
     * never released into play. A play on a player already playing changes nothing, so it leaves the mix alone.
     */
    fun pressPauses(mixRunning: Boolean, play: Boolean, playerPlaying: Boolean): Boolean =
        mixRunning && (!play || !playerPlaying)
}
