package com.music.bitchord.desktop

/**
 * Fork: Dolby Atmos first, from PC-Continued (e16ed1f8 / 18b6b3ae source walk, be257dad downmix
 * make-up). Everything here only acts with the existing "Dolby Atmos" setting on and an E-AC-3
 * decoder in the build; with it off the source choice is exactly as before.
 *
 * Atmos is a mix, not a quality rung: a FLAC that answers first must not shut out the immersive copy
 * another source is still preparing, and a lossless copy doesn't end the follow-up search for it.
 */
internal object ForkAtmos {

    /**
     * Raised by the engine while a mix owns the decks (the seam's lead, the mix, its settle), mirrored
     * from its own flag. Waiting for Atmos holds a stereo copy and then re-opens the deck for the mix,
     * so it is not asked for then; it is asked again once this comes down.
     */
    @Volatile var mixEngaged = false

    /**
     * Whether an immersive copy should be preferred right now. The codec probe loads FFmpeg; a build
     * or machine where it can't load answers "no" instead of failing the caller (module searches ask
     * this for every request, and a thrown NoClassDefFoundError there emptied every module's results).
     */
    fun wanted(): Boolean =
        !mixEngaged && DesktopAddonSettings.dolbyAtmosEnabled &&
            runCatching { DesktopCodecs.supportsDolbyAtmos }.getOrDefault(false)

    /** Either flag: an addon states `atmos`, a module's E-AC-3 only shows in the codec it named. */
    fun isAtmos(stream: DesktopStream): Boolean = stream.isDolbyAtmos || stream.format.isDolbyAtmos

    /**
     * How long a stereo copy is held while the other sources still look for the Atmos mix. YouTube is
     * already playing by then, so the wait costs nothing heard.
     */
    const val GRACE_MS = 2_500L
}

/**
 * Fork (from PC-Continued be257dad): a multichannel bed folded to stereo is brought back up. The
 * centre and surrounds arrive attenuated on top of the fronts, the LFE is dropped, and the immersive
 * master is mixed several dB below the stereo one, so the same song played noticeably quieter in
 * Atmos. A static gain per block, held under [CEILING] by the block's own peak: a loud passage loses
 * the make-up for that block rather than clipping, and no waveform is reshaped.
 */
internal object ForkDownmixMakeup {
    const val GAIN = 2.0f
    const val CEILING = 0.98f

    /** [GAIN] for a source of more than two channels decoded to stereo with Atmos on, else 1. */
    fun gainFor(sourceChannels: Int, outputChannels: Int, atmosEnabled: Boolean): Float =
        if (atmosEnabled && sourceChannels > 2 && outputChannels == 2) GAIN else 1f

    fun apply(samples: FloatArray, count: Int, gain: Float) {
        var peak = 0f
        for (index in 0 until count) {
            val level = kotlin.math.abs(samples[index])
            if (level > peak) peak = level
        }
        if (peak <= 0f) return
        val applied = if (peak * gain > CEILING) CEILING / peak else gain
        for (index in 0 until count) samples[index] *= applied
    }
}
