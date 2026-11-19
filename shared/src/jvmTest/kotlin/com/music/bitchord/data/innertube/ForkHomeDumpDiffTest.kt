package com.music.bitchord.data.innertube

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fork: the red/green check of [ForkHomeFeedParser] against a real dump of the web Home
 * ([ForkHomeDump]; on the PC, the debug bridge's `/homedump`). Runs over `FORKHOME_DUMP` or the repo's
 * `out/forkhome-dump` (git-excluded) when one is there, and passes with nothing to check otherwise
 * (CI has no dump).
 */
class ForkHomeDumpDiffTest {
    /** The check itself: the fixture page is green, the same page with a section the parser can't read is red. */
    @Test
    fun diffTurnsRedOnASkippedSection() {
        // Without its taste-builder prompt, which the parser skips on purpose (ForkHomeFeedParserTest): the app has no page for it.
        val page = kotlinx.serialization.json.Json.parseToJsonElement(javaClass.getResource("/forkhome/home_first_page.json")!!.readText()).toString()
            .replace(Regex(""",\{"musicTastebuilderShelfRenderer":\{"primaryText":\{"runs":\[\{"text":"[^"]*"}]}}}"""), "")
            .also { check("musicTastebuilderShelfRenderer" !in it) }
        val dir = kotlin.io.path.createTempDirectory("forkhome").toFile()
        try {
            File(dir, "home-0.json").writeText(page)
            assertTrue(ForkHomeDump.diff(dir).startsWith("GREEN"), ForkHomeDump.diff(dir))
            val unknown = """{"musicSomethingNewRenderer":{"header":{"x":{"title":{"runs":[{"text":"Brand new shelf"}]}}}}},"""
            File(dir, "home-0.json").writeText(page.replaceFirst("\"contents\":[{", "\"contents\":[$unknown{").also { check(it != page) })
            val red = ForkHomeDump.diff(dir)
            assertTrue(red.startsWith("RED") && "MISSING" in red && "Brand new shelf" in red, red)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parserMatchesTheDump() {
        val dir = System.getenv("FORKHOME_DUMP")?.let(::File)
            ?: listOf("../out/forkhome-dump", "out/forkhome-dump").map(::File).firstOrNull { it.isDirectory }
            ?: return
        if (dir.listFiles { f -> f.name.startsWith("home-0") }.isNullOrEmpty()) return
        val report = ForkHomeDump.diff(dir)
        println(report)
        assertTrue(report.startsWith("GREEN"), report)
    }
}
