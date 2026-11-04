package com.music.bitchord.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Fork: the phone's backup file (`app/.../data/stats/Backup.kt`, `BackupFile`, `PrefValue`,
 * `StoredBucket` in `ListeningStats.kt`), field for field, so the PC reads and writes the same
 * JSON. The phone's classes stay where upstream keeps them; `ForkBackupFormatTest` (app) checks
 * that a file the phone's serializer writes decodes here and comes back the same.
 *
 * [BackupFile.fork] is this fork's own section (saved queues, the fork's switches, and on the PC
 * its own settings). An upstream build ignores it (`ignoreUnknownKeys`).
 */
object ForkBackupFormat {

    /** The phone's `Backup.json`. */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    const val APP_TAG = "bitchord"
    const val SCHEMA_VERSION = 1

    @Serializable
    data class BackupFile(
        val app: String = APP_TAG,
        val version: Int = SCHEMA_VERSION,
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, PrefValue> = emptyMap(),
        val listening: List<StoredBucket> = emptyList(),
        val fork: JsonObject? = null,
    )

    /** The phone's `Backup.PrefValue`: a preference with the type it is restored as. */
    @Serializable
    data class PrefValue(
        val type: String,
        val value: String? = null,
        val values: List<String> = emptyList(),
    ) {
        fun decoded(): Any? = when (type) {
            BOOLEAN -> value?.toBooleanStrictOrNull()
            INT -> value?.toIntOrNull()
            LONG -> value?.toLongOrNull()
            FLOAT -> value?.toFloatOrNull()
            STRING -> value
            STRING_SET -> values.toSet()
            else -> null
        }

        companion object {
            fun of(value: Any?): PrefValue? = when (value) {
                is Boolean -> PrefValue(BOOLEAN, value.toString())
                is Int -> PrefValue(INT, value.toString())
                is Long -> PrefValue(LONG, value.toString())
                is Float -> PrefValue(FLOAT, value.toString())
                is String -> PrefValue(STRING, value)
                is Set<*> -> PrefValue(STRING_SET, values = value.filterIsInstance<String>())
                else -> null
            }

            const val BOOLEAN = "bool"
            const val INT = "int"
            const val LONG = "long"
            const val FLOAT = "float"
            const val STRING = "string"
            const val STRING_SET = "stringSet"
        }
    }

    @Serializable
    data class DayStats(
        val ms: Long = 0L,
        val plays: Int = 0,
    )

    @Serializable
    data class TrackEntry(
        val id: String,
        val title: String = "",
        val artist: String = "",
        val album: String? = null,
        val albumId: String? = null,
        val artistId: String? = null,
        val art: String? = null,
        val ms: Long = 0L,
        val plays: Int = 0,
        val last: Long = 0L,
        val days: Map<String, DayStats> = emptyMap(),
    )

    @Serializable
    data class NameEntry(
        val name: String = "",
        val sub: String? = null,
        val art: String? = null,
        val id: String? = null,
        val ms: Long = 0L,
        val plays: Int = 0,
        val key: String? = null,
        val days: Map<String, DayStats> = emptyMap(),
    )

    @Serializable
    data class StoredBucket(
        val version: Int = 1,
        val month: String,
        val tracks: List<TrackEntry> = emptyList(),
        val artists: List<NameEntry> = emptyList(),
        val albums: List<NameEntry> = emptyList(),
        val hours: List<Long> = List(24) { 0L },
        val days: Map<Int, Long> = emptyMap(),
        val trackDays: Map<String, DayStats>? = null,
        val artistDays: Map<String, DayStats>? = null,
        val albumDays: Map<String, DayStats>? = null,
    )

    /**
     * [element] with every Subsonic login taken out of the addresses in it: `u`, `t`, `s` and `p`
     * on a `/rest/` URL (a Navidrome cover or stream). A backup is a file people copy around, so
     * this runs over the whole document rather than trusting each writer to have done it.
     */
    fun scrubbed(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { scrubbed(it.value) })
        is JsonArray -> JsonArray(element.map(::scrubbed))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(scrubUrl(element.content)) else element
    }

    /** One address with its Subsonic login removed; anything else comes back as it was. */
    fun scrubUrl(value: String): String {
        if ("/rest/" !in value) return value
        val url = value.toHttpUrlOrNull() ?: return value
        if (LOGIN_PARAMS.none { url.queryParameter(it) != null }) return value
        return url.newBuilder().apply { LOGIN_PARAMS.forEach { removeAllQueryParameters(it) } }.build().toString()
    }

    private val LOGIN_PARAMS = listOf("u", "t", "s", "p")

    /**
     * Saved queues (the `fork_queues.json` shape) ready for this device: a song's `localUri` and
     * `localPath` stay only when they are web addresses or [keep] says the file is here. A path
     * from the other device is a song that won't open; without it the song plays from its source.
     */
    fun queuesForThisDevice(queues: JsonArray, keep: (String) -> Boolean): JsonArray = JsonArray(
        queues.map { queue ->
            val o = queue as? JsonObject ?: return@map queue
            val songs = o["songs"] as? JsonArray ?: return@map queue
            JsonObject(
                o + ("songs" to JsonArray(
                    songs.map { song ->
                        val s = song as? JsonObject ?: return@map song
                        JsonObject(
                            s.filter { (key, value) ->
                                if (key != "localUri" && key != "localPath") return@filter true
                                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@filter false
                                text.startsWith("http://") || text.startsWith("https://") || keep(text)
                            },
                        )
                    },
                )),
            )
        },
    )
}
