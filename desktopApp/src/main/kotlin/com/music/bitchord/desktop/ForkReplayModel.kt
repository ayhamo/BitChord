package com.music.bitchord.desktop

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fork: what the phone's Replay says, for the PC's page, stories and poster — the phone's
 * `ReplayModel.kt` (headlines, labels, cards, artwork and hue per card, formatting) over the PC's
 * [DesktopReplaySummary].
 *
 * One copy of every sentence, read by all three, for the phone's reason: a story card and the
 * picture made of it must not say different things.
 *
 * ## The wording
 *
 * The phone's English, through [DesktopStrings] under the phone's keys, trimmed (Android's resource
 * compiler drops the edge spaces some of these values carry in the XML, and the runs below put their
 * own spaces in). Four keys are read under `fork_` names because the PC's catalogue holds an older
 * copy of their English ("Your listening had a", " across ", no full stops); those use the phone's
 * current text. The PC has no plurals in its catalogue, so the counts are English one/other, as
 * the phone's English plurals are.
 */
internal enum class ForkStoryPage {
    INTRO, MINUTES, ARTISTS, SONGS, ALBUMS, GENRES, HABITS, SUMMARY;
}

/** One run of a headline, and whether it is the emphasised part (the phone's `HeadlineRun`). */
internal data class ForkHeadlineRun(val text: String, val bold: Boolean)

/** One headline card on the page (the phone's `ReplayHeroCard`), with the story it opens. */
internal data class ForkReplayCard(
    val label: String,
    val value: String,
    val detail: String?,
    val artworkUrl: String?,
    val page: ForkStoryPage,
)

internal object ForkReplayText {

    /** A catalogue string, its edge spaces dropped as Android's resource compiler drops them. */
    fun t(key: String, fallback: String): String = DesktopStrings[key, fallback].trim()

    fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)

    private fun count(value: Int, one: String, other: String): String =
        if (value == 1) "1 $one" else "${grouped(value.toLong())} $other"

    fun plays(value: Int) = count(value, "play", "plays")
    fun songs(value: Int) = count(value, "song", "songs")
    fun artists(value: Int) = count(value, "artist", "artists")

    /** The minutes card's figure, always minutes, always grouped (phone `formatMinutes`). */
    fun minutes(ms: Long): String = grouped(ms / 60_000L)

    /** "1,284 minutes" (phone `replay_minutes_unit`). */
    fun minutesUnit(ms: Long): String {
        val figure = minutes(ms)
        return if (ms / 60_000L == 1L) "$figure minute" else "$figure minutes"
    }

    /** The phone's `formatListening`: minutes up to an hour, then hours and minutes, then grouped minutes. */
    fun listening(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 60 -> DesktopStrings.format("minutes_short", minutes, fallback = "%1\$d min")
            minutes < 1_440 -> DesktopStrings.format("hours_minutes_short", minutes / 60, minutes % 60, fallback = "%1\$dh %2\$dm")
            else -> DesktopStrings.format("minutes_grouped_short", grouped(minutes), fallback = "%1\$s min")
        }
    }

    /** "3 PM", "midnight" (phone `formatHour`). */
    fun hour(hour: Int): String = when (hour) {
        0 -> t("midnight", "midnight")
        12 -> t("midday", "midday")
        in 1..11 -> DesktopStrings.format("hour_am", hour, fallback = "%1\$d AM")
        else -> DesktopStrings.format("hour_pm", hour - 12, fallback = "%1\$d PM")
    }

    /** `2026-08-14` as "14 August" (phone `formatDay`). */
    fun day(iso: String): String = runCatching {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
    }.getOrDefault(iso)

    /** The phone's `localizedLabel`: "This week", "November 2026", "2026", "All time". */
    fun label(period: DesktopReplayPeriod, today: LocalDate = LocalDate.now()): String = when (period) {
        DesktopReplayPeriod.THIS_WEEK -> ForkReplayWeek.LABEL
        DesktopReplayPeriod.THIS_MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
            .format(today)
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        DesktopReplayPeriod.THIS_YEAR -> today.year.toString()
        DesktopReplayPeriod.ALL_TIME -> t("all_time", "All time")
    }

    /** The period picker's short names (phone `localizedTab`). */
    fun tab(period: DesktopReplayPeriod): String = when (period) {
        DesktopReplayPeriod.THIS_WEEK -> t("replay_period_weekly", "Weekly")
        DesktopReplayPeriod.THIS_MONTH -> t("replay_period_monthly", "Monthly")
        DesktopReplayPeriod.THIS_YEAR -> t("replay_period_yearly", "Yearly")
        DesktopReplayPeriod.ALL_TIME -> t("replay_period_lifetime", "Lifetime")
    }

    /** "Replay 26" for a year, "Replay · November 2026" otherwise (the stories' and poster's title). */
    fun title(label: String): String =
        if (label.length == 4 && label.all(Char::isDigit)) "Replay ${label.takeLast(2)}" else "Replay · $label"

    /** The sentence at the top of [page] (phone `storyHeadline`). */
    fun headline(summary: DesktopReplaySummary, page: ForkStoryPage): List<ForkHeadlineRun> {
        val week = summary.period == DesktopReplayPeriod.THIS_WEEK
        fun runs(vararg parts: Pair<String, Boolean>) = parts.map { ForkHeadlineRun(it.first, it.second) }
        val gap = " " to false
        return when (page) {
            ForkStoryPage.INTRO -> runs(
                t("replay_intro_start", "Your") to false, gap, "Replay" to true, gap,
                t("replay_intro_end", "is ready.") to false,
            )
            ForkStoryPage.MINUTES -> runs(
                t("replay_minutes_start", "You spent") to false, gap, minutesUnit(summary.totalMs) to true, gap,
                t("replay_minutes_end", "with music.") to false,
            )
            ForkStoryPage.SONGS -> runs(
                t("replay_songs_start", "In total,") to false, gap, songs(summary.totalPlays) to true, gap,
                (if (week) t("replay_songs_end_week", "filled your week.") else t("replay_songs_end", "filled your month.")) to false,
            )
            ForkStoryPage.ARTISTS -> runs(
                (if (week) t("replay_artist_start_week", "This week,") else t("replay_artist_start", "All month,")) to false, gap,
                t("replay_artist_focus", "One artist") to true, gap,
                t("replay_artist_end", "kept coming back.") to false,
            )
            ForkStoryPage.ALBUMS -> runs(
                t("replay_album_start", "This time,") to false, gap, t("replay_album_focus", "One album") to true, gap,
                t("replay_album_end", "held your attention.") to false,
            )
            ForkStoryPage.GENRES -> runs(
                t("replay_genre_start", "Together,") to false, gap, t("replay_genre_focus", "Your genres") to true, gap,
                t("replay_genre_end", "shaped your sound.") to false,
            )
            ForkStoryPage.HABITS -> runs(
                t("replay_habits_start", "You explored") to false, gap, songs(summary.distinctSongs) to true, gap,
                t("replay_habits_middle", "from") to false, gap, artists(summary.distinctArtists) to true, "." to false,
            )
            ForkStoryPage.SUMMARY -> runs(
                t("fork_replay_summary_start", "Your") to false, gap,
                (if (week) t("replay_summary_end_week", "week") else label(summary.period)) to true,
                " in music." to false,
            )
        }
    }

    /** The line under the minutes card's collage (phone `Minutes`). */
    fun minutesLine(summary: DesktopReplaySummary): String = buildString {
        if (summary.hours >= 1) {
            append(DesktopStrings.format("replay_hours_across", grouped(summary.hours), fallback = "%1\$s hours"))
            append(", ")
        } else {
            append(t("fork_replay_across", "Across"))
            append(" ")
        }
        append(plays(summary.totalPlays))
        val peak = summary.peakHour
        if (peak != null) append(", mostly around ").append(hour(peak)).append(".") else append(".")
    }

    /** The four headline cards in the phone's order: minutes, song, artist, album (phone `cards`). */
    fun cards(summary: DesktopReplaySummary): List<ForkReplayCard> = buildList {
        add(
            ForkReplayCard(
                label = t("minutes_listened", "Minutes listened"),
                value = minutes(summary.totalMs),
                detail = "${plays(summary.totalPlays)} · ${label(summary.period)}",
                artworkUrl = summary.songs.firstOrNull()?.song?.thumbnailUrl,
                page = ForkStoryPage.MINUTES,
            ),
        )
        summary.songs.firstOrNull()?.let {
            add(ForkReplayCard(t("top_song", "Top song"), it.song.title, "${it.song.artist} · ${plays(it.plays)}", it.song.thumbnailUrl, ForkStoryPage.SONGS))
        }
        summary.artists.firstOrNull()?.let {
            add(ForkReplayCard(t("top_artist", "Top artist"), it.title, "${listening(it.ms)} · ${plays(it.plays)}", it.artworkUrl, ForkStoryPage.ARTISTS))
        }
        summary.albums.firstOrNull()?.let {
            add(
                ForkReplayCard(
                    t("top_album", "Top album"), it.title,
                    listOfNotNull(it.subtitle, listening(it.ms)).joinToString(" · "),
                    it.artworkUrl, ForkStoryPage.ALBUMS,
                ),
            )
        }
    }

    /** The cards a story run has: a category with nothing in it has none (phone `ReplayStories`). */
    fun pages(summary: DesktopReplaySummary, genres: List<DesktopRankedEntry>): List<ForkStoryPage> =
        ForkStoryPage.entries.filter { page ->
            when (page) {
                ForkStoryPage.ALBUMS -> summary.albums.isNotEmpty()
                ForkStoryPage.GENRES -> genres.isNotEmpty()
                else -> true
            }
        }

    /**
     * Which cover a card is washed in (phone `storyArtwork`): the three cards about something take
     * its artwork, the rest walk a pool of everything on the Replay.
     */
    fun artwork(summary: DesktopReplaySummary, page: ForkStoryPage): String? {
        val pinned = when (page) {
            ForkStoryPage.SONGS -> summary.songs.firstOrNull()?.song?.thumbnailUrl
            ForkStoryPage.ARTISTS -> summary.artists.firstOrNull()?.artworkUrl
            ForkStoryPage.ALBUMS -> summary.albums.firstOrNull()?.artworkUrl
            else -> null
        }?.takeIf(String::isNotBlank)
        if (pinned != null) return pinned
        val pool = (summary.songs.map { it.song.thumbnailUrl } + summary.artists.map { it.artworkUrl } + summary.albums.map { it.artworkUrl })
            .filterNotNull().filter(String::isNotBlank).distinct()
        if (pool.isEmpty()) return null
        return pool[page.ordinal % pool.size]
    }

    /** How far a card's palette is turned round the colour wheel (phone `storyHue`). */
    fun hue(page: ForkStoryPage): Float = when (page) {
        ForkStoryPage.INTRO -> 0f
        ForkStoryPage.MINUTES -> 40f
        ForkStoryPage.SONGS -> 95f
        ForkStoryPage.ARTISTS -> 145f
        ForkStoryPage.ALBUMS -> 195f
        ForkStoryPage.GENRES -> 240f
        ForkStoryPage.HABITS -> 285f
        ForkStoryPage.SUMMARY -> 325f
    }

    /** [argb] turned [degrees] round the wheel, its saturation and lightness kept. */
    fun rotated(argb: Int, degrees: Float): Int {
        if (degrees == 0f) return argb
        val (h, s, l) = ForkLyricsShare.colorToHsl(argb)
        return ForkLyricsShare.hslToColor((h + degrees) % 360f, s, l)
    }

    /** Genre rows, without artwork: a genre's lead artist's cover is not a picture of the genre. */
    fun genreRows(genres: List<DesktopRankedEntry>, limit: Int): List<DesktopReplayRow> =
        genres.take(limit).mapIndexed { index, entry ->
            DesktopReplayRow(key = entry.title, rank = index + 1, title = entry.title, subtitle = null, artworkUrl = null, ms = entry.ms, plays = entry.plays)
        }

    /** Whose Replay it is, the phone's stand-in when nobody is signed in. */
    fun holder(name: String): String = name.ifBlank { t("default_replay_holder", "BitChord listener") }
}
