package com.music.bitchord.fork

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Collections
import java.util.WeakHashMap

/**
 * The audio decoder each player last stood up (fork-only).
 *
 * The Audio Pipeline's "Decoder Name" comes from the service's format listener,
 * which only listens to the player that owns the session. In a crossfade the
 * incoming track's decoder starts on the *other* player before the handoff, so
 * the listener never hears it, and the handoff (a track change) clears the
 * name: after every crossfade the row read "—". Tracking every player from
 * the moment it is built lets the handoff publish the right name again.
 */
@OptIn(UnstableApi::class)
object PlayerDecoderNames {

    private val names: MutableMap<ExoPlayer, String> = Collections.synchronizedMap(WeakHashMap())

    fun track(player: ExoPlayer) {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                names[player] = decoderName
            }
        })
    }

    fun of(player: ExoPlayer): String? = names[player]
}
