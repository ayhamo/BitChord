package com.music.bitchord.desktop

/**
 * Fork (from Desktop Revived 337653d8, `writePcmFully`): only the frames the device accepted advance
 * the engine's clock.
 *
 * Java Sound returns a short write when its line is stopped or flushed under it, and WASAPI when an
 * endpoint fails mid-block. The engine used to count the whole block as written, so the queued-audio
 * sums behind the position and the mixes (written less played) ran ahead by what was never queued.
 * Here a short write is retried with the rest of the block; a write that takes nothing is retried
 * [ZERO_RETRIES] times, [ZERO_WAIT_MS] apart, then the rest is dropped (Revived stops playback with an
 * error there; this keeps playing). [accepted] is told every whole frame taken, as it is taken.
 *
 * Returns the frames written.
 */
internal fun forkWritePcmFully(
    samples: FloatArray,
    count: Int,
    channels: Int,
    bytesPerSample: Int,
    write: (FloatArray, Int) -> Int,
    accepted: (Int) -> Unit,
    wait: (Long) -> Unit = { Thread.sleep(it) },
): Int {
    if (channels <= 0 || bytesPerSample <= 0 || count <= 0) return 0
    val total = minOf(count, samples.size) / channels * channels
    var offset = 0
    var zeros = 0
    while (offset < total) {
        val pending = if (offset == 0) samples else samples.copyOfRange(offset, total)
        val bytes = write(pending, total - offset)
        // Whole frames only, and never more than was offered.
        val frames = (bytes.coerceAtLeast(0) / (channels * bytesPerSample)).coerceAtMost((total - offset) / channels)
        if (frames <= 0) {
            if (++zeros > ForkPcmWrite.ZERO_RETRIES) break
            wait(ForkPcmWrite.ZERO_WAIT_MS)
            continue
        }
        zeros = 0
        offset += frames * channels
        accepted(frames)
    }
    return offset / channels
}

internal object ForkPcmWrite {
    const val ZERO_RETRIES = 3
    const val ZERO_WAIT_MS = 5L
}
