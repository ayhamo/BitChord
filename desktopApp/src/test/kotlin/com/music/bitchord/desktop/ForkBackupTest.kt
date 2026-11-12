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
        // back exactly as it came. The settings whose defaults differ between the apps, which the
        // phone left unset, come back spelled out with the phone's default (what it was using).
        val spelledOut = phoneDefaultsTheFileLeftOut.mapValues { (_, value) ->
            ForkBackupFormat.json.encodeToJsonElement(ForkBackupFormat.PrefValue.serializer(), ForkBackupFormat.PrefValue.of(value)!!)
        }
        assertEquals(JsonObject(phone["settings"]!!.jsonObject + spelledOut), out["settings"])
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

    /** Automix style, key-aware, phrase length and the overview's picks: the phone's keys both ways, only values both apps have. */
    @Test
    fun `the Automix settings and picks travel between the phone and the PC`() {
        val of = ForkBackupFormat.PrefValue.Companion::of
        fun section(vararg values: Pair<String, Any>) = kotlinx.serialization.json.buildJsonObject {
            put("settings", kotlinx.serialization.json.buildJsonObject {
                values.forEach { (key, value) -> put(key, ForkBackupFormat.json.encodeToJsonElement(ForkBackupFormat.PrefValue.serializer(), of(value)!!)) }
            })
        }
        val picks = "a->b\tloop\t\nc->d\tfilter\t8"
        ForkBackup.apply(
            ForkBackupFormat.BackupFile(
                fork = section(
                    "automix_style" to "advanced_autodj", "automix_key_aware" to true,
                    "automix_phrase_bars" to 16, "automix_pair_picks" to picks,
                ),
            ),
        )
        assertEquals("advanced_autodj", main.get("automix_style", null))
        assertEquals("true", main.get("automix_key_aware", null))
        assertEquals("16", main.get("automix_phrase_bars", null))
        assertEquals(picks, main.get("automix_pair_picks", null))
        val settings = exported()["fork"]!!.jsonObject["settings"]!!.jsonObject
        fun pref(value: Any) = ForkBackupFormat.json.encodeToJsonElement(ForkBackupFormat.PrefValue.serializer(), of(value)!!)
        assertEquals(pref("advanced_autodj"), settings["automix_style"])
        assertEquals(pref(true), settings["automix_key_aware"])
        assertEquals(pref(16), settings["automix_phrase_bars"])
        assertEquals(pref(picks), settings["automix_pair_picks"])
        // Not PC-only: they leave in the phone's part, never a second time in the PC's own.
        assertFalse(ForkBackup.pcOnly("automix_style"))
        assertFalse(ForkBackup.pcOnly("automix_pair_picks"))

        // A style or length neither app has: the PC's default, as an unread key.
        ForkBackup.apply(ForkBackupFormat.BackupFile(fork = section("automix_style" to "turntable", "automix_phrase_bars" to 32)))
        assertNull(main.get("automix_style", null))
        assertNull(main.get("automix_phrase_bars", null))
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

    @Test
    fun `track length tolerance and font travel under the phone's keys, inside what Settings offers`() {
        val of = ForkBackupFormat.PrefValue.Companion::of
        ForkBackup.apply(
            ForkBackupFormat.BackupFile(settings = mapOf("upgrade_length_slack_seconds" to of(8)!!, "app_font" to of("LEGACY")!!)),
        )
        assertEquals(8, main.getInt(ForkUpgradeLength.KEY, -1))
        assertEquals("LEGACY", main.get(ForkAppFont.KEY, null))
        val settings = exported()["settings"]!!.jsonObject
        assertEquals(ForkBackupFormat.json.encodeToJsonElement(ForkBackupFormat.PrefValue.serializer(), of(8)!!), settings["upgrade_length_slack_seconds"])
        assertEquals(ForkBackupFormat.json.encodeToJsonElement(ForkBackupFormat.PrefValue.serializer(), of("LEGACY")!!), settings["app_font"])

        // Past the slider's 10 s, or a font the phone doesn't have: the PC's default, as an unread key.
        ForkBackup.apply(
            ForkBackupFormat.BackupFile(settings = mapOf("upgrade_length_slack_seconds" to of(11)!!, "app_font" to of("COMIC")!!)),
        )
        assertNull(main.get(ForkUpgradeLength.KEY, null))
        assertNull(main.get(ForkAppFont.KEY, null))
    }

    /** Mapped settings whose defaults differ between the apps and which `fork-phone-backup.json` leaves unset. */
    private val phoneDefaultsTheFileLeftOut = mapOf(
        "filter_non_music_audio" to true, "full_bleed_artwork" to true, "spotify_canvas_auto_hide" to true,
        "lastfm_now_playing" to false, "lastfm_scrobble_enabled" to false,
    )

    @Test
    fun `a default that differs travels as the value in force, both ways`() {
        // PC -> phone: nothing stored on the PC is its own default spelled out, not "absent" (which
        // the phone would read as its own, different default).
        val settings = exported()["settings"]!!.jsonObject
        val of = ForkBackupFormat.PrefValue.Companion::of
        fun encoded(value: Any) = ForkBackupFormat.json.encodeToJsonElement(ForkBackupFormat.PrefValue.serializer(), of(value)!!)
        assertEquals(encoded(false), settings["full_bleed_artwork"])
        assertEquals(encoded(true), settings["lastfm_scrobble_enabled"])
        // Phone -> PC: a backup without it is the phone's default.
        ForkBackup.apply(ForkBackupFormat.BackupFile(settings = emptyMap()))
        assertEquals("true", main.get("full_bleed_artwork", null))
        assertEquals("false", main.get("lastfm_scrobble_enabled", null))
        // A setting the defaults agree on stays unset.
        assertNull(main.get("autoplay", null))
    }

    @Test
    fun `NetEase keeps its tick and place in the lyrics sources`() {
        main.put("lyrics_source_order", "NetEase,BiniLyrics")
        main.put("lyrics_sources", "BiniLyrics,NetEase")
        val settings = exported()["settings"]!!.jsonObject
        val order = ForkBackupFormat.json.decodeFromJsonElement(ForkBackupFormat.PrefValue.serializer(), settings["lyrics_source_order"]!!)
        assertEquals("NetEase,BINI_LYRICS", order.value)
        main.clear()
        ForkBackup.apply(ForkBackupFormat.BackupFile(settings = mapOf("lyrics_sources" to ForkBackupFormat.PrefValue.of("BINI_LYRICS,NetEase")!!)))
        assertEquals("BiniLyrics,NetEase", main.get("lyrics_sources", null))
    }

    @Test
    fun `the shared signature, alignment and direct-stream settings travel`() {
        main.put("signature_api_enabled", "true")
        main.put("signature_api_url", "https://sig.example.com")
        main.put("smart_version_alignment", "false")
        main.put("direct_source_stream", "true")
        val settings = exported()["settings"]!!.jsonObject
        listOf("signature_api_enabled", "signature_api_url", "smart_version_alignment", "direct_source_stream")
            .forEach { assertTrue(settings[it] != null, it) }
        assertFalse(ForkBackup.pcOnly("signature_api_enabled"))
    }

    @Test
    fun `the yt-dlp path is this machine's`() {
        assertFalse(ForkBackup.pcOnly("fork_ytdlp_path"))
    }

    @Test
    fun `an old quality is exported as the rung it means`() {
        assertEquals("LOSSLESS", ForkBackup.migratedQuality("HIGH", hasFourRungs = false))
        assertEquals("HIGH", ForkBackup.migratedQuality("HIGH", hasFourRungs = true))
        assertEquals("MEDIUM", ForkBackup.migratedQuality("STANDARD", hasFourRungs = true))
    }

    @Test
    fun `an export replaces the target through a temporary file`() {
        val target = scratch.resolve("backup.json")
        Files.writeString(target, "old")
        ForkBackup.writeReplacing(target, "new")
        assertEquals("new", Files.readString(target))
        assertFalse(Files.exists(scratch.resolve("backup.json.tmp")))
        // A target that can't be replaced (a non-empty folder in the way) fails and leaves no temp behind.
        val blocked = scratch.resolve("blocked.json")
        Files.createDirectories(blocked.resolve("inside"))
        kotlin.test.assertFails { ForkBackup.writeReplacing(blocked, "x") }
        assertFalse(Files.exists(scratch.resolve("blocked.json.tmp")))
    }

    @Test
    fun `an import reports a part that failed instead of throwing`() {
        // The carried file's place is a non-empty folder: writing the phone-only settings fails.
        val inTheWay = scratch.resolve("carried-dir")
        Files.createDirectories(inTheWay.resolve("inside"))
        ForkBackup.carriedFile = { inTheWay.toFile() }
        val problems = ForkBackup.apply(ForkBackupFormat.BackupFile(settings = mapOf("autoplay" to ForkBackupFormat.PrefValue.of(false)!!)))
        assertEquals(listOf("phone-only settings"), problems)
        assertEquals("false", main.get("autoplay", null))
    }

    @Test
    fun `an import counts as having seen the welcome, and the flag never travels`() {
        main.put(ForkWelcome.KEY, "true")
        assertFalse(ForkBackup.pcOnly(ForkWelcome.KEY))
        val pc = exported()["fork"]!!.jsonObject["pc"]!!.jsonObject["settings"]!!.jsonObject
        assertNull(pc[ForkWelcome.KEY])

        main.remove(ForkWelcome.KEY)
        importPhone()
        assertEquals("true", main.get(ForkWelcome.KEY, null))
    }
}
