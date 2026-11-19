package com.music.bitchord.playback.smart.automixv2

import kotlin.math.min

/**
 * A player's clock from readings that move in steps: each change is an anchor (the reading less
 * the player's own time line when it was first seen); the line is the median anchor. The time
 * line is wall time integrated over the speed given with each reading, so a speed change (automix's
 * tempo glide) keeps the anchors good. Seen up to one tick late, the same for both players, so the
 * comparison isn't biased. A port of the phone's StepClock.java.
 */
class ForkV2StepClock {
    private val anchors = DoubleArray(8)
    private var count = 0
    private var next = 0
    private var last = Long.MIN_VALUE

    /** The time line (ms of media at the speeds seen) at lastNow. */
    private var line = 0.0
    private var lastNow = Long.MIN_VALUE
    private var lastSpeed = 1f

    private fun advance(now: Long, speed: Float) {
        if (lastNow != Long.MIN_VALUE) line += (now - lastNow) * lastSpeed.toDouble()
        lastNow = now
        lastSpeed = speed
    }

    fun see(value: Long, now: Long, speed: Float): Boolean {
        advance(now, speed)
        if (value == last) return false
        val first = last == Long.MIN_VALUE
        last = value
        if (first) return false // the first reading's change time is unknown
        anchors[next] = value - line
        next = (next + 1) % anchors.size
        count = min(count + 1, anchors.size)
        return true
    }

    /**
     * The speed changed now: the line runs at it from now on, not from the next reading (readings
     * skipped while a speed change settles would be booked at the old speed).
     */
    fun speed(now: Long, speed: Float) {
        advance(now, speed)
    }

    fun n(): Int = count

    fun at(now: Long, speed: Float): Double {
        val a = anchors.copyOf(count)
        a.sort()
        val med = if (count % 2 == 1) a[count / 2] else (a[count / 2 - 1] + a[count / 2]) / 2
        return med + line + (if (lastNow == Long.MIN_VALUE) 0.0 else (now - lastNow) * speed.toDouble())
    }

    fun reset() {
        count = 0
        next = 0
        last = Long.MIN_VALUE
        line = 0.0
        lastNow = Long.MIN_VALUE
    }
}
