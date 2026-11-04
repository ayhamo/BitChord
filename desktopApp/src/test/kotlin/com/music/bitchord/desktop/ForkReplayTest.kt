package com.music.bitchord.desktop

import com.music.bitchord.data.backup.ForkBackupFormat.DayStats
import com.music.bitchord.data.backup.ForkBackupFormat.StoredBucket
import com.music.bitchord.data.backup.ForkBackupFormat.TrackEntry
import com.music.bitchord.data.model.Song
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: the PC's "This week" (per-song days, [ForkReplayWeek]), the genre chart's pure parts
 * ([ForkArtistGenres]), the phone's sentences ([ForkReplayText]), and the backup carrying the days
 * both ways ([ForkBackup.toPc] / [ForkBackup.toPhone]). No store and no network.
 */
class ForkReplayTest {

    private fun song(id: String, artist: String = "Artist $id", album: String? = "Album $id") =
        Song(videoId = id, title = "Song $id", artist = artist, thumbnailUrl = "https://example.com/$id.jpg", albumName = album)

    /** A Wednesday: its week is Monday 2026-09-28 .. Wednesday 2026-09-30, then October. */
    private val wednesday = LocalDate.of(2026, 9, 30)

    private fun bucket(month: String, vararg tracks: Triple<Song, Long, Int>, days: Map<String, Map<String, ForkDayStats>> = emptyMap(), monthDays: Map<String, Long> = emptyMap()) =
        DesktopListeningStats.Bucket(month).apply {
            tracks.forEach { (song, ms, plays) -> this.tracks[song.videoId] = DesktopReplayEntry(song, ms, plays) }
            days.forEach { (id, perDay) -> forkTrackDays[id] = LinkedHashMap(perDay) }
            monthDays.forEach { (day, ms) -> this.days[day] = ms }
        }

    // ── The week ────────────────────────────────────────────────────────

    @Test
    fun `the week runs from Monday to today`() {
        assertEquals(LocalDate.of(2026, 9, 28) to wednesday, ForkReplayWeek.range(wednesday))
        val monday = LocalDate.of(2026, 9, 28)
        assertEquals(monday to monday, ForkReplayWeek.range(monday))
    }

    @Test
    fun `a week across two months reads both`() {
        val thursday = LocalDate.of(2026, 10, 1) // week: Mon 28 Sep .. Thu 1 Oct
        assertTrue(ForkReplayWeek.covers("2026-09", thursday))
        assertTrue(ForkReplayWeek.covers("2026-10", thursday))
        assertFalse(ForkReplayWeek.covers("2026-08", thursday))
        assertFalse(ForkReplayWeek.covers(DesktopListeningStats.LEGACY_MONTH, thursday))
        assertTrue(DesktopReplayPeriod.THIS_WEEK.covers("2026-09", thursday))
    }

    @Test
    fun `only the days inside the week count`() {
        val history = listOf(
            bucket(
                "2026-09",
                Triple(song("a"), 900_000L, 3),
                Triple(song("b"), 300_000L, 1),
                days = mapOf(
                    "a" to mapOf("2026-09-10" to ForkDayStats(600_000L, 2), "2026-09-29" to ForkDayStats(300_000L, 1)),
                    "b" to mapOf("2026-09-15" to ForkDayStats(300_000L, 1)),
                ),
                monthDays = mapOf("2026-09-10" to 600_000L, "2026-09-15" to 300_000L, "2026-09-29" to 300_000L),
            ),
            bucket("2026-03", Triple(song("c"), 1_200_000L, 5), days = mapOf("c" to mapOf("2026-03-02" to ForkDayStats(1_200_000L, 5)))),
        )
        val week = DesktopListeningStats.summarise(history, DesktopReplayPeriod.THIS_WEEK, wednesday)
        assertEquals(DesktopReplayPeriod.THIS_WEEK, week.period)
        assertEquals("This week", week.label)
        assertEquals(listOf("a"), week.songs.map { it.song.videoId })
        assertEquals(300_000L, week.totalMs)
        assertEquals(1, week.totalPlays)
        assertEquals("2026-09-29", week.busiestDay)
        // No hour spread per day on either side: a week has no peak hour, as on the phone.
        assertNull(week.peakHour)
        // "Member since" is still the first month of all.
        assertEquals("2026-03", week.since)
    }

    @Test
    fun `a song's months are merged before the week is cut, as the phone does`() {
        val thursday = LocalDate.of(2026, 10, 1)
        val history = listOf(
            bucket("2026-09", Triple(song("a"), 200_000L, 1), days = mapOf("a" to mapOf("2026-09-29" to ForkDayStats(200_000L, 1)))),
            bucket("2026-10", Triple(song("a"), 100_000L, 0), days = mapOf("a" to mapOf("2026-10-01" to ForkDayStats(100_000L, 0)))),
        )
        val week = DesktopListeningStats.summarise(history, DesktopReplayPeriod.THIS_WEEK, thursday)
        assertEquals(1, week.songs.size)
        assertEquals(300_000L, week.songs.single().listenedMs)
        assertEquals(1, week.songs.single().plays)
    }

    @Test
    fun `a song from before days were kept counts whole, the phone's fallback`() {
        val history = listOf(bucket("2026-09", Triple(song("old"), 500_000L, 2)))
        val week = DesktopListeningStats.summarise(history, DesktopReplayPeriod.THIS_WEEK, wednesday)
        assertEquals(500_000L, week.totalMs)
        assertEquals(2, week.totalPlays)
    }

    @Test
    fun `a week with nothing in it is empty`() {
        val history = listOf(
            bucket("2026-09", Triple(song("a"), 600_000L, 2), days = mapOf("a" to mapOf("2026-09-10" to ForkDayStats(600_000L, 2)))),
        )
        assertTrue(DesktopListeningStats.summarise(history, DesktopReplayPeriod.THIS_WEEK, wednesday).isEmpty)
    }

    @Test
    fun `recording adds to the song's day, and a play only when it counts`() {
        val bucket = DesktopListeningStats.Bucket("2026-09")
        ForkReplayWeek.record(bucket, "a", "2026-09-30", 5_000L, countsAsPlay = false)
        ForkReplayWeek.record(bucket, "a", "2026-09-30", 5_000L, countsAsPlay = true)
        ForkReplayWeek.record(bucket, "a", "2026-09-29", 1_000L, countsAsPlay = false)
        assertEquals(ForkDayStats(10_000L, 1), bucket.forkTrackDays["a"]!!["2026-09-30"])
        assertEquals(ForkDayStats(1_000L, 0), bucket.forkTrackDays["a"]!!["2026-09-29"])
    }

    @Test
    fun `store lines read back as they were written, and bad ones are dropped`() {
        val written = DesktopListeningStats.Bucket("2026-09").apply {
            forkTrackDays["a"] = linkedMapOf("2026-09-30" to ForkDayStats(10_000L, 1), "2026-09-29" to ForkDayStats(0L, 0))
        }
        val lines = ForkReplayWeek.lines(written) { it }
        // The empty day is not written.
        assertEquals(listOf("2026-09|td|a|2026-09-30|10000|1"), lines)
        val read = DesktopListeningStats.Bucket("2026-09")
        lines.forEach { ForkReplayWeek.apply(read, it.split('|')) }
        ForkReplayWeek.apply(read, listOf("2026-09", "td", "b", "not-a-day", "5", "1"))
        ForkReplayWeek.apply(read, listOf("2026-09", "td", "", "2026-09-30", "5", "1"))
        assertEquals(mapOf("a" to mapOf("2026-09-30" to ForkDayStats(10_000L, 1))), read.forkTrackDays)
    }

    // ── The backup ──────────────────────────────────────────────────────

    private val phoneMonth = StoredBucket(
        month = "2026-10",
        tracks = listOf(
            TrackEntry(
                id = "abc", title = "T", artist = "A", ms = 600_000L, plays = 3,
                days = mapOf("2026-10-02" to DayStats(400_000L, 2), "2026-10-03" to DayStats(200_000L, 1)),
            ),
            TrackEntry(id = "nodays", title = "N", artist = "B", ms = 60_000L, plays = 1),
        ),
        days = mapOf(2 to 400_000L, 3 to 260_000L),
    )

    @Test
    fun `a phone month brings its songs' days to the PC`() {
        val pc = ForkBackup.toPc(phoneMonth)!!
        assertEquals<Map<String, ForkDayStats>?>(mapOf("2026-10-02" to ForkDayStats(400_000L, 2), "2026-10-03" to ForkDayStats(200_000L, 1)), pc.forkTrackDays["abc"])
        assertNull(pc.forkTrackDays["nodays"])
    }

    @Test
    fun `a month the PC never added to goes back exactly`() {
        val pc = ForkBackup.toPc(phoneMonth)!!
        assertEquals(phoneMonth, ForkBackup.toPhone(pc, phoneMonth))
    }

    @Test
    fun `a PC month gives the phone each song's days and the day's plays`() {
        val pc = bucket(
            "2026-11",
            Triple(song("x", artist = "Lead feat. Guest", album = "Rec"), 90_000L, 1),
            days = mapOf("x" to mapOf("2026-11-04" to ForkDayStats(90_000L, 1))),
            monthDays = mapOf("2026-11-04" to 90_000L),
        )
        val phone = ForkBackup.toPhone(pc, null)
        val track = phone.tracks.single()
        assertEquals(mapOf("2026-11-04" to DayStats(90_000L, 1)), track.days)
        assertEquals(mapOf("2026-11-04" to DayStats(90_000L, 1)), phone.artists.single().days)
        assertEquals(DayStats(90_000L, 1), phone.trackDays!!["2026-11-04"])
        assertEquals(mapOf(4 to 90_000L), phone.days)
        // And back: the same days.
        assertEquals(pc.forkTrackDays, ForkBackup.toPc(phone)!!.forkTrackDays)
    }

    @Test
    fun `days the PC added to an imported month are exported with it`() {
        val pc = ForkBackup.toPc(phoneMonth)!!
        ForkReplayWeek.record(pc, "abc", "2026-10-05", 30_000L, countsAsPlay = true)
        pc.tracks["abc"] = pc.tracks["abc"]!!.copy(listenedMs = 630_000L, plays = 4)
        pc.days["2026-10-05"] = 30_000L
        val out = ForkBackup.toPhone(pc, phoneMonth)
        val track = out.tracks.first { it.id == "abc" }
        assertEquals(DayStats(30_000L, 1), track.days["2026-10-05"])
        assertEquals(DayStats(400_000L, 2), track.days["2026-10-02"])
        assertEquals(1, out.trackDays!!["2026-10-05"]!!.plays)
    }

    // ── Genres ──────────────────────────────────────────────────────────

    @Test
    fun `a Last_fm error answer is not an answer, whatever its status`() {
        assertTrue(ForkArtistGenres.isErrorAnswer("""{"error":29,"message":"Rate limit exceeded"}"""))
        assertTrue(ForkArtistGenres.isErrorAnswer("""{"error":8,"message":"Operation failed - Most likely the backend service failed. Please try again."}"""))
        assertFalse(ForkArtistGenres.isErrorAnswer("""{"toptags":{"tag":[],"@attr":{"artist":"Nobody"}}}"""))
        assertFalse(ForkArtistGenres.isErrorAnswer("""{"toptags":{"tag":[{"count":100,"name":"rock"}]}}"""))
        assertFalse(ForkArtistGenres.isErrorAnswer("not json"))
        // "Artist not found" is an answer: filed as no genre, not asked again every run.
        assertFalse(ForkArtistGenres.isErrorAnswer("""{"error":6,"message":"The artist you supplied could not be found"}"""))
    }

    @Test
    fun `tags become genres only from the vocabulary, in its spelling`() {
        assertEquals("Hip-Hop", ForkArtistGenres.canonical("hip-hop"))
        assertEquals("Hip-Hop", ForkArtistGenres.canonical("Hip Hop"))
        assertEquals("R&B", ForkArtistGenres.canonical("rnb"))
        assertEquals("R&B", ForkArtistGenres.canonical("R&B"))
        assertEquals("Electronic", ForkArtistGenres.canonical("electronica"))
        assertEquals("Bollywood", ForkArtistGenres.canonical("filmi"))
        assertNull(ForkArtistGenres.canonical("seen live"))
        assertNull(ForkArtistGenres.canonical("favourites"))
        assertNull(ForkArtistGenres.canonical(""))
    }

    @Test
    fun `an artist gets their first two genres`() {
        assertEquals(listOf("Indie", "Rock"), ForkArtistGenres.genresFrom(listOf("seen live", "indie", "rock", "indie", "pop")))
    }

    @Test
    fun `the top tags are read from Last fm's response, and anything else is nothing`() {
        val body = """{"toptags":{"tag":[{"count":100,"name":"shoegaze"},{"count":40,"name":"dream pop"}],"@attr":{"artist":"Slowdive"}}}"""
        assertEquals(listOf("shoegaze", "dream pop"), ForkArtistGenres.parseTopTags(body))
        assertEquals(emptyList(), ForkArtistGenres.parseTopTags("""{"error":6,"message":"The artist you supplied could not be found"}"""))
        assertEquals(emptyList(), ForkArtistGenres.parseTopTags("<html>"))
    }

    @Test
    fun `the genre chart credits each artist's time to each of their genres`() {
        val artists = listOf(
            DesktopRankedEntry("A", null, "a.jpg", null, 600_000L, 3),
            DesktopRankedEntry("B", null, "b.jpg", null, 300_000L, 2),
            DesktopRankedEntry("C", null, null, null, 100_000L, 1),
        )
        val genres = mapOf("A" to listOf("Pop"), "B" to listOf("Rock", "Pop"), "C" to emptyList())
        val chart = ForkArtistGenres.rank(artists) { genres[it].orEmpty() }
        assertEquals(listOf("Pop", "Rock"), chart.map { it.title })
        assertEquals(900_000L, chart[0].ms)
        assertEquals(5, chart[0].plays)
        assertEquals("a.jpg", chart[0].artworkUrl)
        // The rows drop the artwork: a lead artist's cover is not a picture of a genre.
        assertNull(ForkReplayText.genreRows(chart, 5).first().artworkUrl)
    }

    // ── The phone's sentences ───────────────────────────────────────────

    private fun summary(period: DesktopReplayPeriod) = DesktopListeningStats.summarise(
        listOf(bucket("2026-09", Triple(song("a"), 600_000L, 3), Triple(song("b", album = null), 60_000L, 1))),
        period,
        wednesday,
    )

    private fun List<ForkHeadlineRun>.text() = joinToString("") { it.text }

    @Test
    fun `the headlines say week on the week and month otherwise`() {
        val week = summary(DesktopReplayPeriod.THIS_WEEK)
        val year = summary(DesktopReplayPeriod.ALL_TIME)
        assertEquals("Your Replay is ready.", ForkReplayText.headline(year, ForkStoryPage.INTRO).text())
        assertEquals("In total, 4 songs filled your month.", ForkReplayText.headline(year, ForkStoryPage.SONGS).text())
        assertEquals("In total, 4 songs filled your week.", ForkReplayText.headline(week, ForkStoryPage.SONGS).text())
        assertEquals("This week, One artist kept coming back.", ForkReplayText.headline(week, ForkStoryPage.ARTISTS).text())
        assertEquals("Your week in music.", ForkReplayText.headline(week, ForkStoryPage.SUMMARY).text())
        assertEquals("You spent 11 minutes with music.", ForkReplayText.headline(year, ForkStoryPage.MINUTES).text())
        assertEquals("You explored 2 songs from 2 artists.", ForkReplayText.headline(year, ForkStoryPage.HABITS).text())
        // Only the number is bold.
        assertEquals(listOf("4 songs"), ForkReplayText.headline(year, ForkStoryPage.SONGS).filter { it.bold }.map { it.text })
    }

    @Test
    fun `the minutes line says across under an hour`() {
        assertEquals("Across 4 plays.", ForkReplayText.minutesLine(summary(DesktopReplayPeriod.ALL_TIME)))
    }

    @Test
    fun `a card with nothing to show is left out of the stories`() {
        val s = summary(DesktopReplayPeriod.ALL_TIME)
        val pages = ForkReplayText.pages(s, emptyList())
        assertFalse(ForkStoryPage.GENRES in pages)
        assertTrue(ForkStoryPage.ALBUMS in pages)
        assertEquals(ForkStoryPage.INTRO, pages.first())
        assertEquals(ForkStoryPage.SUMMARY, pages.last())
        assertTrue(ForkStoryPage.GENRES in ForkReplayText.pages(s, listOf(DesktopRankedEntry("Pop", null, null, null, 1L, 1))))
    }

    @Test
    fun `the cards come in the phone's order and open their own story`() {
        val cards = ForkReplayText.cards(summary(DesktopReplayPeriod.ALL_TIME))
        assertEquals(listOf("Minutes listened", "Top song", "Top artist", "Top album"), cards.map { it.label })
        assertEquals(listOf(ForkStoryPage.MINUTES, ForkStoryPage.SONGS, ForkStoryPage.ARTISTS, ForkStoryPage.ALBUMS), cards.map { it.page })
    }

    @Test
    fun `the cards about something take its artwork`() {
        val s = summary(DesktopReplayPeriod.ALL_TIME)
        assertEquals("https://example.com/a.jpg", ForkReplayText.artwork(s, ForkStoryPage.SONGS))
        assertTrue(ForkReplayText.artwork(s, ForkStoryPage.HABITS)!!.startsWith("https://example.com/"))
    }

    @Test
    fun `listening time reads the phone's way`() {
        assertEquals("5 min", ForkReplayText.listening(5 * 60_000L))
        assertEquals("2h 5m", ForkReplayText.listening(125 * 60_000L))
        assertEquals("1,500 min", ForkReplayText.listening(1_500 * 60_000L))
        assertEquals("1 play", ForkReplayText.plays(1))
        assertEquals("1,200 plays", ForkReplayText.plays(1_200))
        assertEquals("1 minute", ForkReplayText.minutesUnit(60_000L))
    }

    @Test
    fun `a year is Replay and two digits, anything else is spelt out`() {
        assertEquals("Replay 26", ForkReplayText.title("2026"))
        assertEquals("Replay · This week", ForkReplayText.title("This week"))
    }

    @Test
    fun `a hue turn of nothing changes nothing, and a turn keeps the colour opaque`() {
        val color = 0xFF3A1C71.toInt()
        assertEquals(color, ForkReplayText.rotated(color, 0f))
        assertEquals(0xFF, (ForkReplayText.rotated(color, 145f) ushr 24) and 0xFF)
    }

    @Test
    fun `the saved picture's name is the phone's, made safe for Windows`() {
        assertEquals("bitchord-replay-this-week.png", ForkReplayPoster.fileName("This week"))
        assertEquals("bitchord-replay-2026.png", ForkReplayPoster.fileName("2026"))
        assertEquals("bitchord-replay-november-2026.png", ForkReplayPoster.fileName("November 2026"))
        assertEquals("bitchord-replay.png", ForkReplayPoster.fileName("  "))
        assertFalse(ForkReplayPoster.fileName("a/b:c?").contains(Regex("""[\\/:*?"<>|]""")))
    }
}
