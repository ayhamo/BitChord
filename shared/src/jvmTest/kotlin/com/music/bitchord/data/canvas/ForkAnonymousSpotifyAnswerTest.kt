package com.music.bitchord.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: an empty pathfinder answer is a failed lookup, not "no canvas" ([ForkAnonymousSpotifyCanvas]).
 * A throw is never saved by [ForkCanvasStore.cached]; a null is, as a 24 h miss.
 */
class ForkAnonymousSpotifyAnswerTest {

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun anAnswerWithNoDataThrows() {
        assertFailsWith<IOException> { ForkAnonymousSpotifyCanvas.trackCanvasOf(obj("{}")) }
        assertFailsWith<IOException> { ForkAnonymousSpotifyCanvas.trackCanvasOf(obj("""{"data":null}""")) }
        assertFailsWith<IOException> { ForkAnonymousSpotifyCanvas.trackCanvasOf(obj("""{"data":{}}""")) }
        assertFailsWith<IOException> { ForkAnonymousSpotifyCanvas.searchItems(obj("{}")) }
    }

    @Test
    fun aPresentTrackWithNoCanvasIsNull() {
        assertNull(ForkAnonymousSpotifyCanvas.trackCanvasOf(obj("""{"data":{"trackUnion":{"__typename":"Track","canvas":null}}}""")))
        assertNull(ForkAnonymousSpotifyCanvas.searchItems(obj("""{"data":{"searchV2":{}}}""")))
    }

    @Test
    fun aPresentCanvasIsRead() {
        val url = "https://canvaz.scdn.co/upload/artist/x/video/y.cnvs.mp4"
        assertEquals(url, ForkAnonymousSpotifyCanvas.trackCanvasOf(obj("""{"data":{"trackUnion":{"canvas":{"url":"$url"}}}}""")))
        assertEquals(0, ForkAnonymousSpotifyCanvas.searchItems(obj("""{"data":{"searchV2":{"topResultsV2":{"itemsV2":[]}}}}"""))?.size)
    }

    @Test
    fun aRedirectIsAFailedRequestAndLookupsDoNotQueueBehindOneLock() {
        val e = assertFailsWith<IOException> { ForkAnonymousSpotifyCanvas.checkStatus("canvas", 302) }
        assertEquals("canvas: HTTP 302", e.message)
        assertFailsWith<IOException> { ForkAnonymousSpotifyCanvas.checkStatus("canvas", 404) }
        ForkAnonymousSpotifyCanvas.checkStatus("canvas", 200)
        val canvasUrl = ForkAnonymousSpotifyCanvas::class.java.methods.first { it.name == "canvasUrl" }
        assertFalse(java.lang.reflect.Modifier.isSynchronized(canvasUrl.modifiers))
    }

    /** Canvas.md §4 rule 5 (audit 2026-11-26): only a non-empty `errors` list fails an answer. */
    @Test
    fun onlyANonEmptyErrorsListIsAFailure() {
        assertFalse(ForkAnonymousSpotifyCanvas.hasErrors(obj("""{"data":{}}""")))
        assertFalse(ForkAnonymousSpotifyCanvas.hasErrors(obj("""{"data":{},"errors":[]}""")))
        assertFalse(ForkAnonymousSpotifyCanvas.hasErrors(obj("""{"data":{},"errors":null}""")))
        assertTrue(ForkAnonymousSpotifyCanvas.hasErrors(obj("""{"errors":[{"message":"x"}]}""")))
    }
}
