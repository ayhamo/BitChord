package com.music.bitchord.data.innertube

import com.music.bitchord.data.model.HomeShelf
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Fork: a dump of YouTube Music's web Home (the WEB_REMIX feed music.youtube.com shows) for the
 * signed-in account, and a red/green check of [ForkHomeFeedParser] against it (owner, 2026-11-19:
 * YouTube's part of Home is 1:1 with the website after Find your mood).
 *
 * [dump] writes the raw browse JSON of `FEmusic_home`, every continuation and each chip's Home
 * (`home-<page>.json`, `chip-<n>-<page>.json`, with `responseContext` left out, so no visitor id),
 * plus `home-layout.json`, a summary per section. [diff] reads such a folder and lists, page by page,
 * every section YouTube sent against the shelf the parser made of it: missing, renamed or moved
 * shelves, and header buttons (Play all / More) lost. On the PC it runs from the debug bridge
 * (`/homedump`); `ForkHomeDumpDiffTest` runs [diff] over `out/forkhome-dump` when that folder exists.
 */
object ForkHomeDump {

    private const val MAX_PAGES = 25
    private const val MAX_CHIP_PAGES = 6
    private val pretty = Json { prettyPrint = true }

    /** Fetches and writes the dump into [dir] (emptied of an older dump first); returns [diff]'s report. */
    suspend fun dump(dir: File): String {
        dir.mkdirs()
        dir.listFiles { f -> f.name.matches(Regex("(home|chip)-.*\\.json|home-layout\\.json|diff\\.txt")) }?.forEach { it.delete() }
        val first = Innertube.browse("FEmusic_home")
        val home = pages("home", first, MAX_PAGES, dir)
        val chips = ForkHomeFeedParser.chips(listOf(first).sectionList()?.get("header"))
        chips.forEachIndexed { n, chip ->
            runCatching { pages("chip-$n", Innertube.browse("FEmusic_home", chip.params), MAX_CHIP_PAGES, dir) }
        }
        File(dir, "home-layout.json").writeText(pretty.encodeToString(JsonObject.serializer(), layout(first, home)))
        return diff(dir).also { File(dir, "diff.txt").writeText(it) }
    }

    private suspend fun pages(prefix: String, first: JsonObject, max: Int, dir: File): List<JsonObject> {
        val out = mutableListOf(first)
        var token = InnertubeParser.continuationToken(first)
        while (token != null && out.size < max) {
            val next = Innertube.browseContinuation(token)
            out += next
            token = InnertubeParser.continuationToken(next)
        }
        out.forEachIndexed { i, page -> File(dir, "$prefix-$i.json").writeText(JsonObject(page - "responseContext").toString()) }
        return out
    }

    // ---- home-layout.json ---------------------------------------------------

    private fun layout(first: JsonObject, pages: List<JsonObject>): JsonObject = buildJsonObject {
        val tracking = first.o("responseContext").a("serviceTrackingParams").orEmpty()
            .flatMap { it.a("params").orEmpty() }.associate { it.s("key") to it.s("value") }
        put("client", tracking["c"] ?: "WEB_REMIX")
        put("clientVersion", tracking["cver"])
        put("loggedIn", tracking["logged_in"])
        putJsonArray("chips") {
            ForkHomeFeedParser.chips(listOf(first).sectionList()?.get("header")).forEach { chip ->
                addJsonObject {
                    put("text", chip.title); put("params", chip.params)
                    put("isSelected", chip.selected); put("deselectParams", chip.deselectParams)
                }
            }
        }
        putJsonArray("sections") {
            var index = 0
            pages.forEachIndexed { page, response ->
                sectionsOf(response, page).forEach { section ->
                    val kind = section.keys.first()
                    val body = section.o(kind)
                    val header = body.o("header")?.values?.firstOrNull() as? JsonObject
                    addJsonObject {
                        put("index", index++); put("page", page); put("renderer", kind)
                        put("title", titleOf(section)); put("strapline", header.o("strapline").runs())
                        put("numItemsPerColumn", body.s("numItemsPerColumn")); put("itemSize", body.s("itemSize"))
                        put("headerThumbnail", header.o("thumbnail") != null)
                        buttonsOf(section).forEach { (where, button) ->
                            putJsonObject(where) {
                                put("text", button.text); put("endpoint", button.endpoint)
                                put("browseId", button.browseId); put("pageType", button.pageType)
                            }
                        }
                        putJsonArray("items") {
                            itemsOf(body).forEach { item ->
                                val r = item.keys.firstOrNull() ?: return@forEach
                                val ir = item.o(r)
                                val nav = ir.o("navigationEndpoint") ?: ir.o("onTap") ?: ir.o("clickCommand")
                                val endpoint = nav?.keys?.firstOrNull { it.endsWith("Endpoint") }
                                addJsonObject {
                                    put("renderer", r)
                                    put("title", ir.o("title").runs().ifEmpty { ir.a("flexColumns")?.firstOrNull().o("musicResponsiveListItemFlexColumnRenderer").o("text").runs() }.ifEmpty { ir.o("buttonText").runs() })
                                    put("endpoint", endpoint)
                                    put("pageType", (findFirst(nav, "pageType") as? JsonPrimitive)?.contentOrNull)
                                    put("aspectRatio", ir.s("aspectRatio"))
                                    put("musicVideoType", (findFirst(ir, "musicVideoType") as? JsonPrimitive)?.contentOrNull)
                                    put("explicit", findAll(ir, "musicInlineBadgeRenderer").any { b -> b.o("icon").s("iconType") == "MUSIC_EXPLICIT_BADGE" })
                                    put("displayStyle", ir.s("displayStyle") ?: ir.s("itemHeight"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- The diff -------------------------------------------------------------

    /** Reads a dump folder and compares YouTube's sections with [ForkHomeFeedParser]'s shelves. */
    fun diff(dir: File): String {
        val report = StringBuilder()
        var problems = 0
        val groups = dir.listFiles { f -> f.name.matches(Regex("(home|chip-\\d+)-\\d+\\.json")) }.orEmpty()
            .groupBy { it.name.substringBeforeLast('-') }
            .toSortedMap(compareBy<String> { if (it == "home") -1 else it.removePrefix("chip-").toInt() })
        if (groups.isEmpty()) return "no dump in $dir"
        for ((name, files) in groups) {
            val pages = files.sortedBy { it.name.substringAfterLast('-').removeSuffix(".json").toInt() }
                .map { Json.parseToJsonElement(it.readText()) as JsonObject }
            report.appendLine("== $name (${pages.size} page(s))")
            val expected = pages.flatMapIndexed { i, page -> sectionsOf(page, i).map { Expected(i, it) } }
            val actual = pages.flatMapIndexed { i, page ->
                (if (i == 0) ForkHomeFeedParser.parse(page) else ForkHomeFeedParser.parseContinuation(page)).shelves
            }
            var a = 0
            expected.forEachIndexed { n, want ->
                val shelf = actual.getOrNull(a)
                val wantButtons = buttonsOf(want.section).map { it.second }
                val line = "#$n p${want.page} ${want.kind} \"${want.title}\" ${wantButtons.joinToString { "[${it.text}:${it.endpoint}]" }}"
                if (shelf == null || shelf.title != want.title) {
                    problems++
                    report.appendLine("  MISSING  $line" + (shelf?.let { "  (next shelf: \"${it.title}\")" } ?: ""))
                    return@forEachIndexed
                }
                a++
                val lost = wantButtons.filterNot { hasButton(shelf, it) }
                val sent = itemsOf(want.section.o(want.kind)).size
                val kept = shelf.items.size
                when {
                    lost.isNotEmpty() -> { problems++; report.appendLine("  BUTTONS  $line  -> lost ${lost.joinToString { it.text }}") }
                    kept < sent -> { problems++; report.appendLine("  ITEMS    $line  -> kept $kept of $sent") }
                    else -> report.appendLine("  ok       $line  -> ${shelf.fork?.kind}, $kept items")
                }
            }
            actual.drop(a).forEach { problems++; report.appendLine("  EXTRA    \"${it.title}\"") }
        }
        report.insert(0, if (problems == 0) "GREEN: every section matches\n" else "RED: $problems problem(s)\n")
        return report.toString()
    }

    private class Expected(val page: Int, val section: JsonObject) {
        val kind: String = section.keys.first()
        val title: String = titleOf(section)
    }

    private class Button(val text: String, val endpoint: String?, val browseId: String?, val pageType: String?)

    /** A header button as the parser should keep it: a watch endpoint is Play all, a browse one the heading's More. */
    private fun hasButton(shelf: HomeShelf, b: Button): Boolean = when (b.endpoint) {
        "watchEndpoint", "watchPlaylistEndpoint" -> shelf.fork?.playAll != null
        "browseEndpoint" -> shelf.moreBrowseId == b.browseId
        else -> true
    }

    private fun buttonsOf(section: JsonObject): List<Pair<String, Button>> {
        val body = section.o(section.keys.first())
        val header = body.o("header")?.values?.firstOrNull() as? JsonObject
        return listOfNotNull(
            header.o("moreContentButton").o("buttonRenderer")?.let { "moreContentButton" to button(it) },
            body.o("bottomButton").o("buttonRenderer")?.let { "bottomButton" to button(it) },
        )
    }

    private fun button(b: JsonObject): Button {
        val nav = b.o("navigationEndpoint")
        val endpoint = nav?.keys?.firstOrNull { it.endsWith("Endpoint") }
        return Button(
            text = b.o("text").runs(),
            endpoint = endpoint,
            browseId = nav.o("browseEndpoint").s("browseId"),
            pageType = (findFirst(nav, "pageType") as? JsonPrimitive)?.contentOrNull,
        )
    }

    /** The content sections of a page, without the page's own continuation slot. */
    private fun sectionsOf(page: JsonObject, index: Int): List<JsonObject> {
        val list = if (index == 0) listOf(page).sectionList()?.a("contents")
        else (findFirst(page, "sectionListContinuation") as? JsonObject)?.a("contents")
            ?: (findFirst(page, "appendContinuationItemsAction") as? JsonObject)?.a("continuationItems")
        return list.orEmpty().filterIsInstance<JsonObject>()
            .filter { it.keys.firstOrNull() != null && it.keys.first() != "continuationItemRenderer" }
    }

    private fun itemsOf(body: JsonObject?): List<JsonObject> =
        ((body?.get("contents") ?: body?.get("items")) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun titleOf(section: JsonObject): String {
        val body = section.o(section.keys.first())
        val header = body.o("header")?.values?.firstOrNull() as? JsonObject
        return header.o("title").runs().ifEmpty { body.o("title").runs() }
    }

    private fun List<JsonObject>.sectionList(): JsonObject? = first().let { r ->
        r.o("contents").o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer")
            ?: findFirst(r, "sectionListRenderer") as? JsonObject
    }

    private fun findFirst(root: JsonElement?, key: String): JsonElement? = when (root) {
        is JsonObject -> root[key] ?: root.values.firstNotNullOfOrNull { findFirst(it, key) }
        is JsonArray -> root.firstNotNullOfOrNull { findFirst(it, key) }
        else -> null
    }

    private fun findAll(root: JsonElement?, key: String): List<JsonObject> = when (root) {
        is JsonObject -> listOfNotNull(root[key] as? JsonObject) + root.values.flatMap { findAll(it, key) }
        is JsonArray -> root.flatMap { findAll(it, key) }
        else -> emptyList()
    }

    private fun JsonElement?.o(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.a(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    private fun JsonElement?.s(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.runs(): String = a("runs")?.joinToString("") { it.s("text").orEmpty() }.orEmpty()
}
