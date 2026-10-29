package com.music.bitchord

import com.music.bitchord.data.navidrome.NavidromeClient
import com.music.bitchord.data.navidrome.NavidromeLyrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fork: `getLyricsBySongId` answers turned into player lines. The library used
 * for testing on the device has no lyrics in its files, so this is the only
 * place the conversion runs on known input.
 */
class NavidromeLyricsTest {

    private fun rows(body: String) = NavidromeClient.parseLyrics(Json.parseToJsonElement(body).jsonObject)

    @Test
    fun takesTheTimedEntryOverPlainText() {
        val lines = NavidromeLyrics.linesFrom(
            rows(
                """
                {"status":"ok","lyricsList":{"structuredLyrics":[
                  {"lang":"xxx","synced":false,"line":[{"value":"plain"}]},
                  {"lang":"eng","synced":true,"offset":0,"line":[
                    {"start":1000,"value":"First line"},
                    {"start":4500,"value":"Second line"}
                  ]}
                ]}}
                """,
            ),
        )!!
        val sung = lines.filter { it.text.isNotEmpty() }
        assertEquals(listOf("First line", "Second line"), sung.map { it.text })
        assertEquals(listOf(1000L, 4500L), sung.map { it.timeMs })
    }

    @Test
    fun positiveOffsetShowsLinesSooner() {
        val lines = NavidromeLyrics.linesFrom(
            rows(
                """
                {"lyricsList":{"structuredLyrics":{"synced":true,"offset":300,"line":[
                  {"start":200,"value":"Clamped"},{"start":61000,"value":"A minute in"}
                ]}}}
                """,
            ),
        )!!
        assertEquals(listOf(0L, 60_700L), lines.map { it.timeMs })
    }

    @Test
    fun wordStampsInTheFileSurvive() {
        val lines = NavidromeLyrics.linesFrom(
            rows(
                """
                {"lyricsList":{"structuredLyrics":[{"synced":true,"line":[
                  {"start":27390,"value":"<00:27.39>I <00:27.54>been <00:27.74>tryna"}
                ]}]}}
                """,
            ),
        )!!
        val line = lines.single { it.text.isNotEmpty() }
        assertEquals("I been tryna", line.text)
        assertTrue(line.isWordSynced)
    }

    @Test
    fun plainTextOrNothingIsNull() {
        assertNull(NavidromeLyrics.linesFrom(rows("""{"lyricsList":{}}""")))
        assertNull(
            NavidromeLyrics.linesFrom(
                rows("""{"lyricsList":{"structuredLyrics":[{"synced":false,"line":[{"value":"plain"}]}]}}"""),
            ),
        )
    }
}
