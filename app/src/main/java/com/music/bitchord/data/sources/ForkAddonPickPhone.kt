package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.serialization.json.JsonPrimitive

/**
 * The phone's side of [ForkAddonPick]: an addon's search rows held to the
 * shared rulebook's `rowsToOpen` before [SourceResolver] opens any of them.
 *
 * Hooked into `SourceResolver.matchAndStream` in place of the bare
 * `TrackMatcher.ranked`, for addons only. Modules and JioSaavn keep upstream's
 * matcher as it was: their searches are not rate limited per `/stream` call,
 * and the rulebook is about addons.
 *
 * What upstream's walk did with an addon's rows, and why each changes:
 *
 *  - It opened rows the matcher accepted up to 30 s away (90 s with a shared
 *    artist), unless the caller was one of the strict ones. A copy that far off
 *    is a different edit, and on a TorBox addon the call that found that out
 *    was one of five a minute. Now the listener's "Track length tolerance"
 *    (default 5 s) holds every addon row; a strict caller's own bar (±2 s for a
 *    download and the YouTube fallback, the tolerance for a mid-song swap)
 *    still wins where it has one.
 *  - Atmos rows were kept, ordered after the stereo ones, and opened when a
 *    stereo row failed, only for the answer to be refused by
 *    `ModuleSource.unplayable`. With Atmos not wanted they are now skipped
 *    before any call; with it wanted, `preferred` still puts them first.
 *  - Previews (≤ 35 s against a song over 45 s) were refused by the matcher's
 *    length rule only, and logged as "not the song".
 *
 * The ISRC rule: [target]'s ISRC is what `TrackMatcher.targetOf` found for the
 * song ([ForkAddonStream.songIsrc]: YouTube Music gives none, so it is the one
 * the lyrics lookup worked out, when it has run), and each row's is the `isrc`
 * the addon sent on it in the search, kept on the row by `AddonSource`
 * ([AddonSource.forkIsrc]). With both, an equal ISRC opens first; with either
 * missing the matcher decides, as the rulebook says it does then.
 */
internal object ForkAddonPickPhone {

    /**
     * The rows of [candidates] worth opening, best first, or null when [source]
     * is not an addon and upstream's matcher stands.
     *
     * @param strictSlackSec the strict caller's own length bar, or null for an
     *   ordinary lookup, which answers to the listener's tolerance.
     */
    fun ranked(
        source: MusicSource,
        candidates: List<Song>,
        target: TrackMatcher.Target,
        strictSlackSec: Int?,
    ): List<Song>? {
        if (source.kind != SourceKind.ADDON) return null
        val picked = ForkAddonPick.rowsToOpen(
            candidates,
            target,
            ForkAddonPick.Opt(
                slackSec = (strictSlackSec ?: AppSettings.upgradeLengthSlackSeconds.value).toDouble(),
                atmosAllowed = DeviceCodecs.atmosWanted,
            ),
        ) { ForkAddonPick.rowOf(it, atmos = it.sourceQuality == ModuleSource.DOLBY, isrc = rowIsrc(source, it)) }
        // One line per search, and only when something was held back: a row
        // refused for its length reads in the log exactly like a source that
        // never had the song, and the two have opposite fixes.
        val notable = picked.refused.filter { it.why != ForkAddonPick.WHY_NOT_THIS_SONG }
        if (notable.isNotEmpty()) {
            TrackLog.d(
                TAG,
                "${source.displayName}: not opening ${notable.joinToString { "${it.id} (${it.why})" }} " +
                    "for '${target.title}' (${target.durationSec ?: "?"}s)",
            )
        }
        return picked.open
    }

    /** The ISRC [source]'s search sent on the row [song], read leniently (a number or a stray type is none). */
    private fun rowIsrc(source: MusicSource, song: Song): String? {
        val addon = source as? AddonSource ?: return null
        val trackId = SourceRegistry.parseTrackKey(song.videoId)?.second ?: return null
        return ((addon.forkIsrc(trackId) as? JsonPrimitive)?.takeIf { it.isString })?.content
    }

    /**
     * The tier of a quality label that upstream's `ModuleSource.qualityTier`
     * reads as nothing, by the rulebook's `tierOf` spellings: `Hi Res` and
     * `HiRes` spaced or not, `16bit`/`24 bit` without the hyphen, and a bare
     * `96`. Asked only after upstream's own hint lists found nothing, so no
     * label upstream already reads changes tier; a row that said "Hi Res"
     * stopped counting as lossless only because of the space, and lost its
     * place in `preferred` to a row that spelt it `Hi-Res`.
     *
     * [text] is upper case, as `qualityTier` hands it over.
     */
    fun tierSpelling(text: String): String? = when {
        LOSSLESS_SPELLING.containsMatchIn(text) -> ModuleSource.LOSSLESS
        "96" in text -> ModuleSource.LOW
        else -> null
    }

    private val LOSSLESS_SPELLING = Regex("""HI[\s_-]?RES|(16|24)[\s-]?BIT""")

    private const val TAG = "BitChord"
}
