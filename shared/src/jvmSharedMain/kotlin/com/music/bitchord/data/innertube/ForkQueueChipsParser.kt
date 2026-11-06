package com.music.bitchord.data.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One of YouTube Music's own queue chips: "All", "Popular", "Discover",
 * "Deep cuts", then the seed song's moods, decades and genres.
 *
 * [playlistId] and [params] are sent back to `next` exactly as the chip gave
 * them; that request answers with the chip's queue (an `RDAT…` mix for every
 * chip but "All", whose id is the radio's own `RDAMVM<videoId>`). [playerParams]
 * rides along on the genre chips only and is kept for completeness: it
 * describes how to play, not what to queue, so the queue request leaves it out.
 */
data class QueueChip(
    val title: String,
    val playlistId: String,
    val params: String?,
    val playerParams: String? = null,
    val selected: Boolean = false,
) {
    /**
     * The chip that is the plain radio. Told apart by its playlist id rather
     * than its label ("All" is translated with the account's language) or its
     * `uniqueId` (not documented, and only seen in English answers).
     */
    val isAll: Boolean get() = playlistId.startsWith("RDAMVM")
}

/**
 * Fork: reads the queue chips out of a radio `next` answer.
 *
 * The chips sit at
 * `musicQueueRenderer.subHeaderChipCloud.chipCloudRenderer.chips[].chipCloudChipRenderer`,
 * each with its fetch at
 * `navigationEndpoint.queueUpdateCommand.fetchContentsCommand.watchEndpoint`
 * (checked against a signed-out WEB_REMIX answer, 2026-11-06). Looked for
 * anywhere under the first `musicQueueRenderer` rather than along the full
 * tabbed path, which YouTube has reshuffled before without touching the queue
 * renderer itself. A chip without a playlist id can't be fetched and is left
 * out; an answer with no chip cloud (every chip's own queue, a playlist's
 * queue) gives an empty list.
 */
object ForkQueueChipsParser {

    fun parse(root: JsonElement): List<QueueChip> {
        val queue = findFirst(root, "musicQueueRenderer") ?: return emptyList()
        val cloud = findFirst(queue, "subHeaderChipCloud") as? JsonObject ?: return emptyList()
        val chips = (cloud["chipCloudRenderer"] as? JsonObject)?.get("chips") as? JsonArray ?: return emptyList()
        return chips.mapNotNull { element ->
            val chip = (element as? JsonObject)?.get("chipCloudChipRenderer") as? JsonObject ?: return@mapNotNull null
            val title = text(chip["text"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val watch = chip.path("navigationEndpoint", "queueUpdateCommand", "fetchContentsCommand", "watchEndpoint")
                ?: return@mapNotNull null
            val playlistId = watch.string("playlistId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            QueueChip(
                title = title,
                playlistId = playlistId,
                params = watch.string("params"),
                playerParams = watch.string("playerParams"),
                selected = (chip["isSelected"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }

    private fun text(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        obj.string("simpleText")?.let { return it }
        val runs = obj["runs"] as? JsonArray ?: return null
        return runs.joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.path(vararg keys: String): JsonObject? {
        var at: JsonObject = this
        for (key in keys) at = at[key] as? JsonObject ?: return null
        return at
    }

    private fun findFirst(element: JsonElement, key: String): JsonElement? = when (element) {
        is JsonObject -> element[key] ?: element.values.firstNotNullOfOrNull { findFirst(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findFirst(it, key) }
        else -> null
    }
}
