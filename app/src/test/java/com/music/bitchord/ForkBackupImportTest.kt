package com.music.bitchord

import com.music.bitchord.data.backup.ForkBackupFormat
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.fork.ForkBackup
import com.music.bitchord.fork.ForkQueues
import com.music.bitchord.fork.ForkSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fork: reading the phone backup's fork section from files that are old, edited or from the PC.
 * What reaches the preference file and the saved-queue list, not what the file says.
 */
class ForkBackupImportTest {

    /** The phone's `Backup.json` (private there). */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private fun decode(text: String) = json.decodeFromString(Backup.BackupFile.serializer(), text)

    @Test
    fun `an old backup without the fork section still reads`() {
        val file = decode("""{"app":"bitchord","version":1,"settings":{"autoplay":{"type":"bool","value":"true"}}}""")
        assertNull(file.fork)
        assertEquals(true, file.settings.getValue("autoplay").decoded())
    }

    @Test
    fun `a fork section that isn't an object doesn't refuse the whole file`() {
        // Before, `fork` was typed as an object: `"fork": []` made the file "not a BitChord backup",
        // and with it the listening history and every upstream setting in it.
        for (bad in listOf("[]", "\"x\"", "3", "null")) {
            val file = decode("""{"app":"bitchord","version":1,"listening":[{"month":"2026-10"}],"fork":$bad}""")
            assertEquals(1, file.listening.size)
            ForkBackup.import(file.fork) // and is skipped without touching anything (prefs aren't set up here)
        }
    }

    @Test
    fun `a switch only takes a value of its own type`() {
        // An Int under a switch's key was stored as is, and every later getBoolean threw (in init too).
        assertNull(ForkSettings.backupValue("backdrop_clips", 1))
        assertNull(ForkSettings.backupValue("backdrop_clips", "true"))
        assertEquals(false, ForkSettings.backupValue("backdrop_clips", false))
        assertNull(ForkSettings.backupValue("clip_cache_mb", true))
        assertNull(ForkSettings.backupValue("preload_songs", 2L))
    }

    @Test
    fun `counts only take the values Settings offers`() {
        assertEquals(300, ForkSettings.backupValue("clip_cache_mb", 300))
        assertNull(ForkSettings.backupValue("clip_cache_mb", -5))
        assertNull(ForkSettings.backupValue("clip_cache_mb", 1_000_000))
        assertEquals(2, ForkSettings.backupValue("preload_songs", 2))
        assertNull(ForkSettings.backupValue("preload_songs", 10_000))
    }

    @Test
    fun `device state and unknown keys are never imported`() {
        assertNull(ForkSettings.backupValue("offline_mode", true))
        assertNull(ForkSettings.backupValue("canvas_manual_set", true))
        assertNull(ForkSettings.backupValue("navidrome_password", true))
    }

    @Test
    fun `the phone keeps its own kinds of song address and drops the PC's files`() {
        val none: (String) -> Boolean = { false }
        // An SMB song has no other address: dropping it left the queue's song unplayable after a restore.
        assertTrue(ForkBackup.keepsAddress("smb://nas/music/a.flac", none))
        assertTrue(ForkBackup.keepsAddress("content://media/external/audio/media/77", none))
        assertFalse(ForkBackup.keepsAddress("C:\\Users\\me\\Music\\a.flac", none))
        assertFalse(ForkBackup.keepsAddress("file:///C:/Users/me/Music/a.flac", none))
        assertTrue(ForkBackup.keepsAddress("/storage/emulated/0/Music/a.mp3") { it == "/storage/emulated/0/Music/a.mp3" })
    }

    @Test
    fun `queues for this device keep web addresses and what the device keeps, nothing else`() {
        val queues = Json.parseToJsonElement(
            """[{"id":"1","songs":[
                {"videoId":"a","localUri":"https://dav.example.com/a.mp3"},
                {"videoId":"b","localUri":"C:\\Music\\b.flac","localPath":"C:\\Music\\b.flac"},
                {"videoId":"c","localUri":"smb://nas/c.flac"},
                {"videoId":"d","localUri":7}
            ]}]""",
        ).jsonArray
        val songs = ForkBackupFormat.queuesForThisDevice(queues) { ForkBackup.keepsAddress(it) { false } }
            .single().jsonObject.getValue("songs").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("https://dav.example.com/a.mp3"), songs[0]["localUri"])
        assertFalse("localUri" in songs[1] || "localPath" in songs[1])
        assertEquals(JsonPrimitive("smb://nas/c.flac"), songs[2]["localUri"])
        assertFalse("localUri" in songs[3])
    }

    @Test
    fun `a saved queue's index and second stay inside it`() {
        val parsed = ForkQueues.parse(
            Json.parseToJsonElement(
                """[{"id":"1","name":"A","index":9,"positionMs":-4,"songs":[{"videoId":"x"},{"videoId":"y"}]},
                    {"id":"2","index":-3,"songs":[]},
                    {"name":"no id"},
                    "not a queue"]""",
            ).jsonArray,
        )
        assertEquals(listOf("1", "2"), parsed.map { it.id })
        assertEquals(1, parsed[0].index)
        assertEquals(0L, parsed[0].positionMs)
        assertEquals("y", parsed[0].current?.videoId)
        assertEquals(0, parsed[1].index)
    }

    @Test
    fun `the scrub reaches logins nested anywhere in the section`() {
        val login = "https://music.example.com/rest/getCoverArt?id=al-1&u=me&t=abc&s=salt&p=enc:pw"
        val section = JsonObject(
            mapOf(
                "savedQueues" to JsonArray(listOf(JsonObject(mapOf("songs" to JsonArray(listOf(
                    JsonObject(mapOf("thumbnailUrl" to JsonPrimitive(login), "title" to JsonPrimitive("/rest/ in a title"))),
                )))))),
            ),
        )
        val text = ForkBackupFormat.scrubbed(section).toString()
        for (param in listOf("u=", "t=", "s=", "p=")) assertFalse(text, "&$param" in text || "?$param" in text)
        assertTrue(text, "id=al-1" in text)
        assertTrue(text, "/rest/ in a title" in text)
    }
}
