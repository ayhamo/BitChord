package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SimpMusic's live reply shape (2026-11-20): `type`, and `durationSeconds` / `plainLyric` on each entry. */
class ForkSimpMusicTest {

    private fun reply(vararg entries: String, type: String = "success") =
        """{"type":"$type","data":[${entries.joinToString(",")}]}"""

    private val rich = """[00:01.00]<00:01.00>Hello <00:01.60>world <00:02.20>
[00:03.00]<00:03.00>Second <00:03.50>line <00:04.00>"""

    @Test
    fun wordSyncedFirst() {
        val body = reply(
            """{"durationSeconds":213,"richSyncLyrics":${quote(rich)},"syncedLyrics":"[00:01.00]Hello world","plainLyric":"Hello world"}""",
        )
        val lines = ForkSimpMusic.pick(body, 213_000)
        assertTrue(lines != null && lines.isNotEmpty() && lines.first().words.isNotEmpty(), "word-synced lines, got $lines")
    }

    @Test
    fun lineSyncedThenPlain() {
        val synced = ForkSimpMusic.pick(reply("""{"durationSeconds":200.4,"syncedLyrics":"[00:01.00]One\n[00:02.00]Two","plainLyric":"One\nTwo"}"""), 200_000)
        assertEquals(listOf(1000L, 2000L), synced?.map { it.timeMs })
        val plain = ForkSimpMusic.pick(reply("""{"durationSeconds":200,"plainLyric":"One\n\nTwo"}"""), 200_000)
        assertEquals(listOf("One", "Two"), plain?.map { it.text })
        assertTrue(plain!!.all { it.timeMs == 0L })
    }

    @Test
    fun theCutClosestInLength() {
        val body = reply(
            """{"durationSeconds":150,"syncedLyrics":"[00:01.00]Radio edit"}""",
            """{"durationSeconds":214,"syncedLyrics":"[00:01.00]Album cut"}""",
        )
        assertEquals("Album cut", ForkSimpMusic.pick(body, 213_000)?.single()?.text)
        assertNull(ForkSimpMusic.pick(reply("""{"durationSeconds":150,"syncedLyrics":"[00:01.00]Radio edit"}"""), 213_000))
    }

    @Test
    fun notASuccess() {
        assertNull(ForkSimpMusic.pick(reply("""{"durationSeconds":213,"syncedLyrics":"[00:01.00]x"}""", type = "error"), 213_000))
        assertNull(ForkSimpMusic.pick("""{"type":"success","data":[]}""", 213_000))
        assertNull(ForkSimpMusic.pick("not json", 213_000))
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n") + "\""
}
