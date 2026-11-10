package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * "Multichannel output" (fork, owner 2026-11-10; off by default): 5.1 and 7.1 sources (a Navidrome
 * 6-channel FLAC, an add-on's E-AC-3 Atmos bed) are sent to Windows with their channels, so a
 * spatialiser on the Windows side (Dolby Atmos for Headphones, Windows Sonic) or a surround speaker
 * setup gets them. Off, the engine folds every source to stereo in FFmpeg as upstream does.
 *
 * Why not Java Sound: its Windows provider is DirectSound, which opens a plain `WAVEFORMATEX`
 * (mono or stereo, no speaker mask); it can't say which of six channels is the centre. The PC
 * already plays through its own WASAPI shared-mode sink (`window_jni.cpp`, [DesktopWindowsAudio]),
 * so the multichannel line goes there as `WAVEFORMATEXTENSIBLE` with a speaker mask; Java Sound
 * stays stereo and folds ([DesktopAudioSink]).
 *
 * The layout sent is the device's own: Windows' shared-mode mix format for the chosen endpoint
 * ([ForkWindowsAudioLayout.mixFormat]), read each time a deck opens. With Dolby Atmos for
 * Headphones on that should be 7.1; with plain headphones it is stereo, and the setting folds to
 * stereo and says so rather than handing Windows six channels to fold its own way. While the
 * device takes N channels every deck decodes to N: a stereo song sits on the front left/right with
 * the rest silent, so two decks in a crossfade or Automix always have the same channels and nothing
 * between decode and the device has to cope with two layouts at once. Where they still differ
 * (the device changed between two songs), [ForkChannelAdapter] maps the deck onto the output in the
 * engine's `readSource`.
 *
 * What changes downstream with more than two channels: the equaliser, loudness, crossfade and
 * Automix sends are per channel already; Spatial audio (a stereo widener), Karaoke (centre
 * cancel on a stereo pair) and the loop mix (stereo renderer) don't run, as they never did for a
 * non-stereo stream; the backdrop/bass meters and the silent-tail check read the stereo fold of
 * the block, so padded silent channels don't read as quieter music.
 *
 * Applies from the next song: a playing deck's decoder keeps the layout it opened with.
 */
internal object ForkMultichannel {
    const val KEY = "fork_multichannel_output"

    private val persistence by lazy { DesktopPersistence() }

    val enabled: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(runCatching { persistence.boolean(KEY, false) }.getOrDefault(false))
    }

    /** The last decision, for the setting's subtitle; null until something asked. */
    val layout = MutableStateFlow<ForkOutputLayout?>(null)

    /** Windows' mix format for the chosen output as last read (channels, mask), for the pipeline panel. */
    @Volatile
    var lastMix: Pair<Int, Long>? = null
        private set

    /** The output selection a multichannel open was refused on, and why; see [refused]. */
    @Volatile
    private var refusal: Pair<String, String>? = null

    /** The settings row is greyed outside Windows: the multichannel line is the WASAPI sink's. */
    val supportedHere: Boolean get() = DesktopPlatform.isWindows

    fun setEnabled(on: Boolean) {
        runCatching { persistence.saveBoolean(KEY, on) }
        refusal = null // switching it again is the way to try a refused device once more
        enabled.value = on
    }

    /**
     * The sink could not open the multichannel line on [device]; it played on folded to stereo.
     * Later decks decode stereo for that output until the setting is switched again or another
     * output is chosen, so the next song reopens the device in stereo instead of folding forever.
     */
    fun refused(device: String, why: String) {
        refusal = device to why
        DesktopTrackLog.log("multichannel output refused, folding to stereo: $why")
        layout.value = decide(enabled.value, probe = false)
    }

    /** What a deck opened now should decode to (on an IO thread: it may ask Windows). */
    fun deckFormat(): DesktopPcmFormat {
        val decided = decide(enabled.value, probe = enabled.value)
        if (layout.value != decided) {
            layout.value = decided
            DesktopTrackLog.log("multichannel output: ${decided.reason}")
        }
        return DesktopPcmFormat(44_100, decided.channels, bytesPerSample = 4, isFloat = true, forkChannelMask = decided.mask)
    }

    /** For the settings row: reads the device's layout even while off, so the subtitle can say what it takes. */
    fun refresh() {
        layout.value = decide(enabled.value, probe = true)
    }

    /** "7.1 (Windows mixes at 8 channels)" for the pipeline panel: the device's capability, not ours. */
    fun deviceLayoutText(): String? = lastMix?.let { (channels, mask) ->
        "${ForkChannelLayout.name(channels, mask)} (Windows mix format)"
    }

    private fun decide(on: Boolean, probe: Boolean): ForkOutputLayout {
        val device = DesktopAudioDevices.selected.value
        val wasapi = DesktopPlatform.isWindows && ForkWindowsAudioLayout.available
        if (probe && wasapi) lastMix = ForkWindowsAudioLayout.mixFormat(device)
        return ForkChannelLayout.decide(
            enabled = on,
            wasapi = wasapi,
            mix = lastMix,
            refusal = refusal?.takeIf { it.first == device }?.second,
        )
    }
}

/** What the decks decode to: [channels] in the speaker order of [mask]; [active] when more than two. */
internal data class ForkOutputLayout(
    val channels: Int,
    val mask: Long,
    val reason: String,
) {
    val active: Boolean get() = channels > 2
}

/**
 * The speaker arithmetic, kept free of FFmpeg and Windows so it can be tested. Masks are
 * `WAVEFORMATEXTENSIBLE.dwChannelMask` bits, which FFmpeg's `AV_CH_*` share bit for bit; channels
 * are interleaved in bit order in both, so 5.1 is FL FR FC LFE SL SR (or BL BR) and 7.1 is
 * FL FR FC LFE BL BR SL SR.
 */
internal object ForkChannelLayout {
    const val FL = 0x1L
    const val FR = 0x2L
    const val FC = 0x4L
    const val LFE = 0x8L
    const val BL = 0x10L
    const val BR = 0x20L
    const val FLC = 0x40L
    const val FRC = 0x80L
    const val BC = 0x100L
    const val SL = 0x200L
    const val SR = 0x400L

    /** The eighteen speakers both sides define; anything above them (Atmos heights in a mask) isn't taken. */
    private const val KNOWN = 0x3FFFFL

    const val STEREO = FL or FR
    const val SURROUND_5_1 = FL or FR or FC or LFE or SL or SR // KSAUDIO_SPEAKER_5POINT1_SURROUND, Windows' 5.1
    const val SURROUND_7_1 = FL or FR or FC or LFE or BL or BR or SL or SR // KSAUDIO_SPEAKER_7POINT1_SURROUND

    /** Center and surround levels of a fold, swresample's defaults (-3 dB), so the app's fold matches FFmpeg's. */
    const val MIX_LEVEL = 0.70710677f

    private const val MAX_CHANNELS = 8

    /** [format]'s mask: its own when it fits its channel count, else the usual one for the count. */
    fun maskOf(format: DesktopPcmFormat): Long = maskOf(format.channels, format.forkChannelMask)

    fun maskOf(channels: Int, mask: Long): Long =
        if (mask != 0L && java.lang.Long.bitCount(mask) == channels) mask else defaultMask(channels)

    fun defaultMask(channels: Int): Long = when (channels) {
        1 -> FC
        2 -> STEREO
        3 -> FL or FR or FC
        4 -> FL or FR or BL or BR
        5 -> FL or FR or FC or SL or SR
        6 -> SURROUND_5_1
        7 -> FL or FR or FC or LFE or BC or SL or SR
        8 -> SURROUND_7_1
        else -> if (channels <= 0) 0L else (1L shl channels.coerceAtMost(62)) - 1
    }

    /**
     * The setting's whole decision. [mix] is Windows' mix format for the output (channels, mask);
     * [refusal] a multichannel open already refused there. Stereo unless every condition holds.
     */
    fun decide(enabled: Boolean, wasapi: Boolean, mix: Pair<Int, Long>?, refusal: String?): ForkOutputLayout {
        fun stereo(reason: String) = ForkOutputLayout(2, 0L, reason)
        val takes = mix?.let { (channels, mask) -> name(channels, mask) }
        if (!enabled) {
            return stereo(
                "Off: songs in 5.1 or 7.1 are folded to stereo" +
                    (takes?.let { ". This output takes $it" } ?: ""),
            )
        }
        if (!wasapi) return stereo("Folding to stereo: this needs the Windows (WASAPI) output, which isn't available")
        if (refusal != null) return stereo("Folding to stereo: Windows refused the multichannel stream ($refusal). Switch off and on to try again")
        val (channels, rawMask) = mix ?: return stereo("Folding to stereo: Windows didn't say what this output takes")
        if (channels <= 2) {
            return stereo(
                "Folding to stereo: this output takes ${name(channels, rawMask).lowercase()}. " +
                    "Turn on Dolby Atmos for Headphones or a 5.1/7.1 speaker setup for it in Windows",
            )
        }
        val mask = deviceMask(channels, rawMask)
            ?: return stereo("Folding to stereo: this output's $channels-channel layout isn't supported")
        return ForkOutputLayout(
            channels,
            mask,
            "Sending ${name(channels, mask)} to Windows, this output's layout. Stereo songs play on the front left and right",
        )
    }

    /** The mask to open the device with: its own when sane, else the usual one for 6 or 8; null when unusable. */
    fun deviceMask(channels: Int, mask: Long): Long? {
        if (channels !in 3..MAX_CHANNELS) return null
        val usable = mask != 0L && java.lang.Long.bitCount(mask) == channels &&
            mask and KNOWN.inv() == 0L && mask and STEREO == STEREO
        return when {
            usable -> mask
            channels == 6 -> SURROUND_5_1
            channels == 8 -> SURROUND_7_1
            else -> null
        }
    }

    /** What a deck's swresample writes for [deck] given a source of [sourceChannels]: stereo for a ≤2-channel source on a multichannel deck. */
    fun decodeFormat(deck: DesktopPcmFormat, sourceChannels: Int): DesktopPcmFormat = when {
        deck.channels <= 2 -> deck
        sourceChannels in 1..2 -> deck.copy(channels = 2, forkChannelMask = 0L)
        else -> deck.copy(forkChannelMask = maskOf(deck))
    }

    /** Whether samples of [from] need mapping before they can be mixed into [to]. */
    fun differs(from: DesktopPcmFormat, to: DesktopPcmFormat): Boolean =
        from.channels != to.channels || (from.channels > 2 && maskOf(from) != maskOf(to))

    /**
     * The mix from [fromMask]'s channels onto [toMask]'s, row-major `[to][from]`. A speaker both
     * have goes straight across at 1; one the target lacks is folded where swresample folds it by
     * default (centre and surrounds at -3 dB onto the fronts, a side onto the back of its side and
     * the other way round, LFE dropped), so a stereo fold here sounds like FFmpeg's. Stereo onto 7.1
     * is FL→FL and FR→FR only: no centre or surround is made up.
     */
    fun matrix(fromMask: Long, toMask: Long): FloatArray {
        val from = bits(fromMask)
        val to = bits(toMask)
        val m = FloatArray(to.size * from.size)
        fun add(target: Long, source: Int, gain: Float): Boolean {
            val row = to.indexOf(target)
            if (row < 0) return false
            m[row * from.size + source] += gain
            return true
        }
        fun pair(left: Long, right: Long, source: Int, gain: Float): Boolean =
            if (toMask and left != 0L && toMask and right != 0L) { add(left, source, gain); add(right, source, gain); true } else false
        from.forEachIndexed { index, speaker ->
            if (add(speaker, index, 1f)) return@forEachIndexed
            when (speaker) {
                FC -> pair(FL, FR, index, MIX_LEVEL)
                FL, FR -> add(FC, index, MIX_LEVEL)
                LFE -> Unit // swresample's default lfe_mix_level is 0
                BL -> add(SL, index, 1f) || add(FL, index, MIX_LEVEL)
                BR -> add(SR, index, 1f) || add(FR, index, MIX_LEVEL)
                SL -> add(BL, index, 1f) || add(FL, index, MIX_LEVEL)
                SR -> add(BR, index, 1f) || add(FR, index, MIX_LEVEL)
                BC -> pair(BL, BR, index, MIX_LEVEL) || pair(SL, SR, index, MIX_LEVEL) || pair(FL, FR, index, 0.5f)
                FLC -> add(FL, index, 1f) || add(FC, index, MIX_LEVEL)
                FRC -> add(FR, index, 1f) || add(FC, index, MIX_LEVEL)
                else -> Unit // heights and the rest: dropped, as no output here has them
            }
        }
        return m
    }

    /** The speakers of [mask], in interleaving (bit) order. */
    fun bits(mask: Long): List<Long> = (0 until 63).map { 1L shl it }.filter { mask and it != 0L }

    /** "Mono", "Stereo", "5.1", "7.1", else "6 ch". */
    fun name(channels: Int, mask: Long = 0L): String = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> if (maskOf(6, mask) and LFE != 0L) "5.1" else "6 ch"
        8 -> if (maskOf(8, mask) and LFE != 0L) "7.1" else "8 ch"
        else -> "$channels ch"
    }

    /**
     * The channels from the file to the device, for the pipeline panel: [source] the file's,
     * [deck] what the decoder hands over, [dsp] what the engine processes and mixes, [line] what the
     * output line takes, [device] what Windows mixes at. Equal neighbours collapse, so a stereo song
     * on a stereo output is "Stereo throughout".
     */
    fun path(source: Int?, deck: Int?, dsp: Int?, line: Int?, device: Int?): String? {
        val stages = listOfNotNull(source, deck, dsp, line).filter { it > 0 }
        if (stages.isEmpty()) return null
        val collapsed = stages.fold(mutableListOf<Int>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
        val notes = buildList {
            if (collapsed.zipWithNext().any { (a, b) -> b < a }) add("folded down")
            if (collapsed.zipWithNext().any { (a, b) -> a <= 2 && b > 2 }) add("stereo on the front left/right")
            val last = collapsed.last()
            if (device != null && device in 1 until last) add("Windows mixes it to ${name(device).lowercase()}")
        }
        val chain = if (collapsed.size == 1) "${name(collapsed[0])} throughout" else collapsed.joinToString(" → ") { name(it) }
        return if (notes.isEmpty()) chain else "$chain (${notes.joinToString("; ")})"
    }
}

/**
 * Maps interleaved blocks from one layout onto another ([ForkChannelLayout.matrix]), into its own
 * reused [output]. One per user: the decoder's stereo padding, each engine deck's guard in
 * `readSource`, the sink's fold to a stereo line, the meters' stereo view. Not thread-safe.
 */
internal class ForkChannelAdapter {
    var output = FloatArray(0)
        private set

    private var fromMask = -1L
    private var toMask = -1L
    private var matrix = FloatArray(0)
    private var fromChannels = 0
    private var toChannels = 0

    /** Maps [count] samples of [input] from [from] to [to]; returns the samples written to [output]. */
    fun adapt(input: FloatArray, count: Int, from: DesktopPcmFormat, to: DesktopPcmFormat): Int {
        val fromM = ForkChannelLayout.maskOf(from)
        val toM = ForkChannelLayout.maskOf(to)
        if (fromM != fromMask || toM != toMask) {
            fromMask = fromM
            toMask = toM
            matrix = ForkChannelLayout.matrix(fromM, toM)
            fromChannels = java.lang.Long.bitCount(fromM)
            toChannels = java.lang.Long.bitCount(toM)
        }
        val inCh = fromChannels
        val outCh = toChannels
        if (inCh <= 0 || outCh <= 0) return 0
        val frames = minOf(count, input.size) / inCh
        val total = frames * outCh
        if (output.size < total) output = FloatArray(total)
        val out = output
        val m = matrix
        for (frame in 0 until frames) {
            val inAt = frame * inCh
            val outAt = frame * outCh
            for (o in 0 until outCh) {
                var sum = 0f
                val row = o * inCh
                for (i in 0 until inCh) {
                    val gain = m[row + i]
                    if (gain != 0f) sum += gain * input[inAt + i]
                }
                out[outAt + o] = sum
            }
        }
        return total
    }

    /** Samples counted by the last [stereoView]. */
    var viewCount = 0
        private set

    /** [samples] itself when [format] is stereo or less, else its stereo fold in [output] ([viewCount] samples). */
    fun stereoView(samples: FloatArray, count: Int, format: DesktopPcmFormat): FloatArray {
        if (format.channels <= 2) {
            viewCount = count
            return samples
        }
        viewCount = adapt(samples, count, format, STEREO_FORMAT)
        return output
    }

    private companion object {
        val STEREO_FORMAT = DesktopPcmFormat(48_000, 2, 4, isFloat = true)
    }
}

/**
 * The multichannel half of the WASAPI sink (`window_jni.cpp`): opening with a speaker mask, and
 * reading the endpoint's mix format. Bound to this fork class so [DesktopWindowsAudio]'s exports
 * stay as upstream wrote them; the library is the one it loads.
 */
internal object ForkWindowsAudioLayout {
    val available: Boolean get() = DesktopWindowsAudio.forkAvailable

    fun open(device: String, format: DesktopPcmFormat): Result<Int> = runCatching {
        check(available) { "native Windows audio is unavailable" }
        val bytes = try {
            nativeOpenLayout(
                device,
                format.sampleRate,
                format.channels,
                format.bytesPerSample,
                format.isFloat,
                ForkChannelLayout.maskOf(format),
            )
        } catch (missing: UnsatisfiedLinkError) {
            error("this build's audio library has no multichannel open")
        }
        check(bytes > 0) { DesktopWindowsAudio.forkLastError().ifBlank { "WASAPI could not open ${format.channels} channels" } }
        bytes
    }

    /** (channels, mask) of Windows' shared-mode mix format for [device]; null when unknown. */
    fun mixFormat(device: String): Pair<Int, Long>? {
        if (!available) return null
        return try {
            nativeMixFormat(device).takeIf { it.size == 2 && it[0] > 0 }?.let { it[0].toInt() to it[1] }
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic private external fun nativeOpenLayout(
        device: String,
        sampleRate: Int,
        channels: Int,
        bytesPerSample: Int,
        floatingPoint: Boolean,
        channelMask: Long,
    ): Int

    @JvmStatic private external fun nativeMixFormat(device: String): LongArray
}

/**
 * Settings > Audio output, under Output device: the switch, with what it does right now as its
 * subtitle (sending 7.1, or folding to stereo and why). Greyed, never hidden, off Windows.
 */
@Composable
internal fun ForkMultichannelRow() {
    val on by ForkMultichannel.enabled.collectAsState()
    val layout by ForkMultichannel.layout.collectAsState()
    val selected by DesktopAudioDevices.selected.collectAsState()
    LaunchedEffect(on, selected) {
        if (ForkMultichannel.supportedHere) withContext(Dispatchers.IO) { runCatching { ForkMultichannel.refresh() } }
    }
    val supported = ForkMultichannel.supportedHere
    val subtitle = when {
        !supported -> DesktopStrings["fork_multichannel_windows_only", "Windows only: other systems play stereo"]
        else -> (layout?.reason ?: if (on) "On" else "Off: songs in 5.1 or 7.1 are folded to stereo") +
            when {
                !on -> ""
                layout?.active == true -> ". From the next song. Spatial audio, Karaoke and loop mixes don't run on more than two channels"
                else -> ". From the next song"
            }
    }
    ForkDisabledWhen(!supported) {
        SettingsToggle(
            DesktopStrings["fork_multichannel_output", "Multichannel output"],
            subtitle,
            on && supported,
        ) { if (supported) ForkMultichannel.setEnabled(it) }
    }
}
