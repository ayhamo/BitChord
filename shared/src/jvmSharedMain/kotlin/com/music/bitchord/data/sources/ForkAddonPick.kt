package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import java.util.Locale
import kotlin.math.abs

/**
 * Which of an addon's search rows are worth opening for a song: the shared
 * addon rulebook's `rowsToOpen` (YT Music Extension `spec/addons/protocol.md`
 * §3 and §7, `src/addons/pick.js`; the phone port's `Pick.java`), on top of
 * [TrackMatcher].
 *
 * The matcher answers "is this row the recording"; it does not answer "is this
 * row worth a `/stream` call". Those are different questions on an addon,
 * because every `/stream` call is spent from a budget of about five a minute
 * (TorBox-backed addons, see `ForkAddonLimits`) and a row that is the right
 * recording can still be the wrong file to open:
 *
 *  - **Atmos rows** are another mix of the song. Unless the listener asked for
 *    immersive audio and the device can decode it, opening one only gets a
 *    stream that is then refused as unplayable, after the call was spent.
 *  - **A preview** (Mornye's 30 s clips: a row of 35 s or less for a song over
 *    45 s) is refused before the matcher sees it, so it is never mistaken for
 *    a short edit, and is named as a preview in the log.
 *  - **Length.** The matcher accepts a row 30 s away (90 s with a shared
 *    artist, for a video's intro and outro), which is right for "is it the
 *    song" and wrong for "can it stand in for the copy being played": the
 *    listener's own tolerance decides that (default 5 s, 0–10), and a download
 *    holds rows to ±2 s, since a file kept under the song's name has to be it.
 *    Rows within 3 s go first. A length nobody stated (a missing row duration,
 *    or a song length of 0) is unknown, not 0 s, and refuses nothing: the probe
 *    and playback's own length check decide those.
 *  - **ISRC first.** When both sides carry one, an equal ISRC is the same
 *    recording whatever the titles say ("Blinding Lights - 2020 Remaster"); it
 *    still has to pass the length, since one ISRC can sit on an extended cut.
 *  - **At most 15 rows read, at most 3 opened.** A search answer past its 15th
 *    row is noise, and a fourth stream call for one song spends most of a
 *    minute's budget on a song that three good rows already failed.
 *
 * Pure, and the same for the phone (`ForkAddonPickPhone`) and the PC
 * (`DesktopAddon.matches`), so the rulebook's `pick-cases.json` checks the
 * rules themselves (`ForkRulebookTest`).
 */
object ForkAddonPick {

    /** One search row, read leniently. [durationSec] null is unknown; [isrc] is normalised by [isrcOf]. */
    data class Row(
        val id: String,
        val title: String,
        val artist: String = "",
        val album: String? = null,
        val durationSec: Double? = null,
        val explicit: Boolean? = null,
        val isrc: String? = null,
        val atmos: Boolean = false,
    )

    /**
     * @param slackSec the listener's length tolerance (0–10 s, clamped).
     * @param forDownload holds rows to [DOWNLOAD_SLACK_SEC] instead.
     * @param atmosAllowed the phone's Dolby Atmos switch, on a device that can
     *   decode it: Atmos rows then go through the same rules as the rest
     *   (protocol §3a) instead of being refused.
     */
    data class Opt(
        val slackSec: Double = DEFAULT_SLACK_SEC,
        val forDownload: Boolean = false,
        val atmosAllowed: Boolean = false,
    )

    /** A row not opened, and the rulebook's word for why: `atmos`, `preview`, `not this song`, `length`. */
    data class Refusal(val id: String, val why: String)

    /** [open] in the order to open them (at most [MAX_OPEN]); [refused] in the order the rules refused them. */
    data class Picked<T>(val open: List<T>, val refused: List<Refusal>)

    /**
     * The rows of [rows] to open for [target], best first.
     *
     * [isrc] is the song's own ISRC when its catalogue gave one; null skips the
     * ISRC rule and leaves everything to the matcher. Generic over the caller's
     * row type so the phone and the PC hand in their own `Song`s and get the
     * same objects back, while [read] says what each one is.
     */
    fun <T> rowsToOpen(
        rows: List<T>,
        target: TrackMatcher.Target,
        opt: Opt = Opt(),
        isrc: String? = null,
        read: (T) -> Row,
    ): Picked<T> {
        val refused = mutableListOf<Refusal>()
        val slack = if (opt.forDownload) DOWNLOAD_SLACK_SEC else opt.slackSec.coerceIn(0.0, MAX_SLACK_SEC)
        // 0 is how a queue row with no runtime arrives from some paths: unknown, not a 0 s song
        val song = target.durationSec?.takeIf { it > 0 }
        val read15 = rows.take(MAX_ROWS)
            .map { it to read(it) }
            .filter { (_, row) -> row.id.isNotBlank() && row.title.isNotBlank() }

        fun preview(row: Row) =
            row.durationSec != null && song != null && row.durationSec <= PREVIEW_MAX_SEC && song > PREVIEW_SONG_SEC

        val usable = read15.filter { (_, row) ->
            val why = when {
                row.atmos && !opt.atmosAllowed -> WHY_ATMOS
                preview(row) -> WHY_PREVIEW
                else -> null
            }
            if (why != null) refused += Refusal(row.id, why)
            why == null
        }

        val wanted = isrcOf(isrc)
        val byIsrc = if (wanted == null) emptyList() else usable.filter { (_, row) -> row.isrc == wanted }
        val rest = usable.filterNot { it in byIsrc }
        // The matcher works on Songs; each row gets its index as its id, so two
        // rows a catalogue lists identically still come back as themselves.
        val songs = rest.mapIndexed { i, (_, row) -> songOf(row, i.toString()) }
        val matchOn = if (song == null) target.copy(durationSec = null) else target
        val matched = TrackMatcher.ranked(songs, matchOn).map { rest[it.videoId.toInt()] }
        for (pair in usable) {
            if (pair !in byIsrc && pair !in matched) refused += Refusal(pair.second.id, WHY_NOT_THIS_SONG)
        }

        val ok = (byIsrc + matched).filter { (_, row) ->
            val far = row.durationSec != null && song != null && abs(row.durationSec - song) > slack
            if (far) refused += Refusal(row.id, WHY_LENGTH)
            !far
        }
        // Rows within 3 s first, otherwise in the order above (stable).
        val (near, other) = ok.partition { (_, row) ->
            row.durationSec != null && song != null && abs(row.durationSec - song) <= PREFER_SEC
        }
        return Picked((near + other).take(MAX_OPEN).map { it.first }, refused)
    }

    /**
     * An ISRC as 12 letters and digits, upper case, separators dropped
     * (`us-ug1-19-00001` is `USUG11900001`); null for anything that isn't one.
     */
    fun isrcOf(value: String?): String? {
        val s = value.orEmpty().uppercase(Locale.ROOT).replace(NOT_ISRC_CHAR, "")
        return s.takeIf { ISRC.matches(it) }
    }

    /**
     * The matcher's view of a row. The runtime is cut to whole seconds the way
     * an addon row's is everywhere else in the app (`AddonTrack.durationSec`),
     * so the matcher scores a row here exactly as it would in a search.
     */
    private fun songOf(row: Row, key: String) = Song(
        videoId = key,
        title = row.title,
        artist = row.artist,
        albumName = row.album?.ifBlank { null },
        thumbnailUrl = null,
        durationText = row.durationSec?.takeIf { it > 0 }?.toInt()?.let { "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}" },
        isExplicit = row.explicit,
    )

    /** A matched song as a [Row]: what the phone's and the PC's addon searches already produce. */
    fun rowOf(song: Song, atmos: Boolean): Row = Row(
        id = song.videoId,
        title = song.title,
        artist = song.artist,
        album = song.albumName,
        durationSec = TrackMatcher.secondsOf(song.durationText)?.toDouble(),
        explicit = song.isExplicit,
        atmos = atmos,
    )

    const val WHY_ATMOS = "atmos"
    const val WHY_PREVIEW = "preview"
    const val WHY_NOT_THIS_SONG = "not this song"
    const val WHY_LENGTH = "length"

    /** Rows kept for matching (protocol §3). */
    const val MAX_ROWS = 15

    /** Rows opened: each is a `/stream` call, a probe and an audition. */
    const val MAX_OPEN = 3

    /** Rows this close in length go first. */
    const val PREFER_SEC = 3.0

    const val DEFAULT_SLACK_SEC = 5.0
    const val MAX_SLACK_SEC = 10.0
    const val DOWNLOAD_SLACK_SEC = 2.0

    /** (Mornye) a clip this short of a song longer than [PREVIEW_SONG_SEC] is a preview. */
    const val PREVIEW_MAX_SEC = 35.0
    const val PREVIEW_SONG_SEC = 45

    private val NOT_ISRC_CHAR = Regex("[^A-Z0-9]")
    private val ISRC = Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}")
}
