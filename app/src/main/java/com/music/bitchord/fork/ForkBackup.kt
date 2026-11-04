package com.music.bitchord.fork

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.backup.ForkBackupFormat
import com.music.bitchord.data.stats.Backup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File

/**
 * Fork: the fork's section of the phone's backup (`Backup.BackupFile.fork`): the saved queues and
 * the fork's switches (`ForkSettings`, `bitchord_fork`), so they travel with Export / Import data
 * like upstream's settings do, and between the phone and the PC (`desktopApp/.../ForkBackup.kt`
 * reads and writes the same section). Upstream's builds ignore it.
 *
 * Nothing in it is a login: the Navidrome login lives in its own encrypted store and is never
 * read here, and every address goes through [ForkBackupFormat.scrubbed].
 */
object ForkBackup {

    private const val TAG = "BitChordFork"

    fun export(): JsonObject = runCatching {
        val settings = ForkSettings.exportForBackup().mapNotNull { (key, value) ->
            Backup.PrefValue.of(value)?.let { key to ForkBackupFormat.json.encodeToJsonElement(it) }
        }.toMap()
        ForkBackupFormat.scrubbed(section(carried(), JsonObject(settings), ForkQueues.exportForBackup())) as JsonObject
    }.getOrElse {
        Log.w(TAG, "backup: fork section left out: ${it.message}")
        JsonObject(emptyMap())
    }

    /**
     * Applies a backup's fork section; a backup without one (upstream's, an older build's) changes
     * nothing here. Runs after upstream's settings and listening are in, so a section this can't
     * read is logged and skipped rather than failing an import that has already been applied.
     * Anything but an object (an edited file) counts as no section, and leaves the parts carried
     * from the last section ([ForkSettings.carriedSections]) as they are, like the switches here.
     */
    fun import(section: JsonElement?) {
        val fork = section as? JsonObject ?: return
        runCatching { applySection(fork) }.onFailure { Log.w(TAG, "backup: fork section not read: ${it.message}") }
    }

    /** The parts of the last imported section this phone doesn't read (the PC's `pc` part), kept as they came. */
    private fun carried(): JsonObject = runCatching {
        ForkSettings.carriedSections?.let { ForkBackupFormat.json.parseToJsonElement(it) as? JsonObject }
    }.getOrNull() ?: JsonObject(emptyMap())

    /** The section an export writes: the carried parts first, so they can never stand in for the phone's own two. */
    internal fun section(carried: JsonObject, settings: JsonObject, queues: JsonElement): JsonObject = buildJsonObject {
        carried.forEach { (key, value) -> if (key != KEY_SETTINGS && key != KEY_QUEUES) put(key, value) }
        put(KEY_SETTINGS, settings)
        put(KEY_QUEUES, queues)
    }

    /** What of an imported [section] the phone keeps unread for the next export (null: nothing), scrubbed as it is stored. */
    internal fun carriedPart(section: JsonObject): JsonObject? =
        section.filterKeys { it != KEY_SETTINGS && it != KEY_QUEUES }.takeIf { it.isNotEmpty() }
            ?.let { ForkBackupFormat.scrubbed(JsonObject(it)) as JsonObject }

    private fun applySection(section: JsonObject) {
        // Before the parts below, so a part that fails to read doesn't lose the PC's settings too.
        ForkSettings.carriedSections = carriedPart(section)?.toString()
        (section[KEY_SETTINGS] as? JsonObject)?.let { stored ->
            val values = stored.mapNotNull { (key, element) ->
                runCatching { ForkBackupFormat.json.decodeFromJsonElement<Backup.PrefValue>(element).decoded() }
                    .getOrNull()?.let { key to it }
            }.toMap()
            ForkSettings.importFromBackup(values)
        }
        (section[KEY_QUEUES] as? JsonArray)?.let { queues ->
            // A song's file from another device (the PC's C:\ paths) is dropped; the phone's own
            // files, content:// documents and smb:// shares are kept (an SMB song's only address).
            ForkQueues.importFromBackup(
                ForkBackupFormat.queuesForThisDevice(queues) { path ->
                    keepsAddress(path) {
                        runCatching { File(android.net.Uri.parse(it).path ?: it).isFile }.getOrDefault(false)
                    }
                },
            )
        }
    }

    /**
     * Whether a saved queue's song address can be kept on this phone: a document or SMB share
     * address (the phone's own kinds, which the SMB client opens itself), or a file that is here.
     * Web addresses are kept before this is asked ([ForkBackupFormat.queuesForThisDevice]).
     */
    internal fun keepsAddress(path: String, isFile: (String) -> Boolean): Boolean =
        path.startsWith("content://") || path.startsWith("smb://") || isFile(path)

    const val KEY_SETTINGS = "settings"
    const val KEY_QUEUES = "savedQueues"
}
