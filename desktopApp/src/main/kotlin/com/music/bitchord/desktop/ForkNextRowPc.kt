package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ForkAddonRefusals

/**
 * Fork: the addon rulebook's 8.1.4 "a refusal doesn't end the race" (YT Music Extension
 * `spec/addons/protocol.md`, baa5e1d) on the PC. The rules are [ForkAddonRefusals]'s, shared with the
 * phone.
 *
 * Before this, a copy the window check refused ([ForkWindowCheckPc]) left the song on what plays
 * until the engine's next follow-up search (two at most), and that search opened the same row again:
 * the same row answers first every time, so a refused row stayed the answer.
 *
 * Now the refused row is passed over for the song (`openBest` leaves it out of the rows it opens,
 * [left]; the same addon's other rows and the other sources still count) and the second look is asked
 * again at once ([next]) while what plays keeps playing: at most [ForkAddonRefusals.NEXT_ROWS] more
 * copies per song, then what plays stays. A song here is its video id.
 */
internal object ForkNextRowPc {

    val book = ForkAddonRefusals.Book()

    /** Whether [song] used up its next rows: `upgradeFor` then searches nothing (what plays stays). */
    fun exhausted(song: Song): Boolean = book.exhausted(song.videoId)

    /**
     * "Upgrade quality" by hand (the engine's forced reload): [song]'s refusals go, as the rulebook's
     * 8.1.7 drops the song's ticket, so its rows can be opened again. Without this a song that used up
     * its [ForkAddonRefusals.NEXT_ROWS] got no addon copy until a restart.
     */
    fun forget(song: Song) = book.forget(song.videoId)

    /** `openBest`'s rows of [sourceId] for [song], less the ones refused for it. */
    fun left(song: Song, sourceId: String, rows: List<Song>): List<Song> =
        book.left(song.videoId, sourceId, rows) { it.videoId }

    /** `openBest` opened [url] from [sourceId]'s row [rowId]. */
    fun opened(url: String, sourceId: String, rowId: String) = book.opened(url, sourceId, rowId)

    /**
     * `openBest`'s row of [config] answered [stream] for [song]: an addon's HLS playlist or DASH
     * manifest (its declared transport, or a `.m3u8`/`.mpd` path) is refused like a copy the fit
     * refused, and `openBest` goes on to the next row (rulebook 8.1.4 + 8.1.6, the extension's
     * `refused` on the FETCH answer). It was played through the HLS/DASH reader before.
     */
    fun refusesManifest(song: Song, config: DesktopSourceConfig, stream: DesktopStream): Boolean {
        val path = stream.url.substringBefore('?').lowercase()
        val manifest = DesktopManifestDownload.isManifest(stream) || path.endsWith(".m3u8") || path.endsWith(".mpd")
        val verdict = book.refuseManifest(song.videoId, stream.url, isAddon = config.kind == DesktopSourceKind.ADDON, isManifest = manifest) ?: return false
        DesktopTrackLog.log(
            "${DesktopMusicSources.sourceNameFor(stream)} answered a playlist or manifest for '${song.title}'; refused (rulebook 8.1.6), " +
                if (verdict.next) "the next row is asked (${verdict.refusals} of ${ForkAddonRefusals.NEXT_ROWS + 1})" else "no more copies are tried",
        )
        return true
    }

    /** The window check refused the copy [stream] for [song] ([why] for the log). */
    fun refused(song: Song, stream: DesktopStream, why: String) {
        val verdict = book.refuse(song.videoId, stream.url)
        DesktopTrackLog.log(
            "the copy of '${song.title}' from ${DesktopMusicSources.sourceNameFor(stream)} is refused ($why); " +
                if (verdict.next) "the next row or source is asked (${verdict.refusals} of ${ForkAddonRefusals.NEXT_ROWS + 1})" else "no more copies are tried; what plays stays",
        )
    }

    /**
     * After the window check said no to [refused]: when it was a refusal and copies are left to try,
     * [ask] (the second look again, the refused rows passed over), else null. Not for a song no
     * longer [current]: the window check takes seconds, and a song skipped meanwhile had every addon
     * searched again for nothing.
     */
    suspend fun next(song: Song, refused: DesktopStream, current: () -> Boolean = { true }, ask: suspend () -> DesktopStream?): DesktopStream? {
        if (!book.wasRefused(song.videoId, refused.url) || book.exhausted(song.videoId) || !current()) return null
        return ask().also { if (it == null) DesktopTrackLog.log("no other row or source has a copy of '${song.title}' that passes; what plays stays") }
    }
}
