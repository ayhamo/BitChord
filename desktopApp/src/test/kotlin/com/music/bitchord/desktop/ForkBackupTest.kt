package com.music.bitchord.desktop

import com.music.bitchord.data.backup.ForkBackupFormat
import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: [ForkBackup] against a file the phone wrote (`fork-phone-backup.json`, which the app's
 * `ForkBackupFormatTest` proves is the phone serializer's output): it imports on the PC, and the
 * PC's export of it reads back as the phone's file. Runs on the real preference nodes, so each
 * test puts back what was there.
 */
class ForkBackupTest {

    private val scratch = Files.createTempDirectory("bitchord-backup")
    private val nodes = listOf(
        Preferences.userRoot().node("com.music.bitchord.desktop"),
        Preferences.userRoot().node("com/music/bitchord/navidrome_fork"),
        Preferences.userRoot().node("com.music.bitchord.desktop.stats"),
    )
    private lateinit var saved: List<Map<String, String>>
    private lateinit var savedQueues: JsonArray

    private val phoneText: String = javaClass.getResource("/fork-phone-backup.json")!!.readText()
    private val phone: JsonObject = Json.parseToJsonElement(phoneText).jsonObject

    @BeforeTest
    fun keep() {
        saved = nodes.map { node -> node.keys().associateWith { node.get(it, "") } }
        savedQueues = ForkQueues.exportForBackup()
        nodes.forEach { it.clear() }
        ForkBackup.carriedFile = { scratch.resolve("carried.json").toFile() }
    }

    @AfterTest
    fun putBack() {
        nodes.forEachIndexed { i, node ->
            node.clear()
            saved[i].forEach(node::put)
            node.flush()
        }
        ForkQueues.importFromBackup(savedQueues)
        scratch.toFile().deleteRecursively()
    }

    private val main get() = nodes[0]
    private val fork get() = nodes[1]

    private fun importPhone() {
        val file = scratch.resolve("phone.json")
        Files.writeString(file, phoneText)
        ForkBackup.importFrom(file)
    }

    private fun exported(): JsonObject {
        val file: Path = scratch.resolve("pc.json")
        ForkBackup.exportTo(file)
        return Json.parseToJsonElement(Files.readString(file)).jsonObject
    }

    @Test
    fun `a phone backup lands in the PC's settings, Replay and saved queues`() {
        main.put("discord_token", "keep-me")
        importPhone()

        assertEquals("false", main.get("autoplay", null))
        assertEquals(6, main.getInt("crossfade_seconds", 0))
        assertEquals("1.25", main.get("playback_speed", null))
        assertEquals("ALL", main.get("repeat_mode", null))
        assertEquals("HIGH", main.get("audio_quality", null))
        assertTrue(main.getBoolean("audio_quality_rungs", false))
        assertEquals("STANDARD", main.get("download_quality", null))
        assertTrue(main.getBoolean("automix", false))
        assertTrue(main.getBoolean("shuffle", false))
        assertEquals("BiniLyrics,lrc.red", main.get("lyrics_source_order", null))
        assertEquals(-250, main.getInt("lyrics_offset_ms", 0))
        assertEquals("1.0,0.0,-2.5,0.0,0.0,0.0,3.0", main.get("equalizer_bands", null))
        assertTrue("abc123" in main.get("search_entities", "")!!)
        // Phone-only settings are not written into the PC's settings, and a PC login is left alone.
        assertNull(main.get("theme_mode", null))
        assertNull(main.get("lastfm_username", null))
        assertEquals("keep-me", main.get("discord_token", null))

        // The fork's switches.
        assertFalse(main.getBoolean("crossfade_on_skip", true))
        assertEquals("off", fork.get("backdrop_videos", null))
        assertEquals(2, fork.getInt("preload_songs", 0))

        // Replay: the October month, with its days.
        val replay = DesktopListeningStats.summary(DesktopReplayPeriod.ALL_TIME)
        assertEquals(600_000L, replay.totalMs)
        assertEquals(3, replay.totalPlays)
        assertEquals("2026-10-02", replay.busiestDay)
        assertEquals(400_000L, replay.hourOfDay[20])

        // The saved queue, without the phone's content:// address.
        val queue = ForkQueues.saved.value.single()
        assertEquals("Evening", queue.name)
        assertNull(queue.songs.single().localUri)
    }

    @Test
    fun `the PC's export of a phone backup is the phone's file again`() {
        importPhone()
        val out = exported()
        assertEquals(ForkBackupFormat.APP_TAG, out["app"]!!.jsonPrimitive.content)
        // Every setting, the phone-only ones carried through; the month untouched on the PC goes
        // back exactly as it came.
        assertEquals(phone["settings"], out["settings"])
        assertEquals(phone["listening"], out["listening"])
        val phoneFork = phone["fork"]!!.jsonObject
        val outFork = out["fork"]!!.jsonObject
        assertEquals(phoneFork["settings"], outFork["settings"])
        assertEquals("Evening", outFork["savedQueues"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        // And the phone's own serializer shape reads it.
        ForkBackupFormat.json.decodeFromString(ForkBackupFormat.BackupFile.serializer(), out.toString())
    }

    @Test
    fun `a month the PC added to is rebuilt the phone's way`() {
        importPhone()
        val song = Song(videoId = "xyz", title = "New", artist = "Lead feat. Guest", thumbnailUrl = null, albumName = "Rec", albumId = "MPREb_9")
        DesktopListeningStats.record(song, 90_000L, countsAsPlay = true)
        val months = exported()["listening"]!!.jsonArray.map { it.jsonObject }
        val october = months.single { it["month"]!!.jsonPrimitive.content == "2026-10" }
        assertEquals(phone["listening"]!!.jsonArray.single(), october)
        val now = months.single { it["month"]!!.jsonPrimitive.content != "2026-10" }
        val track = now["tracks"]!!.jsonArray.single().jsonObject
        assertEquals("xyz", track["id"]!!.jsonPrimitive.content)
        assertEquals("90000", track["ms"]!!.jsonPrimitive.content)
        assertEquals("lead", now["artists"]!!.jsonArray.single().jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("MPREb_9", now["albums"]!!.jsonArray.single().jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the PC's own settings travel PC to PC, never a login`() {
        main.put("close_to_tray", "true")
        main.put("liked_ids.0", "a\nb")
        main.put("discord_token", "secret")
        main.put("google_accounts_v1.0", "[{}]")
        main.put("listen_together_token", "secret")
        main.put("lastfm_session_key", "secret")
        main.put("window_backdrop", "https://music.example.com/rest/getCoverArt?id=1&u=me&t=tok&s=salt")
        main.put("history.0", "device-local")
        val out = exported()
        val text = out.toString()
        assertFalse("secret" in text || "tok" in text || "[{}]" in text || "device-local" in text, text)
        val pc = out["fork"]!!.jsonObject["pc"]!!.jsonObject["settings"]!!.jsonObject
        assertEquals(JsonPrimitive("true"), pc["close_to_tray"])
        assertEquals(JsonPrimitive("a\nb"), pc["liked_ids.0"])

        // Restored on another PC (here: after clearing this one).
        val file = scratch.resolve("pc.json")
        main.clear()
        ForkBackup.importFrom(file)
        assertEquals("true", main.get("close_to_tray", null))
        assertEquals("a\nb", main.get("liked_ids.0", null))
        assertNull(main.get("discord_token", null))
    }

    @Test
    fun `secret-looking keys are never PC-only settings`() {
        listOf("discord_token", "paxsenix_api_key_protected", "listen_together_code", "spotify_spdc_token", "queue.0", "history")
            .forEach { assertFalse(ForkBackup.pcOnly(it), it) }
        listOf("close_to_tray", "liked_ids.3", "window_backdrop", "volume").forEach { assertTrue(ForkBackup.pcOnly(it), it) }
    }

    @Test
    fun `values of the wrong type or outside what Settings offers are left at the default`() {
        val of = ForkBackupFormat.PrefValue.Companion::of
        val fork = kotlinx.serialization.json.buildJsonObject {
            put("settings", kotlinx.serialization.json.buildJsonObject {
                put("clip_cache_mb", ForkBackupFormat.json.encodeToJsonElement(ForkBackupFormat.PrefValue.serializer(), of(7)!!))
                put("preload_songs", ForkBackupFormat.json.encodeToJsonElement(ForkBackupFormat.PrefValue.serializer(), of(true)!!))
                put("crossfade_on_skip", ForkBackupFormat.json.encodeToJsonElement(ForkBackupFormat.PrefValue.serializer(), of(3)!!))
            })
            // A part that doesn't read is skipped, not the whole import.
            put("savedQueues", JsonPrimitive("not a list"))
        }
        ForkBackup.apply(
            ForkBackupFormat.BackupFile(
                settings = mapOf(
                    "playback_speed" to of(0f)!!,
                    "crossfade_seconds" to of(99)!!,
                    "autoplay" to of(5)!!,
                    "synced_lyrics" to of(false)!!,
                ),
                fork = fork,
            ),
        )
        val main = nodes[0]
        val navidrome = nodes[1]
        assertNull(navidrome.get("clip_cache_mb", null))
        assertNull(navidrome.get("preload_songs", null))
        assertNull(main.get("crossfade_on_skip", null))
        assertNull(main.get("playback_speed", null))
        assertNull(main.get("crossfade_seconds", null))
        assertNull(main.get("autoplay", null))
        assertEquals("false", main.get("synced_lyrics", null))
    }

    @Test
    fun `saved queues come back without repeated ids or an index past the end`() {
        val song = kotlinx.serialization.json.buildJsonObject { put("videoId", JsonPrimitive("aaaaaaaaaaa")); put("title", JsonPrimitive("t")); put("artist", JsonPrimitive("a")) }
        fun queue(index: Int, position: Long) = kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive("q1")); put("name", JsonPrimitive("Q")); put("index", JsonPrimitive(index))
            put("positionMs", JsonPrimitive(position)); put("savedAt", JsonPrimitive(1L)); put("songs", JsonArray(listOf(song)))
        }
        ForkQueues.importFromBackup(JsonArray(listOf(queue(9, -5L), queue(0, 0L))))
        val back = ForkQueues.exportForBackup()
        assertEquals(1, back.size)
        assertEquals(0, back[0].jsonObject["index"]!!.jsonPrimitive.content.toInt())
        assertEquals(0L, back[0].jsonObject["positionMs"]!!.jsonPrimitive.content.toLong())
    }
}
