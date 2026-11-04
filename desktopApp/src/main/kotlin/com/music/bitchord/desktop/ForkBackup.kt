package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.music.bitchord.data.backup.ForkBackupFormat
import com.music.bitchord.data.backup.ForkBackupFormat.BackupFile
import com.music.bitchord.data.backup.ForkBackupFormat.DayStats
import com.music.bitchord.data.backup.ForkBackupFormat.NameEntry
import com.music.bitchord.data.backup.ForkBackupFormat.PrefValue
import com.music.bitchord.data.backup.ForkBackupFormat.StoredBucket
import com.music.bitchord.data.backup.ForkBackupFormat.TrackEntry
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.awt.FileDialog
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.prefs.Preferences

/**
 * Fork: the phone's Export data / Import data (`app/.../data/stats/Backup.kt`, Settings > Your
 * data) on the PC, in the phone's file ([ForkBackupFormat]), so a phone's backup restores on the PC
 * and the PC's on the phone, for what both have:
 *
 *  - settings both apps have, key by key ([MAIN], [FORK]): most share the phone's name, some are
 *    renamed or stored differently on the PC (repeat mode as a name, lyrics sources by label);
 *  - listening history (Replay), month by month;
 *  - the search history, the saved queues and the fork's switches (the fork's section, which the
 *    phone's own `ForkBackup` writes and reads).
 *
 * What only the phone has (its theme, its Wi-Fi/cellular split, ...) is kept aside on import
 * ([CARRIED_FILE]) and written back out on export, so phone → PC → phone loses nothing; a month of
 * listening the PC never added to goes back exactly as it came (per-day figures and all). What only
 * the PC has (its window, tray and audio-output settings, liked songs, playlists) goes in the fork
 * section's `pc` part, which the phone ignores.
 *
 * Never in the file: logins, tokens, keys, cookies, the Google accounts, the Navidrome login (its
 * own node, never read here), and every Subsonic login in an address ([ForkBackupFormat.scrubbed]).
 * Nor what describes this machine: the queue being played, downloads, the music folder, the output
 * device. Import replaces, as the phone's does, and then closes the app so nothing still on screen
 * writes the old values back.
 *
 * PC-Continued's `DesktopBackup.kt` (e16ed1f8) was read for reference only; its file isn't the phone's.
 */
internal object ForkBackup {

    /** The phone's `Backup.suggestedName()`. */
    fun suggestedName(): String =
        "bitchord-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.now().atZone(ZoneId.systemDefault()))}.json"

    // ── Settings both apps have ─────────────────────────────────────────

    /** One setting: the phone's key and type, the PC's node and key, and the value either way. */
    internal class Mapped(
        val phoneKey: String,
        val pcKey: String,
        val node: () -> Preferences,
        /** The phone's typed value as the PC's stored text; null when it isn't one the PC takes. */
        val toPc: (Any) -> String?,
        /** The PC's stored text as the phone's typed value; null when it can't be read as one. */
        val toPhone: (String) -> Any?,
    )

    private val main: () -> Preferences = { DesktopPersistence().preferences }
    private val navidromeFork: () -> Preferences = { Preferences.userRoot().node("com/music/bitchord/navidrome_fork") }

    private fun bool(phone: String, pc: String = phone, node: () -> Preferences = main) =
        Mapped(phone, pc, node, { (it as? Boolean)?.toString() }, { it.toBooleanStrictOrNull() })

    /**
     * A number; with [allowed], only the counts Settings offers (a backup's other number, or a value
     * of another type, is the PC's default, as an unread key is).
     */
    private fun int(phone: String, pc: String = phone, node: () -> Preferences = main, allowed: Iterable<Int>? = null) =
        Mapped(
            phone, pc, node,
            { (it as? Int)?.takeIf { value -> allowed == null || value in allowed }?.toString() },
            { it.trim().toIntOrNull()?.takeIf { value -> allowed == null || value in allowed } },
        )

    /** A finite number, inside [range] when one is given. */
    private fun float(phone: String, pc: String = phone, range: ClosedFloatingPointRange<Float>? = null) =
        Mapped(
            phone, pc, main,
            { (it as? Float)?.takeIf { value -> value.isFinite() && (range == null || value in range) }?.toString() },
            { it.trim().toFloatOrNull()?.takeIf { value -> value.isFinite() && (range == null || value in range) } },
        )

    private fun text(phone: String, pc: String = phone) = Mapped(phone, pc, main, { it as? String }, { it })

    /** A value from a fixed set of names, the same on both (an enum's `name`). */
    private fun named(phone: String, pc: String, names: Collection<String>) = Mapped(
        phone, pc, main,
        { (it as? String)?.takeIf { value -> value in names } },
        { it.takeIf { value -> value in names } },
    )

    /** The PC's Navidrome switches stored as "on" / "off" ([ForkNavidrome]). */
    private fun onOff(phone: String, pc: String) = Mapped(
        phone, pc, navidromeFork,
        { (it as? Boolean)?.let { on -> if (on) "on" else "off" } },
        { when (it) { "on" -> true; "off" -> false; else -> null } },
    )

    /** Lyrics sources: the phone stores their names, the PC their labels. */
    private fun lyricsSources(phone: String) = Mapped(
        phone, phone, main,
        { value -> (value as? String)?.split(',')?.mapNotNull { lyricsSourceNamed(it.trim()) }?.joinToString(",") { it.label } },
        { value -> value.split(',').mapNotNull { lyricsSourceNamed(it.trim()) }.joinToString(",") { it.name } },
    )

    /** Player's repeat: the phone's Media3 number (0 off, 1 one, 2 all), the PC's name. */
    private val repeat = Mapped(
        "repeat_mode", "repeat_mode", main,
        { when (it as? Int) { 0 -> "OFF"; 1 -> "ONE"; 2 -> "ALL"; else -> null } },
        { when (it) { "OFF" -> 0; "ONE" -> 1; "ALL" -> 2; else -> null } },
    )

    /** The phone's search history (`search_history`) is the same list the PC keeps as `search_entities`. */
    private val searchHistory = Mapped(
        "search_history", "search_entities", main,
        { value -> (value as? String)?.let { searches(it, NavidromeRepository::live) } },
        { value -> searches(value, NavidromeRepository::atRest) },
    )

    private val searchSerializer = ListSerializer(SearchHistoryEntity.serializer())

    private fun searches(text: String, address: (String?) -> String?): String? = runCatching {
        val list = ForkBackupFormat.json.decodeFromString(searchSerializer, text)
        kotlinx.serialization.json.Json.encodeToString(searchSerializer, list.map { it.copy(artworkUrl = address(it.artworkUrl)) })
    }.getOrNull()

    private val qualities = listOf("LOW", "MEDIUM", "HIGH", "LOSSLESS")

    /** `bitchord_settings` on the phone. */
    internal val MAIN: List<Mapped> = listOf(
        bool("animated_canvas"), bool("autoplay"), int("crossfade_seconds", allowed = 0..12), bool("dolby_atmos"),
        bool("dont_repeat_suggestions"), bool("filter_non_music_audio"), bool("full_bleed_artwork"),
        bool("hide_song_status"), bool("hide_volume_bar"), bool("legacy_mesh_gradient"),
        bool("loudness_normalization"), bool("lyrics_blur"), int("lyrics_offset_ms"), float("playback_speed", range = 0.25f..4f),
        bool(ForkArtistGenres.KEY_ENABLED), // "Work out genres" (ForkArtistGenres)
        bool("sync_downloaded_playlists"), // "Keep playlists updated" (ForkPlaylistSync), the phone's own key
        bool("reduce_animation"), bool("reduce_dynamic_blur"), bool("show_listen_together_button"),
        bool("show_nerd_stats"), bool("show_queue_history"), bool("skip_silence"), bool("spatial_audio"),
        bool("spotify_canvas_auto_hide"), bool("synced_lyrics"), text("translation_language"),
        bool(ForkSpotifyCanvasFirst.KEY), bool(ForkCachedSongs.KEY_SHOWN), // "Prioritize Spotify Canvas", "Show cached songs folder": the phone's keys
        named("last_player_screen", "last_player_screen", listOf("MAIN", "LYRICS", "QUEUE")),
        named("library_sort", "library_sort", com.music.bitchord.data.settings.LibrarySort.entries.map { it.name }),
        // Equaliser: the same names, numbers and comma-joined bands on both.
        bool("equalizer_enabled"), bool("equalizer_focused"), float("equalizer_balance"), text("equalizer_bands"),
        named("equalizer_mode", "equalizer_mode", listOf("DYNAMIC", "MANUAL")),
        int("equalizer_tone_x"), int("equalizer_tone_y"),
        // Scrobbling switches (never the keys or the session: see [SECRETS]).
        bool("lastfm_enabled"), bool("lastfm_now_playing"), bool("lastfm_primary_artist_only"),
        bool("lastfm_scrobble_enabled"), bool("listenbrainz_enabled"), bool("listenbrainz_primary_artist_only"),
        float("scrobble_delay_percent"), int("scrobble_delay_seconds"), int("scrobble_min_duration"),
        // Discord's look (never the token).
        bool("discord_rpc_enabled"), bool("discord_use_details"), bool("discord_show_audio_quality"),
        bool("discord_advanced_mode"), text("discord_status"), text("discord_activity_type"),
        text("discord_activity_name"), text("discord_button_1_text"), bool("discord_button_1_visible"),
        text("discord_button_2_text"), bool("discord_button_2_visible"),
        // Lyrics sources, by name on the phone and by label here.
        lyricsSources("lyrics_source_order"), lyricsSources("lyrics_sources"), lyricsSources("lyrics_sources_seen"),
        // "Track length tolerance" and "Font" (ForkSourceMatching, ForkAppFont): the phone's keys, range and names.
        int(ForkUpgradeLength.KEY, allowed = ForkUpgradeLength.MIN_SECONDS..ForkUpgradeLength.MAX_SECONDS),
        named(ForkAppFont.KEY, ForkAppFont.KEY, ForkFont.entries.map { it.name }),
        // Renamed on the PC.
        named("audio_quality_wifi", "audio_quality", qualities),
        named("audio_quality_download", "download_quality", listOf("STANDARD", "HIGH", "LOSSLESS")),
        bool("smart_fade_enabled", "automix"),
        named("automix_performance_mode", "automix_performance", listOf("EFFICIENT", "BALANCED", "PERFORMANCE")),
        bool("shuffle_enabled", "shuffle"),
        bool("swipe_to_play_next", "swipe_play_next"),
        bool("prioritize_syllable_sync", DesktopLyricsClient.KEY_PRIORITIZE_SYLLABLES),
        repeat,
        searchHistory,
    )

    /** `bitchord_fork` on the phone (the phone's `ForkSettings`). */
    internal val FORK: List<Mapped> = listOf(
        bool("crossfade_on_skip"),
        onOff("backdrop_clips", "backdrop_videos"),
        onOff("animated_covers", "animated_covers"),
        onOff("navidrome_yt_history", "youtube_history"),
        bool("navidrome_scrobble", "report_plays", navidromeFork),
        bool("navidrome_home_shelves", "home_shelves", navidromeFork),
        int("clip_cache_mb", "clip_cache_mb", navidromeFork, allowed = ForkNavidromeSettings.CLIP_CACHE_CHOICES_MB),
        int("preload_songs", "preload_songs", navidromeFork, allowed = ForkNavidromeSettings.PRELOAD_CHOICES),
    )

    /** The phone's `AppSettings.SECRETS` and `DEVICE_LOCAL`: never carried either way. */
    private val SECRETS = setOf(
        "lastfm_session_key", "lastfm_api_key", "lastfm_secret", "listenbrainz_token", "spotify_spdc_token",
        "paxsenix_api_key", "downloaded_tracks", "downloaded_tracks_metadata", "downloaded_collections",
        "local_music_folder_uri", "last_version_code", "onboarding_complete", "offline_mode",
    )

    /** The phone's quality before it split into Wi-Fi and cellular; read when a backup has only it. */
    private const val PHONE_LEGACY_QUALITY = "audio_quality"

    // ── What only the PC has ────────────────────────────────────────────

    /** The PC's own keys that are this machine's, not its configuration, or are a login. */
    private val PC_DEVICE = setOf(
        "history", "queue", "queue_index", "downloads", "local_music_folder", "audio_output_device",
        "source_configs", "source_configs_dpapi_v1", "module_index_url", "presence_install_id",
        "google_accounts_v1", "active_google_account_v1", "active_google_profile_v1",
        "discord_token", "discord_username", "lastfm_api_key", "lastfm_api_secret", "lastfm_session_key",
        "lastfm_username", "listenbrainz_token", "spotify_spdc_token", "paxsenix_api_key", "paxsenix_api_key_protected",
        "audio_quality_rungs",
        // The welcome pages' flag is this machine's, as the phone's is (its DEVICE_LOCAL); see ForkWelcome.
        ForkWelcome.KEY,
        "playlist_sync_last_complete_check", // ForkPlaylistSync: this machine's last full check
    )

    /** [ForkWindowState]'s place on this machine's screens. */
    private const val PC_WINDOW_PREFIX = "fork_window_"

    private val PC_SECRET_PARTS = listOf(
        "token", "secret", "password", "cookie", "session", "credential", "api_key", "apikey", "auth",
        "account", "spdc", "protected", "dpapi", "listen_together",
    )

    private val CHUNK = Regex("""^(.*)\.\d+$""")

    /** Whether a raw key of the PC's main node is a PC-only setting a backup carries. */
    internal fun pcOnly(rawKey: String): Boolean {
        val key = CHUNK.matchEntire(rawKey)?.groupValues?.get(1) ?: rawKey
        if (key in PC_DEVICE || key.startsWith(PC_WINDOW_PREFIX) || MAIN.any { it.node === main && it.pcKey == key } || FORK.any { it.node === main && it.pcKey == key }) return false
        val lower = key.lowercase(Locale.ROOT)
        return PC_SECRET_PARTS.none { it in lower }
    }

    // ── Kept aside from the phone ───────────────────────────────────────

    /** What came from a backup that the PC doesn't use itself, to go back out on export. */
    @Serializable
    internal data class Carried(
        val settings: Map<String, PrefValue> = emptyMap(),
        val forkSettings: Map<String, PrefValue> = emptyMap(),
        /** The backup's listening as it came, so an untouched month goes back exactly. */
        val listening: List<StoredBucket> = emptyList(),
    )

    internal var carriedFile: () -> File = { File(ForkQueues.dataFolder, CARRIED_FILE) }

    private fun readCarried(): Carried = runCatching {
        val file = carriedFile()
        if (!file.isFile) Carried() else ForkBackupFormat.json.decodeFromString(Carried.serializer(), file.readText())
    }.getOrDefault(Carried())

    private fun writeCarried(carried: Carried) {
        val file = carriedFile()
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(ForkBackupFormat.json.encodeToString(Carried.serializer(), carried))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    // ── Export ──────────────────────────────────────────────────────────

    /** The months written; throws when [target] can't be written. */
    fun exportTo(target: Path): Int {
        val file = build()
        val text = ForkBackupFormat.json.encodeToString(
            JsonObject.serializer(),
            ForkBackupFormat.scrubbed(ForkBackupFormat.json.encodeToJsonElement(BackupFile.serializer(), file)) as JsonObject,
        )
        Files.writeString(target, text)
        return file.listening.size
    }

    internal fun build(): BackupFile {
        val carried = readCarried()
        val settings = LinkedHashMap<String, PrefValue>()
        MAIN.forEach { mapped -> read(mapped)?.let { settings[mapped.phoneKey] = it } }
        carried.settings.forEach { (key, value) -> if (key !in settings && key !in SECRETS) settings[key] = value }

        val forkSettings = LinkedHashMap<String, PrefValue>()
        FORK.forEach { mapped -> read(mapped)?.let { forkSettings[mapped.phoneKey] = it } }
        carried.forkSettings.forEach { (key, value) -> if (key !in forkSettings && key !in SECRETS) forkSettings[key] = value }

        val buckets = DesktopListeningStats.forkBuckets()
        val byMonth = carried.listening.associateBy { it.month }
        val listening = buckets.filter { it.month != DesktopListeningStats.LEGACY_MONTH }
            .sortedBy { it.month }
            .map { toPhone(it, byMonth[it.month]) }
        val legacy = buckets.firstOrNull { it.month == DesktopListeningStats.LEGACY_MONTH }

        val node = main()
        val pc = node.keys().filter(::pcOnly).sorted().mapNotNull { key -> node.get(key, null)?.let { key to JsonPrimitive(it) } }.toMap()
        val fork = buildJsonObject {
            put(KEY_FORK_SETTINGS, JsonObject(forkSettings.mapValues { ForkBackupFormat.json.encodeToJsonElement(it.value) }))
            put(KEY_QUEUES, ForkQueues.exportForBackup())
            put(KEY_PC, buildJsonObject {
                put(KEY_PC_SETTINGS, JsonObject(pc))
                if (legacy != null) put(KEY_PC_LEGACY, ForkBackupFormat.json.encodeToJsonElement(toPhone(legacy, null)))
            })
        }
        return BackupFile(
            versionName = DesktopUpdateChecker.currentVersion,
            exportedAt = Instant.now().toString(),
            settings = settings,
            listening = listening,
            fork = fork,
        )
    }

    private fun read(mapped: Mapped): PrefValue? {
        val stored = DesktopPreferenceChunks.read(mapped.node(), mapped.pcKey) ?: return null
        return mapped.toPhone(stored)?.let(PrefValue::of)
    }

    // ── Import ──────────────────────────────────────────────────────────

    data class Summary(val months: Int, val settings: Int, val from: String, val at: String)

    fun importFrom(source: Path): Summary {
        // A file-system failure's message is only the path; say what happened instead.
        val text = try {
            Files.readString(source)
        } catch (e: java.nio.file.NoSuchFileException) {
            error("That file isn't there any more")
        } catch (e: java.nio.file.AccessDeniedException) {
            error("BitChord isn't allowed to read that file")
        } catch (e: java.nio.charset.CharacterCodingException) {
            error("That doesn't look like a BitChord backup")
        }
        val file = runCatching { ForkBackupFormat.json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { error("That doesn't look like a BitChord backup") }
        require(file.app == ForkBackupFormat.APP_TAG) { "That backup is from another app" }
        require(file.version <= ForkBackupFormat.SCHEMA_VERSION) { "That backup was written by a newer version of BitChord" }
        apply(file)
        return Summary(file.listening.size, file.settings.size, file.versionName, file.exportedAt)
    }

    internal fun apply(file: BackupFile) {
        val incoming = file.settings.filterKeys { it !in SECRETS }.mapValues { it.value.decoded() }
        val legacyQuality = incoming[PHONE_LEGACY_QUALITY].takeIf { "audio_quality_wifi" !in incoming }
        MAIN.forEach { mapped ->
            val value = incoming[mapped.phoneKey] ?: legacyQuality.takeIf { mapped.phoneKey == "audio_quality_wifi" }
            write(mapped, value)
        }
        // The PC's quality reads its four rungs only with this set (DesktopPersistence.saveAudioQuality).
        main().putBoolean("audio_quality_rungs", true)

        val fork = file.fork
        // Each part of the fork's section on its own: one that doesn't read is skipped, not the import.
        fun <T> part(name: String, block: () -> T): T? = runCatching(block)
            .onFailure { DesktopTrackLog.log("backup import: skipped the fork section's $name (${it.javaClass.simpleName}: ${it.message})") }
            .getOrNull()
        val forkSettings = part("settings") {
            (fork?.get(KEY_FORK_SETTINGS) as? JsonObject)?.let { stored ->
                stored.mapNotNull { (key, element) ->
                    runCatching { ForkBackupFormat.json.decodeFromJsonElement<PrefValue>(element) }.getOrNull()?.let { key to it }
                }.toMap()
            }
        }
        // A backup without the fork's section (upstream's phone, an older build) leaves its settings alone.
        if (forkSettings != null) part("switches") { FORK.forEach { mapped -> write(mapped, forkSettings[mapped.phoneKey]?.decoded()) } }

        part("saved queues") {
            (fork?.get(KEY_QUEUES) as? JsonArray)?.let { queues ->
                ForkQueues.importFromBackup(ForkBackupFormat.queuesForThisDevice(queues, ::isFileHere))
            }
        }

        val pc = part("PC part") { fork?.get(KEY_PC) as? JsonObject }
        part("PC settings") {
            (pc?.get(KEY_PC_SETTINGS) as? JsonObject)?.let { stored ->
                val node = main()
                node.keys().filter(::pcOnly).forEach(node::remove)
                stored.forEach { (key, value) ->
                    val raw = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    if (pcOnly(key) && raw.length <= Preferences.MAX_VALUE_LENGTH && key.length <= Preferences.MAX_KEY_LENGTH) node.put(key, raw)
                }
            }
        }

        val legacy = part("listening before months") { (pc?.get(KEY_PC_LEGACY) as? JsonObject)?.let { ForkBackupFormat.json.decodeFromJsonElement<StoredBucket>(it) } }
        val buckets = file.listening.mapNotNull(::toPc) + listOfNotNull(legacy?.let { toPc(it, DesktopListeningStats.LEGACY_MONTH) })
        DesktopListeningStats.forkReplace(buckets)

        val mappedMain = MAIN.map { it.phoneKey }.toSet() + PHONE_LEGACY_QUALITY
        val mappedFork = FORK.map { it.phoneKey }.toSet()
        writeCarried(
            Carried(
                settings = file.settings.filterKeys { it !in mappedMain && it !in SECRETS },
                forkSettings = forkSettings.orEmpty().filterKeys { it !in mappedFork && it !in SECRETS },
                listening = file.listening,
            ),
        )
        // Restoring a backup is coming back to a BitChord already set up: no welcome pages after it
        // (the phone's file never carries its own flag, which is that phone's).
        ForkWelcome.markDone()
        runCatching { main().flush() }
        runCatching { navidromeFork().flush() }
    }

    private fun write(mapped: Mapped, value: Any?) {
        val node = mapped.node()
        val text = value?.let(mapped.toPc)
        DesktopPreferenceChunks.remove(node, mapped.pcKey)
        if (text == null) return // not in the backup: back to the PC's default, as the phone's import does
        if (text.length > Preferences.MAX_VALUE_LENGTH / 2) DesktopPreferenceChunks.write(node, mapped.pcKey, text) else node.put(mapped.pcKey, text)
    }

    private fun isFileHere(path: String): Boolean = runCatching {
        val file = if (path.startsWith("file:")) Path.of(java.net.URI(path)) else Path.of(path)
        Files.isRegularFile(file)
    }.getOrDefault(false)

    // ── Listening: the phone's month and the PC's ───────────────────────

    /** A phone month as the PC keeps it; null for a month that isn't one. */
    internal fun toPc(bucket: StoredBucket, month: String? = null): DesktopListeningStats.Bucket? {
        val key = month ?: runCatching { YearMonth.parse(bucket.month).toString() }.getOrNull() ?: return null
        val tracks = LinkedHashMap<String, DesktopReplayEntry>()
        // Each song's days, which "This week" is counted from (ForkReplayWeek); kept the way the PC's
        // store reads them back, so a month the PC never added to still compares equal on export.
        val trackDays = LinkedHashMap<String, MutableMap<String, ForkDayStats>>()
        bucket.tracks.forEach { track ->
            if (track.id.isBlank()) return@forEach
            val days = track.days.toSortedMap()
                .filterKeys { runCatching { LocalDate.parse(it) }.isSuccess }
                .mapValues { (_, stats) -> ForkDayStats(stats.ms.coerceAtLeast(0L), stats.plays.coerceAtLeast(0)) }
                .filterValues { it.ms > 0L || it.plays > 0 }
            if (days.isNotEmpty()) trackDays[track.id] = LinkedHashMap(days) else trackDays.remove(track.id)
            tracks[track.id] = DesktopReplayEntry(
                song = Song(
                    videoId = track.id,
                    title = track.title,
                    artist = track.artist,
                    thumbnailUrl = track.art,
                    artistId = track.artistId,
                    albumName = track.album,
                    albumId = track.albumId,
                ),
                listenedMs = track.ms.coerceAtLeast(0L),
                plays = track.plays.coerceAtLeast(0),
            )
        }
        val days = LinkedHashMap<String, Long>()
        val ym = runCatching { YearMonth.parse(key) }.getOrNull()
        if (ym != null) {
            bucket.days.toSortedMap().forEach { (day, ms) ->
                if (day in 1..ym.lengthOfMonth() && ms > 0L) days[ym.atDay(day).toString()] = ms
            }
        }
        return DesktopListeningStats.Bucket(
            month = key,
            tracks = tracks,
            hours = LongArray(24) { bucket.hours.getOrElse(it) { 0L }.coerceAtLeast(0L) },
            days = days,
            forkTrackDays = trackDays,
        )
    }

    /**
     * A PC month as the phone keeps it. A month that is still exactly what [carried] became on
     * import goes back as it came; one the PC has added to is rebuilt the way the phone's
     * `ListeningStats.record` files a play (lead artist, album by id), keeping what the carried copy
     * knew about each song (its days, its last play, its artist id).
     */
    internal fun toPhone(bucket: DesktopListeningStats.Bucket, carried: StoredBucket?): StoredBucket {
        if (carried != null) {
            val before = toPc(carried, bucket.month)
            if (before != null && fingerprint(before) == fingerprint(bucket)) return carried
        }
        val knownTracks = carried?.tracks?.associateBy { it.id }.orEmpty()
        val tracks = bucket.tracks.values.map { entry ->
            val song = entry.song
            val known = knownTracks[song.videoId]
            TrackEntry(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                albumId = song.albumId,
                artistId = known?.artistId ?: song.artistId,
                art = song.thumbnailUrl,
                ms = entry.listenedMs,
                plays = entry.plays,
                last = known?.last ?: 0L,
                // The PC's own days for the song (which include what came in on import), else the
                // carried copy's.
                days = bucket.forkTrackDays[song.videoId]?.takeIf { it.isNotEmpty() }
                    ?.toSortedMap()?.mapValues { (_, stats) -> DayStats(stats.ms, stats.plays) }
                    ?: known?.days.orEmpty(),
            )
        }
        val knownArtists = carried?.artists?.associateBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        val artists = tracks.groupBy { primaryArtist(it.artist)?.lowercase(Locale.ROOT) }
            .filterKeys { it != null }
            .map { (key, rows) ->
                val lead = rows.maxBy { it.ms }
                val name = primaryArtist(lead.artist)!!
                NameEntry(
                    name = name,
                    art = knownArtists[key]?.art ?: lead.art,
                    id = knownArtists[key]?.id ?: rows.firstNotNullOfOrNull { it.artistId },
                    ms = rows.sumOf { it.ms },
                    plays = rows.sumOf { it.plays },
                    key = key,
                    days = mergeDays(rows.map { it.days }),
                )
            }
        val albums = tracks.filter { !it.album.isNullOrBlank() }
            .groupBy { it.albumId ?: (it.album!!.trim().lowercase(Locale.ROOT) + '\u001F' + it.artist.lowercase(Locale.ROOT)) }
            .map { (key, rows) ->
                val lead = rows.maxBy { it.ms }
                NameEntry(
                    name = lead.album!!.trim(),
                    sub = lead.artist,
                    art = lead.art,
                    id = lead.albumId,
                    ms = rows.sumOf { it.ms },
                    plays = rows.sumOf { it.plays },
                    key = key,
                    days = mergeDays(rows.map { it.days }),
                )
            }
        val ym = runCatching { YearMonth.parse(bucket.month) }.getOrNull()
        val days = bucket.days.mapNotNull { (day, ms) ->
            val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@mapNotNull null
            if (ym == null || YearMonth.from(date) != ym) null else date.dayOfMonth to ms
        }.toMap()
        // The whole month's per-day figures: the PC keeps their time; their play counts are the songs'
        // own days added up, else as far as the carried copy knew them.
        val playsByDay = HashMap<String, Int>()
        bucket.forkTrackDays.values.forEach { days -> days.forEach { (day, stats) -> playsByDay[day] = (playsByDay[day] ?: 0) + stats.plays } }
        val perDay = bucket.days.toSortedMap().mapValues { (day, ms) -> DayStats(ms, playsByDay[day] ?: carried?.trackDays?.get(day)?.plays ?: 0) }
        return StoredBucket(
            month = bucket.month,
            tracks = tracks,
            artists = artists,
            albums = albums,
            hours = bucket.hours.toList(),
            days = days,
            trackDays = perDay,
            artistDays = perDay,
            albumDays = perDay,
        )
    }

    private fun fingerprint(bucket: DesktopListeningStats.Bucket) = listOf(
        bucket.tracks.mapValues { it.value.listenedMs to it.value.plays },
        bucket.hours.toList(),
        bucket.days.filterValues { it > 0L },
        bucket.forkTrackDays.mapValues { (_, days) -> days.filterValues { it.ms > 0L || it.plays > 0 } }.filterValues { it.isNotEmpty() },
    )

    private fun mergeDays(all: List<Map<String, DayStats>>): Map<String, DayStats> {
        val out = sortedMapOf<String, DayStats>()
        all.forEach { days -> days.forEach { (day, stats) -> out[day] = out[day]?.let { DayStats(it.ms + stats.ms, it.plays + stats.plays) } ?: stats } }
        return out
    }

    /** The phone's `ListeningStats.primaryArtist`. */
    private fun primaryArtist(credit: String): String? =
        credit.split(CREDIT_SEPARATORS).firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }

    private val CREDIT_SEPARATORS = Regex(
        """\s*,\s*|\s+&\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+""",
        RegexOption.IGNORE_CASE,
    )

    private const val CARRIED_FILE = "fork_backup_carried.json"
    private const val KEY_FORK_SETTINGS = "settings"
    private const val KEY_QUEUES = "savedQueues"
    private const val KEY_PC = "pc"
    private const val KEY_PC_SETTINGS = "settings"
    private const val KEY_PC_LEGACY = "listeningBeforeMonths"
}

/** Settings > Your data, the phone's group: Export data and Import data. */
@Composable
internal fun ForkBackupGroup() {
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<String?>(null) }
    SettingsGroup(DesktopStrings["your_data", "Your data"]) {
        ForkReplayGenresToggle() // the phone's "Work out genres", first in Your data as there
        SettingsRow(
            Icons.Rounded.FileUpload,
            DesktopStrings["export_data", "Export data"],
            exportStatus ?: DesktopStrings["export_data_subtitle", "Settings and listening history, as one JSON file"],
        ) {
            val target = pickFile(save = true) ?: return@SettingsRow
            scope.launch {
                exportStatus = withContext(Dispatchers.IO) {
                    runCatching { ForkBackup.exportTo(target) }.fold(
                        onSuccess = { "Exported settings and ${months(it)}" },
                        onFailure = { "Export failed: ${it.message ?: "unknown error"}" },
                    )
                }
            }
        }
        SettingsRow(
            Icons.Rounded.FileDownload,
            DesktopStrings["import_data", "Import data"],
            importStatus ?: DesktopStrings["import_data_subtitle", "Replaces the settings and history on this device"],
        ) { confirmImport = true }
    }
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text(DesktopStrings["import_backup_title", "Import a backup?"]) },
            text = {
                Text(
                    DesktopStrings[
                        "import_backup_warning",
                        "This replaces this device’s settings and listening history with the contents of the file. " +
                            "Export the current data first if you want to keep it.",
                    ] + " BitChord closes when it is done, so nothing open writes the old settings back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    val source = pickFile(save = false) ?: return@TextButton
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { ForkBackup.importFrom(source) } }.fold(
                            onSuccess = { imported = "Imported ${months(it.months)} from v${it.from}." },
                            onFailure = { importStatus = "Import failed: ${it.message ?: "unknown error"}" },
                        )
                    }
                }) { Text(DesktopStrings["choose_file", "Choose file"]) }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text(DesktopStrings["cancel", "Cancel"]) } },
        )
    }
    imported?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Backup imported") },
            text = { Text("$message BitChord closes now; open it again to use the imported settings.") },
            confirmButton = { TextButton(onClick = { DesktopTrayMenu.forkQuit() }) { Text("Close BitChord") } },
        )
    }
}

private fun months(count: Int) = if (count == 1) "1 month" else "$count months"

/** The system's own file dialog: where the backup goes, or which one to read. */
private fun pickFile(save: Boolean): Path? = ForkDebugBridge.takePick() ?: runCatching {
    val dialog = FileDialog(null as java.awt.Frame?, if (save) "Export data" else "Import data", if (save) FileDialog.SAVE else FileDialog.LOAD)
    dialog.directory = ForkPortable.userHome
    if (save) dialog.file = ForkBackup.suggestedName() else dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    Path.of(dialog.directory ?: ForkPortable.userHome, name)
}.getOrNull()
