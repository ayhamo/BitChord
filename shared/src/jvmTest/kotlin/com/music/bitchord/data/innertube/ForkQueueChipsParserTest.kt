package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: YouTube Music's queue chips (ForkQueueChipsParser). The shape is a
 * signed-out WEB_REMIX `next` answer for `RDAMVM<videoId>` (2026-11-06),
 * trimmed to the queue renderer and four of its twelve chips.
 */
class ForkQueueChipsParserTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun chip(text: String, playlistId: String?, params: String, selected: Boolean, playerParams: String? = null) = """
        { "chipCloudChipRenderer": {
          "text": { "runs": [ { "text": "$text" } ] },
          "navigationEndpoint": {
            "clickTrackingParams": "CCQQ_V0YACIT",
            "queueUpdateCommand": {
              "queueUpdateSection": "QUEUE_UPDATE_SECTION_QUEUE",
              "fetchContentsCommand": {
                "clickTrackingParams": "CCQQ_V0YACIT",
                "watchEndpoint": {
                  ${playlistId?.let { "\"playlistId\": \"$it\"," } ?: ""}
                  "params": "$params",
                  ${playerParams?.let { "\"playerParams\": \"$it\"," } ?: ""}
                  "loggingContext": { "vssLoggingContext": { "serializedContextData": "GhFSREFN" } }
                }
              },
              "dedupeAgainstLocalQueue": true,
              "syncMode": "QUEUE_UPDATE_SYNC_MODE_DEDUPE_AGAINST_LOCAL"
            }
          },
          "accessibilityData": { "accessibilityData": { "label": "$text" } },
          "isSelected": $selected,
          "uniqueId": "$text"
        } }"""

    private fun answer(chips: String) = json(
        """
        { "contents": { "singleColumnMusicWatchNextResultsRenderer": { "tabbedRenderer": {
          "watchNextTabbedResultsRenderer": { "tabs": [ { "tabRenderer": { "content": {
            "musicQueueRenderer": {
              "content": { "playlistPanelRenderer": { "contents": [
                { "playlistPanelVideoRenderer": { "videoId": "dQw4w9WgXcQ", "title": { "runs": [ { "text": "Never Gonna Give You Up" } ] } } }
              ] } },
              "header": { "musicQueueHeaderRenderer": { "title": { "runs": [ { "text": "Up next" } ] } } },
              "subHeaderChipCloud": { "chipCloudRenderer": { "chips": [ $chips ] } }
            }
          } } } ] }
        } } } }
        """,
    )

    @Test
    fun readsEveryChipWithItsQueueEndpoint() {
        val chips = ForkQueueChipsParser.parse(
            answer(
                listOf(
                    chip("All", "RDAMVMdQw4w9WgXcQ", "ggUMRWdBWUFRJTNEJTNE", selected = true),
                    chip("Popular", "RDATpYvdQw4w9WgXcQ", "ggU4Q2hKU1JF", selected = false),
                    chip("Deep cuts", "RDATpXvdQw4w9WgXcQ", "ggU0Q2hKU1JF", selected = false),
                    chip("Pop", "RDATg9XvdQw4w9WgXcQ", "ggU0Q2hOU1JF", selected = false, playerParams = "0gcJCW0At9Zurpe2"),
                ).joinToString(","),
            ),
        )
        assertEquals(listOf("All", "Popular", "Deep cuts", "Pop"), chips.map { it.title })
        assertEquals(listOf(true, false, false, false), chips.map { it.selected })
        assertEquals("RDATpXvdQw4w9WgXcQ", chips[2].playlistId)
        assertEquals("ggU0Q2hKU1JF", chips[2].params)
        assertNull(chips[1].playerParams)
        assertEquals("0gcJCW0At9Zurpe2", chips[3].playerParams)
        assertTrue(chips[0].isAll)
        assertFalse(chips[1].isAll)
    }

    @Test
    fun aChipWithoutAPlaylistIsLeftOut() {
        val chips = ForkQueueChipsParser.parse(
            answer(
                listOf(
                    chip("All", "RDAMVMx", "p", selected = true),
                    chip("Broken", null, "p", selected = false),
                ).joinToString(","),
            ),
        )
        assertEquals(listOf("All"), chips.map { it.title })
    }

    @Test
    fun aChipsOwnQueueHasNoChips() {
        // What a chip's fetch answers: the queue alone, no chip cloud.
        val root = json(
            """
            { "contents": { "singleColumnMusicWatchNextResultsRenderer": { "tabbedRenderer": {
              "watchNextTabbedResultsRenderer": { "tabs": [ { "tabRenderer": { "content": {
                "musicQueueRenderer": { "content": { "playlistPanelRenderer": { "contents": [] } } }
              } } } ] }
            } } } }
            """,
        )
        assertTrue(ForkQueueChipsParser.parse(root).isEmpty())
        assertTrue(ForkQueueChipsParser.parse(json("{}")).isEmpty())
    }
}
