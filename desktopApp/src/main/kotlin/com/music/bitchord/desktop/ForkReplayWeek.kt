package com.music.bitchord.desktop

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** One song's listening on one day: the phone's `DayStats`. */
internal data class ForkDayStats(val ms: Long = 0L, val plays: Int = 0) {
    operator fun plus(other: ForkDayStats) = ForkDayStats(ms + other.ms, plays + other.plays)
}

/**
 * Fork: the phone's "This week" Replay on the PC, and the per-song days it needs.
 *
 * ## Why the PC had to start keeping days per song
 *
 * A PC month ([DesktopListeningStats.Bucket]) kept each song's month total, the hour-of-day spread
 * and the whole month's time per day. That answers "which day was the biggest" but not "what did I
 * play this week": a week cuts across a month, and a song's month total cannot be split into the
 * days it was played on after the fact. The phone (`ListeningStats.record`) keeps a `days` map on
 * every track for exactly this, keyed `YYYY-MM-DD`, with the milliseconds and the plays of that day.
 * So the PC now does the same, from the same call ([record], hooked into
 * [DesktopListeningStats.record]).
 *
 * ## Stored next to the month, readable by older builds
 *
 * The days go into the same store as the rest of the month, one line per song per day
 * (`month|td|videoId|day|ms|plays`, each field Base64 like every other line). An older build reads
 * lines by their second field and has no branch for `td`, so it skips them: going back to an older
 * build loses nothing it knew, and its next write simply drops the days. A store written before
 * this has no `td` lines, and every song in it falls back the phone's way (below).
 *
 * ## The week the phone's way
 *
 * Monday of the current week up to today, in the device's time zone, as the phone's
 * `ReplayPeriod.THIS_WEEK.range`. Every month the week touches is read, a song's months are merged
 * first (its days joined), and then only the days inside the week count. A song with no days at all
 * (listening from before days were kept) counts with its whole total, which is what the phone's
 * `TrackEntry.weekRange` does for its own older months: on both, history from before the change
 * shows up in "This week" until the month it belongs to is over. The hour-of-day spread is not kept
 * per day on either side, so a week has no peak hour on the phone and none here; its busiest day
 * comes from the month's per-day totals, inside the week.
 */
internal object ForkReplayWeek {

    /** The store's row type for one song's day. */
    const val ROW = "td"

    /** Monday of [today]'s week to [today], both inclusive. */
    fun range(today: LocalDate): Pair<LocalDate, LocalDate> = today.with(DayOfWeek.MONDAY) to today

    /** Whether [month] (`YYYY-MM`) has any day of [today]'s week in it. */
    fun covers(month: String, today: LocalDate): Boolean {
        val ym = runCatching { YearMonth.parse(month) }.getOrNull() ?: return false
        val (start, end) = range(today)
        return !(ym.atEndOfMonth() < start || ym.atDay(1) > end)
    }

    /** One sample of [id] on [day] (`YYYY-MM-DD`), into [bucket]'s per-song days. */
    fun record(bucket: DesktopListeningStats.Bucket, id: String, day: String, ms: Long, countsAsPlay: Boolean) {
        val days = bucket.forkTrackDays.getOrPut(id) { LinkedHashMap() }
        days[day] = (days[day] ?: ForkDayStats()) + ForkDayStats(ms.coerceAtLeast(0L), if (countsAsPlay) 1 else 0)
    }

    /** [bucket]'s per-song days as store lines, each field through [encode]. */
    fun lines(bucket: DesktopListeningStats.Bucket, encode: (String) -> String): List<String> =
        bucket.forkTrackDays.flatMap { (id, days) ->
            days.filterValues { it.ms > 0L || it.plays > 0 }.map { (day, stats) ->
                listOf(bucket.month, ROW, id, day, stats.ms.toString(), stats.plays.toString())
                    .joinToString("|", transform = encode)
            }
        }

    /** A decoded `td` line ([fields] as [DesktopListeningStats] splits them) into [bucket]. */
    fun apply(bucket: DesktopListeningStats.Bucket, fields: List<String>) {
        val id = fields.getOrNull(2)?.takeIf(String::isNotBlank) ?: return
        val day = fields.getOrNull(3)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: return
        val ms = fields.getOrNull(4)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val plays = fields.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        bucket.forkTrackDays.getOrPut(id) { LinkedHashMap() }[day] = ForkDayStats(ms, plays)
    }

    /** A song's milliseconds and plays inside [range]; its whole total when it has no days at all. */
    fun weekOf(days: Map<String, ForkDayStats>, totalMs: Long, totalPlays: Int, range: Pair<LocalDate, LocalDate>): Pair<Long, Int> {
        if (days.isEmpty()) return totalMs to totalPlays
        var ms = 0L
        var plays = 0
        days.forEach { (key, stats) ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
            if (date >= range.first && date <= range.second) {
                ms += stats.ms
                plays += stats.plays
            }
        }
        return ms to plays
    }

    /**
     * "This week" from [buckets]: the week's songs folded into one month-shaped bucket, then charted
     * by [DesktopListeningStats.summarise] like any other period, so the artists, albums and counts
     * are worked out exactly as they are for a month.
     */
    fun summarise(buckets: List<DesktopListeningStats.Bucket>, today: LocalDate): DesktopReplaySummary {
        val range = range(today)
        val months = buckets.filter { it.month != DesktopListeningStats.LEGACY_MONTH && covers(it.month, today) }

        // A song's months merged first, as the phone's `MergedBucket.add` absorbs them.
        val merged = LinkedHashMap<String, Pair<DesktopReplayEntry, MutableMap<String, ForkDayStats>>>()
        months.forEach { bucket ->
            bucket.tracks.forEach { (id, entry) ->
                val previous = merged[id]
                val days = previous?.second ?: LinkedHashMap()
                bucket.forkTrackDays[id]?.forEach { (day, stats) -> days[day] = (days[day] ?: ForkDayStats()) + stats }
                val combined = previous?.first?.let {
                    DesktopReplayEntry(
                        song = DesktopListeningStats.mergeSong(it.song, entry.song),
                        listenedMs = it.listenedMs + entry.listenedMs,
                        plays = it.plays + entry.plays,
                    )
                } ?: entry
                merged[id] = combined to days
            }
        }

        val week = DesktopListeningStats.Bucket(YearMonth.from(today).toString())
        merged.forEach { (id, value) ->
            val (entry, days) = value
            val (ms, plays) = weekOf(days, entry.listenedMs, entry.plays, range)
            // The phone keeps a song in the week only when it has time in it.
            if (ms > 0L) week.tracks[id] = entry.copy(listenedMs = ms, plays = plays)
        }
        months.forEach { bucket ->
            bucket.days.forEach { (day, ms) ->
                val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@forEach
                if (date >= range.first && date <= range.second) week.days[day] = (week.days[day] ?: 0L) + ms
            }
        }

        return DesktopListeningStats.summarise(listOf(week), DesktopReplayPeriod.ALL_TIME, today).copy(
            period = DesktopReplayPeriod.THIS_WEEK,
            label = LABEL,
            // "Member since" stays the first month of all, as for every other period.
            since = buckets.map(DesktopListeningStats.Bucket::month)
                .filter { it != DesktopListeningStats.LEGACY_MONTH }
                .minOrNull(),
        )
    }

    /** The phone's `ReplayPeriod.THIS_WEEK.label`. */
    const val LABEL = "This week"
}
