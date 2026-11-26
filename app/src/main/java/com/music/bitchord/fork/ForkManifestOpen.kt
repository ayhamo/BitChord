package com.music.bitchord.fork

import android.net.Uri
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamContainer

/**
 * Fork (addon rulebook 8.1.6, extension baa5e1d; Laya 33a710d1): a manifest open never goes
 * through the audio cache.
 *
 * When an addon answers with a DASH or HLS manifest, the first, progressive open fails on the
 * XML and `PlaybackService.replayAsManifest` discards the song's cache entry, then re-prepares
 * the item with its type declared and a `manifest_reopen` marker
 * (`StreamContainer.markedForManifestReopen`). That reopened URI still carries the song's `v=`
 * (or `s=`/`t=`), so `AudioCache.playbackFactory` keyed it like the song and wrote the manifest
 * XML straight back under the song's own key, right after the discard. A later open in a new
 * process (where `StreamContainer` no longer remembers the manifest, so nothing reopens it) or
 * offline (`completeSibling` hands out complete entries) was then answered with the XML as if
 * it were the song: online that costs the addon's copy (the YouTube fallback takes over);
 * offline the song doesn't play.
 *
 * Upstream's code otherwise ([AudioCache.playbackFactory]); this is the one marked check in
 * front of it (owner via the master, 2026-11-24: a wrong-audio bug gets a small fork patch, easy
 * to drop if upstream fixes it). The manifest's segments are other URLs and are cached as before.
 */
object ForkManifestOpen {

    private val REOPEN = Regex("[?&]manifest_reopen=(dash|hls)(&|#|$)")

    /**
     * Whether the open of [uri] reads past the cache: a manifest reopen, a manifest's own URL (an
     * HLS variant playlist, which the master's parse opens by its `.m3u8` address), or a quality
     * upgrade or audition whose copy is a manifest.
     *
     * The last two were cached (audit 2026-11-24, 2B #1): an upgrade's `bitchord://…&q=hifi` URI
     * carries no `manifest_reopen` marker, so a DASH/HLS copy's manifest was written under the
     * song's `#hifi` key, by the swap's open and by `AudioCache.warmRange` ahead of the audition
     * and the analysis (which return at once for such a URI now). A later process, where nothing
     * remembers it as a manifest, could hand that XML out as the song, as in the Laya bug above.
     */
    fun skipsCache(uri: String): Boolean =
        skips(uri) { if (it.contains("q=")) QualityUpgrade.forcedStream(Uri.parse(it))?.url else null }

    /** [skipsCache] with the forced upgrade stream's URL read by [forcedUrlOf]. */
    internal fun skips(uri: String, forcedUrlOf: (String) -> String?): Boolean =
        REOPEN.containsMatchIn(uri) || StreamContainer.isManifest(uri) ||
            forcedUrlOf(uri)?.let(StreamContainer::isManifest) == true
}
