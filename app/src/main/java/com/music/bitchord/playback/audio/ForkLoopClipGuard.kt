package com.music.bitchord.playback.audio

import com.music.bitchord.playback.ForkMixLimiter

/**
 * Fork: the loop mix's clip guard on the phone, the extension's at 7f110ae (versions.md "The loop mix's
 * baseline" rule 1, "The mix's ceiling" rules 1 and 5): the −1 dBFS mix limiter ([ForkMixLimiter], about
 * 5 ms lookahead, 100 ms release, the PC's settings) on the stream the loop-mix renderer sums both songs
 * into, last in the chain. A clip guard only: no trim and no relative target. The baseline's gains overlap
 * at up to 1.55 (A 1 to 0.6 while B rises 0.55 to 1); measured on the extension's recording of Vaina Loca
 * into SI NO ES CONTIGO, the sum sits at -1 dBFS through the mix and would clip without it. Outside the
 * loop mix it only lets its gain recover, so a single song is never touched.
 */
internal class ForkLoopClipGuard {
    private val limiter = ForkMixLimiter()

    fun process(block: AudioBlock, mixing: Boolean, sampleRate: Int) {
        if (!mixing && limiter.idle) return
        limiter.process(block.samples, block.sampleCount, block.channelCount, sampleRate, active = if (mixing) Int.MAX_VALUE else 0)
    }

    fun reset() = limiter.reset()
}
