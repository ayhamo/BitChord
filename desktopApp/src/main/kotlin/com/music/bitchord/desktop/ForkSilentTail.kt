package com.music.bitchord.desktop

/**
 * Fork (from PC-Continued 18b6b3ae / 8498b497, `framesSinceAudible` / `mustLeaveSilence`, without
 * their PlayerAudioPulse backdrop): a mix starts once the outgoing song has really gone silent.
 *
 * The planner can't tell on its own: an unanalysed song has no envelope, and the analyser's silence
 * line sits far below a fade-out's tail, so its content end often reaches the file's end with the
 * last seconds already inaudible, and Automix sounded late. The engine meters what it writes instead:
 * a second of dead air inside the file's last 15 s is the song having finished, not a breath between
 * phrases (a quiet passage earlier still has music after it).
 *
 * Audio thread only: [meter] on each block of the playing song outside a mix, [reset] wherever that
 * song starts somewhere new or pauses.
 */
internal class ForkSilentTail {

    /** Frames in a row whose blocks stayed under [SILENT_RMS]. */
    var silentFrames = 0L
        private set

    /** Measures one interleaved block, before the volume (the sink applies it), so the slider can't fake silence. */
    fun meter(samples: FloatArray, count: Int, channels: Int) {
        if (count <= 0 || channels <= 0) return
        var squares = 0.0
        for (index in 0 until minOf(count, samples.size)) {
            val sample = samples[index].toDouble()
            squares += sample * sample
        }
        val rms = kotlin.math.sqrt(squares / count)
        if (rms >= SILENT_RMS) silentFrames = 0L else silentFrames += (count / channels).toLong()
    }

    fun reset() {
        silentFrames = 0L
    }

    companion object {
        /** -50 dBFS RMS: far below any music, above a dithered or noise-shaped tail. */
        const val SILENT_RMS = 0.0031623

        /** Dead air this long is the song having stopped. */
        const val CONFIRM_MS = 1_000L

        /** Only inside the file's last moments; a quiet passage earlier still has music after it. */
        const val TAIL_MS = 15_000L

        /**
         * Whether a plan that isn't due yet should start now: it has a fade, the song is in its last
         * [TAIL_MS], and the last [CONFIRM_MS] written were silent.
         */
        fun mustLeave(
            fadeSeconds: Double,
            positionMs: Long,
            durationMs: Long,
            silentFrames: Long,
            sampleRate: Int,
        ): Boolean {
            if (fadeSeconds <= 0.0 || sampleRate <= 0) return false
            val remaining = durationMs - positionMs
            if (remaining <= 0 || remaining > TAIL_MS) return false
            return silentFrames * 1_000L >= sampleRate * CONFIRM_MS
        }
    }
}
