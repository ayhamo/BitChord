package com.music.bitchord.fork

import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ForkAddonRefusals
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: the addon rulebook's 8.1.4 "a refusal doesn't end the race" (YT Music Extension
 * `spec/addons/protocol.md`, baa5e1d) on the phone. The rules are [ForkAddonRefusals]'s, shared with
 * the PC.
 *
 * Before this, a copy the window check refused ([com.music.bitchord.playback.ForkWindowCheckPhone],
 * before an upgrade's swap) was a failed audition and the song stayed on YouTube's audio for good:
 * the second look runs once per song, and the same search would only have found the same row again.
 *
 * Now the refused row is passed over for the song (`SourceResolver.streamBest` leaves it out of the
 * rows it opens, [left], the same addon's other rows and the other addons still count), and the
 * second look is asked again at once ([next]) while YouTube's audio plays: at most
 * [ForkAddonRefusals.NEXT_ROWS] more copies per song, then YouTube's audio stays. A song here is its
 * title and artist ([ForkAddonRefusals.songKey]), what the resolver's target holds.
 */
object ForkNextRowPhone {

    private const val TAG = "BitChord"

    val book = ForkAddonRefusals.Book()

    /** What the song's second look asked with, to ask it again after a refusal. */
    private class Look(
        val target: TrackMatcher.Target,
        val playing: StreamFormat?,
        val servedBy: String?,
        val pass: ForkAddonFloorPhone.Look?,
    )

    private val looks = ConcurrentHashMap<String, Look>()

    private fun keyOf(target: TrackMatcher.Target) = ForkAddonRefusals.songKey(target.title, target.artist)

    /**
     * `QualityUpgrade.lookAgain`'s pass, kept with what the look asked with; returns [pass].
     * (The pass is worked out once: `ForkAddonFloorPhone.passFor` forgets the cached copy's addon.)
     */
    fun keep(
        mediaId: String,
        target: TrackMatcher.Target,
        playing: StreamFormat?,
        servedBy: String?,
        pass: ForkAddonFloorPhone.Look?,
    ): ForkAddonFloorPhone.Look? {
        if (looks.size > 64) looks.clear()
        looks[mediaId] = Look(target, playing, servedBy, pass)
        return pass
    }

    /** Whether [target]'s song used up its next rows: `upgradeFor` then searches nothing (what plays stays). */
    fun exhausted(target: TrackMatcher.Target): Boolean = book.exhausted(keyOf(target))

    /** The last session's looks go (`QualityUpgrade.forgetLastSession`): a new session's play writes its own. */
    fun clearLooks() = looks.clear()

    /**
     * "Upgrade quality" by hand on [mediaId] (playing [playingUri]): the song's refusals and its look
     * go, as the rulebook's 8.1.7 drops the song's ticket, so its rows can be opened again. Without
     * this a song that used up its [ForkAddonRefusals.NEXT_ROWS] got no addon copy until a restart.
     */
    fun forgetSong(mediaId: String, playingUri: Uri) = forgetSong(mediaId) { SourceResolver.targetIn(playingUri) }

    internal fun forgetSong(mediaId: String, targetIn: () -> TrackMatcher.Target) {
        val target = looks.remove(mediaId)?.target ?: targetIn()
        book.forget(keyOf(target))
    }

    /** `streamBest`'s rows of [sourceId] for [target], less the ones refused for the song. */
    fun left(target: TrackMatcher.Target, sourceId: String, rows: List<Song>): List<Song> =
        book.left(keyOf(target), sourceId, rows) { SourceRegistry.parseTrackKey(it.videoId)?.second ?: it.videoId }

    /** `streamBest` opened [url] from [sourceId]'s row [trackId]. */
    fun opened(url: String, sourceId: String, trackId: String) = book.opened(url, sourceId, trackId)

    /**
     * The resolver's row answered [stream] from [source] for [target]: an addon's answer announced as a
     * file that is a playlist or manifest on the wire (its Content-Type or first bytes) is refused like
     * a copy the fit refused, and the resolver goes on to the next row (rulebook 8.1.4, 8.1.5 rule 6).
     *
     * An answer *announced* as HLS or DASH (its transport, declared to `StreamContainer`, or a
     * `.m3u8`/`.mpd` path) is not refused: 8.1.5 rule 5 plays it, through the players' HLS/DASH support
     * and never through the cache ([ForkManifestSniff] says how). Until 2026-11-26 every announced one
     * was refused, so no addon HLS/DASH stream ever played on the phone, while one told only by its
     * bytes went to the player as audio.
     *
     * An announced one is still read once before it wins ([ForkManifestCheck]: the extension's
     * refusal rules, 3dce391): encrypted, live, more than one init segment or Period, Dolby only,
     * unreadable, or a link or redirect off https or to this phone is refused like a bad fit too.
     */
    suspend fun refusesManifest(target: TrackMatcher.Target, source: com.music.bitchord.data.sources.MusicSource, stream: SourceStream): Boolean =
        refusesManifest(target, source.displayName, source is com.music.bitchord.data.sources.AddonSource, stream, ForkManifestCheck::of, ForkManifestSniff::headOf)

    internal suspend fun refusesManifest(
        target: TrackMatcher.Target,
        sourceName: String,
        isAddon: Boolean,
        stream: SourceStream,
        checkManifest: suspend (SourceStream) -> String? = { null },
        headOf: suspend (SourceStream) -> ForkManifestSniff.Head?,
    ): Boolean {
        if (!isAddon) return false // a module or catalogue may serve HLS/DASH as its normal answer
        val announced = com.music.bitchord.playback.StreamContainer.isManifest(stream.url)
        if (announced) {
            val why = checkManifest(stream) ?: return false
            val verdict = book.refuseManifest(keyOf(target), stream.url, isAddon = true, isManifest = true) ?: return false
            TrackLog.d(
                TAG,
                "$sourceName answered an HLS/DASH stream for '${target.title}' that can't play here ($why); refused (rulebook 8.1.5 rule 5), " +
                    if (verdict.next) "the next row is asked (${verdict.refusals} of ${ForkAddonRefusals.NEXT_ROWS + 1})" else "no more copies are tried",
            )
            return true
        }
        val head = headOf(stream)
        if (head?.blocked == true) {
            val blocked = book.refuse(keyOf(target), stream.url)
            TrackLog.d(
                TAG,
                "$sourceName answered a link for '${target.title}' that redirects off https or to this phone; refused (rulebook 8.1.5 rule 5), " +
                    if (blocked.next) "the next row is asked (${blocked.refusals} of ${ForkAddonRefusals.NEXT_ROWS + 1})" else "no more copies are tried",
            )
            return true
        }
        val verdict = book.refuseManifest(
            keyOf(target), stream.url,
            isAddon = true,
            isManifest = ForkManifestSniff.isUnannouncedManifest(announced, head),
        ) ?: return false
        TrackLog.d(
            TAG,
            "$sourceName answered a playlist or manifest announced as a file for '${target.title}'; refused (rulebook 8.1.5 rule 6), " +
                if (verdict.next) "the next row is asked (${verdict.refusals} of ${ForkAddonRefusals.NEXT_ROWS + 1})" else "no more copies are tried",
        )
        return true
    }

    /**
     * The window check refused the copy at [url] for the song [playingUri] plays ([why] for the log).
     */
    fun refused(mediaId: String, playingUri: String, url: String, why: String) {
        val target = looks[mediaId]?.target ?: SourceResolver.targetIn(Uri.parse(playingUri))
        val verdict = book.refuse(keyOf(target), url)
        TrackLog.d(
            TAG,
            "the copy from ${verdict.row?.let { "${SourceRegistry.config(it.source)?.displayName ?: "a source"} row ${it.row}" } ?: "a source"} " +
                "is refused for this song ($why); " +
                if (verdict.next) "the next row or source is asked (${verdict.refusals} of ${ForkAddonRefusals.NEXT_ROWS + 1})" else "no more copies are tried, YouTube's audio stays",
            about = mediaId,
        )
    }

    /**
     * After a failed swap of [refused]: when the window check refused it and copies are left to try,
     * the next row or source (the second look again, the refused rows passed over), else null.
     *
     * The "Upgrading quality" badge (protocol.md 8.6: shown "while a copy is being found or fitted for
     * the playing song") goes back up for the search: `swapIn`'s failed audition calls upstream's
     * `QualityUpgrade.forget` first, which puts it out, and the next row's search, audition and window
     * check then ran with no badge (audit 2026-11-24 2b #5 part 2, 2026-11-26 2b #6). Both callers of
     * `swapIn` put it out in their `finally`, so a retry that finds nothing, or is cancelled by a
     * skip, still ends with the badge down. `QualityUpgrade.forget` itself stays where upstream has it:
     * the failed copy's parked stream must go before the next one is parked.
     */
    suspend fun next(mediaId: String, playingUri: String, refused: SourceStream): SourceStream? {
        val look = looks[mediaId]
        val target = look?.target ?: SourceResolver.targetIn(Uri.parse(playingUri))
        val song = keyOf(target)
        if (!book.wasRefused(song, refused.url) || book.exhausted(song)) return null
        com.music.bitchord.data.NerdStats.onLosslessRaceStart(mediaId)
        val pass = look?.pass ?: ForkAddonFloorPhone.passFor(mediaId, look?.playing, look?.servedBy)
        val found = ForkAddonCopyPhone.asking(mediaId) { SourceResolver.upgradeFor(target, playing = look?.playing, servedBy = look?.servedBy, forkLook = pass) } // the progress row's "Asking your addons…" (editor.md §11 rule 6)
        if (found == null) TrackLog.d(TAG, "no other row or source has a copy that passes; YouTube's audio stays", about = mediaId)
        return found
    }
}
