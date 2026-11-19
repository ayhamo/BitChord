package com.music.bitchord.playback.smart.automixv2

import java.math.BigDecimal
import java.util.Locale

internal actual fun forkV2Format(format: String, vararg args: Any?): String = String.format(Locale.US, format, *args)

internal actual fun forkV2PlainString(v: Double): String = BigDecimal.valueOf(v).toPlainString()
