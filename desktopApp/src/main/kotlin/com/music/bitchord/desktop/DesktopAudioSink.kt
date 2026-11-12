package com.music.bitchord.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** The speakers, and the negotiation to reach them. */
internal class DesktopAudioSink {

    private var line: SourceDataLine? = null

    /** True while the Windows shared-mode sink owns the output instead of Java Sound. */
    private var windowsOutput = false

    private var windowsBufferBytes = 0

    /** The selection for which [line] was opened, including the blank system-default choice. */
    private var openedForSelection: String? = null

    /** Reused conversion buffer; only the playback thread touches it. */
    private var staging = ByteArray(0)

    /** Kept short: enough to distinguish real samples from a pipeline producing silence. */
    private var diagnosticBlocks = 0

    /** The format the mixer accepted, valid once [open] has succeeded. */
    var format: DesktopPcmFormat = DesktopPcmFormat(44_100, 2, 2)
        private set

    /**
     * Fork (ForkMultichannel): the stereo line a multichannel [format] is folded onto, when the device
     * refused the multichannel one; null when the line takes [format] as it is.
     */
    @Volatile
    private var forkFoldTo: DesktopPcmFormat? = null
    private val forkFold = ForkChannelAdapter()

    /** Fork: the channels the device line takes (the fold's two, else [format]'s), for the pipeline panel. */
    val forkLineChannels: Int get() = forkFoldTo?.channels ?: format.channels

    /** Fork (ForkMultichannel): whether the Windows line is the Spatial Sound bed, which runs at its own rate. */
    @Volatile
    var forkSpatial = false
        private set

    /** Fork: which API the samples go out through, for the pipeline panel (it always said "javax.sound"). */
    val forkOutputApi: String?
        get() = when {
            windowsOutput && forkSpatial -> "Windows Spatial Sound (7.1 bed)" // fork
            windowsOutput -> "WASAPI shared"
            line != null -> if (DesktopPlatform.isWindows) "Java Sound (DirectSound)" else "Java Sound"
            else -> null
        }

    /** Playback gain, applied to the samples rather than to a mixer control. */
    @Volatile
    var gain: Float = 1f

    val isOpen: Boolean get() = windowsOutput || line != null

    /** Whether the live line already belongs to the choice currently shown in the player. */
    fun isUsingSelection(id: String): Boolean = isOpen && openedForSelection == id

    /** Opens the best line the mixer will give for [requested]. */
    fun open(requested: DesktopPcmFormat): Result<DesktopPcmFormat> = runCatching {
        val selectedDevice = DesktopAudioDevices.selected.value
        if (DesktopPlatform.isWindows) {
            // fork (ForkMultichannel): more than two channels open on Spatial Sound or with their speaker mask (was DesktopWindowsAudio.open for all)
            val native = if (requested.channels > 2) ForkWindowsAudioLayout.openBest(selectedDevice, requested) else DesktopWindowsAudio.open(selectedDevice, requested).map { ForkOpenedLine(it, requested, spatial = false) }
            if (native.isSuccess) {
                val (forkBytes, forkOpened, forkIsSpatial) = native.getOrThrow() // fork
                val previous = line
                line = null
                forkFoldTo = null // fork
                forkSpatial = forkIsSpatial // fork
                windowsOutput = true
                windowsBufferBytes = forkBytes // fork: was native.getOrThrow()
                // Read live from the native side instead: it moves the stream itself when the
                // Windows default changes or a chosen device is unplugged.
                openedOn = null
                openedForSelection = selectedDevice
                format = forkOpened // fork: was requested; the Spatial Sound bed runs at its object rate
                diagnosticBlocks = 0
                closeLine(previous)
                DesktopTrackLog.log(
                    "audio output opened: ${deviceName ?: "Windows system default"} · " +
                        "${forkOpened.sampleRate} Hz · ${forkOpened.channels} ch · $forkOutputApi", // fork: forkOpened, forkOutputApi
                )
                return@runCatching forkOpened // fork: was requested
            }
            DesktopTrackLog.log("WASAPI output unavailable; trying Java Sound: ${native.exceptionOrNull()?.message}")
            if (requested.channels > 2) ForkMultichannel.refused(selectedDevice, native.exceptionOrNull()?.message ?: "no reason given") // fork
        }
        // fork (ForkMultichannel): a multichannel stream refused above plays on a stereo line (WASAPI, else
        // Java Sound, whose DirectSound line has no speaker mask) with the engine's channels folded in [write];
        // [format] stays the engine's, so its processors keep the layout the decks are decoded in
        if (requested.channels > 2) {
            val stereoLine = open(requested.copy(channels = 2, forkChannelMask = 0L)).getOrThrow()
            forkFoldTo = stereoLine
            format = stereoLine.copy(channels = requested.channels, forkChannelMask = requested.forkChannelMask)
            DesktopTrackLog.log("audio output: ${requested.channels} ch folded to the stereo line")
            return@runCatching format
        }

        if (windowsOutput) {
            DesktopWindowsAudio.close()
            windowsOutput = false
            windowsBufferBytes = 0
        }
        forkSpatial = false // fork
        val ladder = buildList {
            add(requested)
            if (requested.isFloat) add(requested.copy(bytesPerSample = 2, isFloat = false))
            // A device that will not take the source's own rate is rare, but a 44.1k-only or
            // 48k-only card does exist and silence is not the right answer for it.
            for (rate in intArrayOf(48_000, 44_100)) {
                if (rate != requested.sampleRate) {
                    add(requested.copy(sampleRate = rate))
                    add(requested.copy(sampleRate = rate, bytesPerSample = 2, isFloat = false))
                }
            }
            if (requested.channels != 2) add(requested.copy(channels = 2, bytesPerSample = 2, isFloat = false))
        }

        // The chosen device, when there is one and it is still plugged in; otherwise whatever the
        // system calls the default.
        val mixer = DesktopAudioDevices.mixerFor(selectedDevice)
        val supports: (DesktopPcmFormat) -> Boolean = { candidate ->
            if (mixer == null) {
                AudioSystem.isLineSupported(infoFor(candidate))
            } else {
                runCatching { mixer.isLineSupported(infoFor(candidate)) }.getOrDefault(false)
            }
        }
        val accepted = ladder.firstOrNull(supports)
            ?: error("no audio line for any supported format")

        val opened = if (mixer == null) {
            AudioSystem.getLine(infoFor(accepted)) as SourceDataLine
        } else {
            mixer.getLine(infoFor(accepted)) as SourceDataLine
        }
        try {
            // Roughly a fifth of a second in the device's hands.
            opened.open(accepted.toAudioFormat(), accepted.byteRate / 5)
            opened.start()
        } catch (failure: Throwable) {
            runCatching { opened.close() }
            throw failure
        }

        // Do not tear down the working route until its replacement is known to have opened. A
        // Bluetooth device can disappear between enumeration and this call; that must not turn a
        // failed switch into silent playback on every other device too.
        val previous = line
        line = opened
        forkFoldTo = null // fork
        openedOn = mixer?.mixerInfo?.name
        openedForSelection = selectedDevice
        format = accepted
        diagnosticBlocks = 0
        closeLine(previous)
        DesktopTrackLog.log(
            "audio output opened: ${deviceName ?: "system default"} · " +
                "${accepted.sampleRate} Hz · ${accepted.channels} ch · " +
                (if (accepted.isFloat) "32-bit float" else "${accepted.bytesPerSample * 8}-bit PCM"),
        )
        accepted
    }

    /**
     * What the samples are actually being written to.
     *
     * The mixer's own name, not `lineInfo`, which describes the line's capabilities — "interface
     * SourceDataLine supporting 36 audio formats" is not a device.
     */
    val deviceName: String?
        get() = runCatching {
            if (windowsOutput) {
                DesktopWindowsAudio.deviceName()
            } else {
                line?.let { openedOn ?: AudioSystem.getMixer(null).mixerInfo?.name?.takeIf(String::isNotBlank) }
            }
        }.getOrNull()

    /** The mixer this line was actually opened on, when it was not the system default. */
    private var openedOn: String? = null

    /** How much audio the device is holding, in bytes; 0 before the line is open. */
    val bufferBytes: Int
        get() = if (windowsOutput) windowsBufferBytes else runCatching { line?.bufferSize ?: 0 }.getOrDefault(0)

    /** Writes [count] interleaved samples, blocking until the device takes them. */
    fun write(samples: FloatArray, count: Int): Int {
        // fork (ForkMultichannel): folded to the stereo line, and the bytes it took counted back in the engine's channels
        forkFoldTo?.let { stereoLine ->
            if (samples !== forkFold.output) {
                val folded = forkFold.adapt(samples, count, format, stereoLine)
                return write(forkFold.output, folded) / stereoLine.channels * format.channels
            }
        }
        if (windowsOutput) {
            val written = DesktopWindowsAudio.write(samples, count, gain)
            diagnose(samples, count, written)
            return written
        }
        val target = line ?: return 0
        if (count <= 0) return 0
        val bytes = convert(samples, count)
        val written = target.write(bytes, 0, count * format.bytesPerSample)
        diagnose(samples, count, written)
        return written
    }

    private fun diagnose(samples: FloatArray, count: Int, written: Int) {
        if (diagnosticBlocks < DIAGNOSTIC_BLOCK_LIMIT) {
            diagnosticBlocks++
            val peak = (0 until minOf(count, samples.size)).maxOfOrNull { kotlin.math.abs(samples[it]) } ?: 0f
            if (peak > AUDIBLE_SIGNAL_FLOOR || diagnosticBlocks == DIAGNOSTIC_BLOCK_LIMIT) {
                DesktopTrackLog.log(
                    "audio output signal: peak=${"%.4f".format(java.util.Locale.ROOT, peak)} " +
                        "gain=${"%.2f".format(java.util.Locale.ROOT, gain)} wrote=$written bytes",
                )
                diagnosticBlocks = DIAGNOSTIC_BLOCK_LIMIT
            }
        }
    }

    /** Floats to whatever the device accepted, scaled by [gain]. */
    private fun convert(samples: FloatArray, count: Int): ByteArray {
        val current = format
        val volume = gain
        val bytes = count * current.bytesPerSample
        if (staging.size < bytes) staging = ByteArray(bytes)
        val out = staging

        when {
            current.isFloat -> for (index in 0 until count) {
                writeIntLe(out, index * 4, (samples[index] * volume).toRawBits())
            }
            current.bytesPerSample == 4 -> for (index in 0 until count) {
                val scaled = (samples[index] * volume).coerceIn(-1f, 1f)
                writeIntLe(out, index * 4, (scaled * Int.MAX_VALUE).toInt())
            }
            else -> for (index in 0 until count) {
                val scaled = (samples[index] * volume).coerceIn(-1f, 1f)
                val value = (scaled * 32_767f).toInt()
                val at = index * 2
                out[at] = value.toByte()
                out[at + 1] = (value shr 8).toByte()
            }
        }
        return out
    }

    private fun writeIntLe(buffer: ByteArray, at: Int, value: Int) {
        buffer[at] = value.toByte()
        buffer[at + 1] = (value shr 8).toByte()
        buffer[at + 2] = (value shr 16).toByte()
        buffer[at + 3] = (value shr 24).toByte()
    }

    /** Frames the device has actually rendered — the honest playback position. */
    fun framesPlayed(): Long =
        if (windowsOutput) DesktopWindowsAudio.framesPlayed() else line?.longFramePosition ?: 0L

    fun pause() {
        if (windowsOutput) DesktopWindowsAudio.pause() else line?.stop()
    }

    fun resume() {
        if (windowsOutput) DesktopWindowsAudio.resume() else line?.start()
    }

    /** Throws away what is queued, so a seek is heard at once. */
    fun flush() {
        if (windowsOutput) DesktopWindowsAudio.flush() else line?.flush()
    }

    /** Waits for the queue to finish, so the end of a track is not clipped. */
    fun drain() {
        if (windowsOutput) DesktopWindowsAudio.drain() else runCatching { line?.drain() }
    }

    fun close() {
        if (windowsOutput) {
            DesktopWindowsAudio.close()
            windowsOutput = false
            forkSpatial = false // fork
            windowsBufferBytes = 0
            openedOn = null
            openedForSelection = null
            diagnosticBlocks = 0
            return
        }
        val target = line ?: return
        line = null
        openedOn = null
        openedForSelection = null
        diagnosticBlocks = 0
        closeLine(target)
    }

    private fun closeLine(target: SourceDataLine?) {
        if (target == null) return
        runCatching {
            target.stop()
            target.flush()
            target.close()
        }
    }

    private fun infoFor(candidate: DesktopPcmFormat) =
        DataLine.Info(SourceDataLine::class.java, candidate.toAudioFormat())

    private companion object {
        const val DIAGNOSTIC_BLOCK_LIMIT = 50
        const val AUDIBLE_SIGNAL_FLOOR = 0.001f
    }
}

/** The same format in JavaSound's vocabulary. */
internal fun DesktopPcmFormat.toAudioFormat(): AudioFormat = AudioFormat(
    if (isFloat) AudioFormat.Encoding.PCM_FLOAT else AudioFormat.Encoding.PCM_SIGNED,
    sampleRate.toFloat(),
    bytesPerSample * 8,
    channels,
    frameBytes,
    sampleRate.toFloat(),
    false,
)
