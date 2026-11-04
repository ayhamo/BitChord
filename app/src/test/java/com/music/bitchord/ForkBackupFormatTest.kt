package com.music.bitchord

import com.music.bitchord.data.backup.ForkBackupFormat
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.data.stats.DayStats
import com.music.bitchord.data.stats.NameEntry
import com.music.bitchord.data.stats.StoredBucket
import com.music.bitchord.data.stats.TrackEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Fork: the PC's backup reader and writer (`ForkBackupFormat`, in `:shared`) against the phone's
 * own classes. The fixture is the file the PC's tests import (`ForkBackupTest`); this proves it is
 * what the phone's serializer writes, and that the shared mirror reads and writes it unchanged.
 */
class ForkBackupFormatTest {

    /** The phone's `Backup.json`, which is private. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val fixture = File("../desktopApp/src/test/resources/fork-phone-backup.json")

    private fun day(ms: Long, plays: Int) = DayStats(ms, plays)

    private fun days() = mutableMapOf("2026-10-02" to day(400_000, 2), "2026-10-03" to day(200_000, 1))

    private fun phoneFile(): Backup.BackupFile {
        val art = "https://i.ytimg.com/vi/abc123/hq.jpg"
        val settings = linkedMapOf<String, Any?>(
            "autoplay" to false,
            "crossfade_seconds" to 6,
            "playback_speed" to 1.25f,
            "repeat_mode" to 2,
            "audio_quality_wifi" to "HIGH",
            "audio_quality_cellular" to "MEDIUM",
            "audio_quality_download" to "STANDARD",
            "smart_fade_enabled" to true,
            "shuffle_enabled" to true,
            "lyrics_source_order" to "BINI_LYRICS,LRC_RED",
            "lyrics_offset_ms" to -250,
            "equalizer_bands" to "1.0,0.0,-2.5,0.0,0.0,0.0,3.0",
            "theme_mode" to "LIGHT",
            "pinned_playlists" to setOf("VLPL123"),
            "lastfm_username" to "someone",
            "search_history" to "[{\"id\":\"abc123\",\"title\":\"A Song\",\"subtitle\":\"Song • An Artist\"," +
                "\"artworkUrl\":\"$art\",\"entityType\":\"TRACK\",\"timestamp\":1730000000000}]",
        )
        val bucket = StoredBucket(
            month = "2026-10",
            tracks = listOf(
                TrackEntry(
                    id = "abc123", title = "A Song", artist = "An Artist & Friend", album = "An Album",
                    albumId = "MPREb_1", artistId = "UC1", art = art, ms = 600_000, plays = 3,
                    last = 1_730_000_000_000, days = days(),
                ),
            ),
            artists = listOf(NameEntry("An Artist", null, art, "UC1", 600_000, 3, "an artist", days())),
            albums = listOf(NameEntry("An Album", "An Artist & Friend", art, "MPREb_1", 600_000, 3, "MPREb_1", days())),
            hours = List(24) { when (it) { 20 -> 400_000L; 21 -> 200_000L; else -> 0L } },
            days = mapOf(2 to 400_000L, 3 to 200_000L),
            trackDays = days(),
            artistDays = days(),
            albumDays = days(),
        )
        val fork = Json.parseToJsonElement(fixture.readText()).let { (it as JsonObject)["fork"] as JsonObject }
        return Backup.BackupFile(
            versionName = "1.9.0",
            exportedAt = "2026-11-04T10:00:00Z",
            settings = settings.mapValues { Backup.PrefValue.of(it.value)!! },
            listening = listOf(bucket),
            fork = fork,
        )
    }

    @Test
    fun `the PC's fixture is what the phone writes`() {
        val written = json.encodeToString(Backup.BackupFile.serializer(), phoneFile())
        assertEquals(Json.parseToJsonElement(fixture.readText()), Json.parseToJsonElement(written))
    }

    @Test
    fun `the shared mirror reads the phone's file and writes it back unchanged`() {
        val written = json.encodeToString(Backup.BackupFile.serializer(), phoneFile())
        val mirrored = ForkBackupFormat.json.decodeFromString(ForkBackupFormat.BackupFile.serializer(), written)
        val again = ForkBackupFormat.json.encodeToString(ForkBackupFormat.BackupFile.serializer(), mirrored)
        assertEquals(Json.parseToJsonElement(written), Json.parseToJsonElement(again))
        // And the phone reads what the mirror writes.
        val back = json.decodeFromString(Backup.BackupFile.serializer(), again)
        assertEquals(phoneFile().listening, back.listening)
        assertEquals(phoneFile().settings, back.settings)
    }

    @Test
    fun `logins come out of every address`() {
        val url = "https://music.example.com/rest/getCoverArt?id=al-1&u=me&t=abc&s=salt&v=1.16.1&c=bitchord"
        assertEquals("https://music.example.com/rest/getCoverArt?id=al-1&v=1.16.1&c=bitchord", ForkBackupFormat.scrubUrl(url))
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", ForkBackupFormat.scrubUrl("https://i.ytimg.com/vi/x/hq.jpg"))
    }
}
