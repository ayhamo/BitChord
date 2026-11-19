package com.music.bitchord.playback.smart.automixv2

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/*
 * The few Java library behaviours the automix port depends on, kept identical so the plans (and
 * their reason strings, which the golden plans compare too) come out exactly as the phone's Java
 * planner writes them.
 */

/**
 * Java's Math.round(double): half up (floor(x + 0.5) without its 0.49999999999999994 bug), NaN
 * to 0. kotlin.math.round is Math.rint (half even) and roundToLong throws on NaN, so neither
 * will do where the Java rounds.
 */
internal fun jround(x: Double): Long {
    if (x.isNaN()) return 0L
    val f = floor(x)
    return (if (x - f >= 0.5) f + 1 else f).toLong()
}

/**
 * String.format(Locale.US, format, args): Java's HALF_UP on the double's exact value, which a
 * hand-rolled formatter would miss on some ties. The plans' reasons and warnings are compared
 * as text against the extension's.
 */
internal expect fun forkV2Format(format: String, vararg args: Any?): String

/** BigDecimal.valueOf(v).toPlainString(): a double that Double.toString would print with an exponent. */
internal expect fun forkV2PlainString(v: Double): String

/** A number as JavaScript prints it in a template string (Planner.js). */
internal fun forkV2Js(v: Double): String {
    if (v.isNaN()) return "null"
    if (v == round(v) && abs(v) < 1e15) return v.toLong().toString()
    val s = v.toString()
    return if (s.contains("E")) forkV2PlainString(v) else s
}
