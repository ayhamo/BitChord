package com.music.bitchord

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fork: keys upstream added to `values/strings.xml` without translating them yet (upstream 141372f6,
 * v1.9.1: Automix overview, the Diwali greeting). [LocaleStringsTest] failed on them in every
 * language, 17 failures on every run, so a new real failure didn't stand out. Translating them here
 * would mean editing upstream's translation files and a conflict at each merge; they are skipped
 * in that test instead, only these keys.
 *
 * [nothing listed is translated yet] fails once upstream has translated one of them everywhere, so
 * the list is cut down rather than left hiding a key that no longer needs it.
 */
object ForkLocaleGaps {
    val upstreamUntranslated = setOf("automix_overview", "diwali_title", "diwali_subtitle", "diwali_cta")
}

class ForkLocaleGapsTest {

    @Test
    fun `nothing listed is translated yet`() {
        val res = listOf(File("app/src/main/res"), File("src/main/res")).first { it.exists() }
        val locales = res.listFiles().orEmpty().filter { it.name.startsWith("values-") && File(it, "strings.xml").exists() }
        val translatedEverywhere = ForkLocaleGaps.upstreamUntranslated.filter { key ->
            locales.isNotEmpty() && locales.all { dir ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml")).getElementsByTagName("string")
                (0 until nodes.length).any { (nodes.item(it) as Element).getAttribute("name") == key }
            }
        }
        assertTrue("translated upstream now, take them out of ForkLocaleGaps: $translatedEverywhere", translatedEverywhere.isEmpty())
    }
}
