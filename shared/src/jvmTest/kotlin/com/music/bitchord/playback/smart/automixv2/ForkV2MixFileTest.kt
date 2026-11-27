package com.music.bitchord.playback.smart.automixv2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Mix files (the extension's spec/automix/mix-file.md): the two ready-made files round-trip (read, written, read
 * again: the same normalized overrides and songs, and the very text the extension wrote), the refusals and skips say
 * the spec's words, a replacement lists what it changes, and a file's beats fold onto a grid at half or double time.
 *
 * The ready-made files are read from the spec where it is mounted (RULEBOOK_SPEC, else /spec), as
 * [ForkV2EditorCasesTest] reads it: spec/automix/mixes/ holds copies of the user's D:\Programs\Projects\YT Clients\mixes\.
 */
class ForkV2MixFileTest {

    private val readyMade = listOf("cinderella-drowning", "yquefue-iliketomoveit")

    @Test
    fun theReadyMadeFilesRoundTrip() {
        for (name in readyMade) {
            val text = mixFixture(specDir(), name).readText()
            val r = ForkV2MixFile.read(text)
            assertNull(r.refused, name)
            assertEquals(0, r.skipped.size, "$name: ${r.skipped.map { it.why }}")
            assertEquals(1, r.mixes.size, name)
            val m = r.mixes[0]
            assertEquals("custom", m.override.preset, name)
            assertNotNull(m.override.drawing("volume"), "$name: its volume is drawn")
            val written = ForkV2MixFile.write(r.mixes)
            // the extension's writer and this one give the same file (JSON.stringify(file, null, 2) and a newline)
            assertEquals(text.replace("\r\n", "\n").trimEnd(), written.trimEnd(), "$name: written as the extension writes it")
            val again = ForkV2MixFile.read(written)
            assertNull(again.refused, name)
            assertEquals(1, again.mixes.size, name)
            val b = again.mixes[0]
            assertEquals(m.key, b.key, name)
            assertEquals(m.from.title, b.from.title, name)
            assertEquals(m.to.artist, b.to.artist, name)
            assertEquals(ForkV2Json.write(m.override.toJson()), ForkV2Json.write(b.override.toJson()), "$name: the same normalized override back")
            // saved as the editor saves it, then exported: the same again (engine and savedAt left out)
            val saved = b.override.copy().also { it.engine = 3; it.savedAt = 1_700_000_000_000 }
            val out = ForkV2MixFile.write(listOf(ForkV2MixFile.Mix(b.from, b.to, saved)))
            assertEquals(written, out, "$name: an export of the saved edit is the file")
            assertEquals(0, ForkV2MixFile.importPlan(again.mixes) { saved }.let { it.add.size + it.replace.size }, "$name: imported twice, nothing to do")
        }
    }

    @Test
    fun aFileIsRefusedInTheSpecsWords() {
        fun refused(text: String) = ForkV2MixFile.read(text).refused
        assertEquals("not a Vivimusic mix file: it is not JSON", refused("{"))
        assertEquals("not a Vivimusic mix file", refused("""{"format":"other","version":1,"mixes":[]}"""))
        assertEquals("not a Vivimusic mix file", refused("[]"))
        assertEquals("the mix file has no valid version", refused("""{"format":"vivi-mix","version":1.5,"mixes":[]}"""))
        assertEquals("the mix file has no valid version", refused("""{"format":"vivi-mix","mixes":[]}"""))
        assertEquals(
            "this mix file is version 2, newer than this app reads (1): update the app to import it",
            refused("""{"format":"vivi-mix","version":2,"mixes":[]}"""),
        )
        assertEquals("the mix file has no mixes", refused("""{"format":"vivi-mix","version":1,"mixes":[]}"""))
        val many = (1..301).joinToString(",") { "{}" }
        assertEquals("the mix file has 301 mixes: 300 is the most", refused("""{"format":"vivi-mix","version":1,"mixes":[$many]}"""))
        assertEquals("the file is over 2 MB: not a Vivimusic mix file", refused(" ".repeat(ForkV2MixFile.MAX_BYTES + 1)))
        // a byte-order mark and unknown fields are fine
        assertNull(ForkV2MixFile.read("\uFEFF" + file(mix("aaaaaaaaaaa", "bbbbbbbbbbb", """{"preset":"smooth"}""")).replace("\"version\"", "\"extra\":1,\"version\"")).refused)
    }

    @Test
    fun aMixIsSkippedInTheSpecsWords() {
        val r = ForkV2MixFile.read(
            file(
                mix("short", "bbbbbbbbbbb", """{"preset":"smooth"}"""),
                mix("aaaaaaaaaaa", "aaaaaaaaaaa", """{"preset":"smooth"}"""),
                mix("aaaaaaaaaaa", "bbbbbbbbbbb", """{"preset":"auto"}"""),
                mix("aaaaaaaaaaa", "bbbbbbbbbbb", """{"preset":"swirl"}"""),
                mix("aaaaaaaaaaa", "bbbbbbbbbbb", """{"preset":"custom","lanes":{"volume":"smooth","eq":"nope","effect":"none"}}"""),
                mix("aaaaaaaaaaa", "ccccccccccc", """{"preset":"smooth"}"""),
                mix("aaaaaaaaaaa", "ccccccccccc", """{"preset":"rise","bars":16}"""),
            ),
        )
        assertNull(r.refused)
        assertEquals(
            listOf(
                "1: a song has no valid videoId",
                "2: the same song on both sides",
                "3: nothing but Auto in it",
                "4: the preset \"swirl\" is unknown here",
                "5: its lanes are not valid",
                "6: the same pair again in mix 7: the later one is used",
            ),
            r.skipped.map { "${it.n}: ${it.why}" },
        )
        assertEquals(1, r.mixes.size)
        assertEquals("rise", r.mixes[0].override.preset, "the later one is used")
        assertEquals(16, r.mixes[0].override.bars)
    }

    @Test
    fun aReplacementListsWhatChanges() {
        val cinderella = ForkV2MixFile.read(mixFixture(specDir(), "cinderella-drowning").readText()).mixes[0].override
        val crossfade = ForkV2MixOverride.fromJson(ov("""{"preset":"custom","lanes":{"volume":"crossfade","eq":"none","effect":"none"}}"""))!!
        assertEquals(
            listOf("volume: crossfade → drawn", "eq: none → drawn", "length: auto → 16 bars", "beat match: auto → glide"),
            ForkV2MixFile.mixChanges(crossfade, cinderella),
        )
        val other = cinderella.copy()
        val d = other.drawing("volume")!!
        other.setLane("volume", null, d.with("gain", true, arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(64.0, 0.0))))
        other.lock = true
        other.out = ForkV2MixOverride.Position(12, 2, -30, 100.0)
        assertEquals(
            listOf("volume: drawn → another drawing", "mix-out: auto → bar 12.3 -30 ms", "lock: off → on"),
            ForkV2MixFile.mixChanges(cinderella, other),
        )
        val smooth = ForkV2MixOverride.fromJson(ov("""{"preset":"smooth"}"""))!!
        assertEquals(listOf("preset: smooth → loop", "volume: smooth's → loop", "eq: smooth's → loop", "effect: smooth's → loop"),
            ForkV2MixFile.mixChanges(smooth, ForkV2MixOverride.fromJson(ov("""{"preset":"loop"}"""))))
        val plan = ForkV2MixFile.importPlan(listOf(mixOf("aaaaaaaaaaa", "bbbbbbbbbbb", cinderella), mixOf("aaaaaaaaaaa", "ccccccccccc", cinderella), mixOf("ddddddddddd", "bbbbbbbbbbb", cinderella))) { key ->
            when (key) {
                "aaaaaaaaaaa>bbbbbbbbbbb" -> crossfade
                // saved with another tempo: a tempo is not an edit
                "aaaaaaaaaaa>ccccccccccc" -> cinderella.copy().also { it.gridBpm = 128.0; it.savedAt = 5 }
                else -> null
            }
        }
        assertEquals(listOf("ddddddddddd>bbbbbbbbbbb"), plan.add.map { it.key })
        assertEquals(listOf("aaaaaaaaaaa>bbbbbbbbbbb"), plan.replace.map { it.mix.key })
        assertEquals(listOf("aaaaaaaaaaa>ccccccccccc"), plan.same.map { it.key })
    }

    @Test
    fun anExportWritesTheBeatMatchAsItPlaysAndComesBackAsSaved() {
        // mix-file.md rules 5, 6 (extension cb897a0, d9e6f90): an own Auto mix is written "auto", so it doesn't come back as Sync
        val own = ForkV2MixOverride.fromJson(ov("""{"preset":"smooth","bars":8}"""))!!
        val out = ForkV2MixFile.forExport(own, 120.0)
        assertEquals("auto", out.beatMatch)
        assertEquals(120.0, out.gridBpm)
        val back = ForkV2MixFile.read(ForkV2MixFile.write(listOf(mixOf("aaaaaaaaaaa", "bbbbbbbbbbb", out)))).mixes[0].override
        assertEquals("auto", back.beatMatchOf())
        // re-imported over the saved mix: already saved as it is
        val plan = ForkV2MixFile.importPlan(listOf(mixOf("aaaaaaaaaaa", "bbbbbbbbbbb", back))) { own }
        assertEquals(listOf("aaaaaaaaaaa>bbbbbbbbbbb"), plan.same.map { it.key })
        // an imported mix with none is written as Sync; a picked one stays; a loop gets none; no tempo: nothing added
        val imported = own.copy().also { it.gridBpm = 100.0 }
        assertEquals("sync", ForkV2MixFile.forExport(imported, null).beatMatch)
        assertEquals("glide", ForkV2MixFile.forExport(own.copy().also { it.beatMatch = "glide" }, 120.0).beatMatch)
        assertNull(ForkV2MixFile.forExport(ForkV2MixOverride.fromJson(ov("""{"preset":"loop"}"""))!!, 120.0).beatMatch)
        assertNull(ForkV2MixFile.forExport(own, null).beatMatch)
        // a change is named as it plays: the saved own Auto against an imported mix with none
        assertEquals(listOf("beat match: auto → sync"), ForkV2MixFile.mixChanges(own, imported))
        assertEquals(emptyList(), ForkV2MixFile.mixChanges(imported, ForkV2MixFile.forExport(imported, null)))
    }

    @Test
    fun beatsFoldOntoHalfOrDoubleTime() {
        val o = ForkV2MixFile.read(mixFixture(specDir(), "cinderella-drowning").readText()).mixes[0].override.copy()
        assertEquals("no gridBpm: its beats are taken as they are", ForkV2MixFile.foldNote(o.copy().also { it.gridBpm = 0.0 }, 65.0))
        o.gridBpm = 130.0
        val half = ForkV2MixFile.fold(o, 65.5)
        assertEquals(0.5, half.k)
        assertEquals(8, half.ov!!.bars, "16 bars at 130 are 8 at 65")
        assertEquals(65.0, half.ov.gridBpm)
        assertEquals(32.0, half.ov.drawing("volume")!!.points("gain", true).last()[0], "64 beats at 130 are 32 at 65")
        assertNull(half.note)
        val same = ForkV2MixFile.fold(o, 131.0)
        assertEquals(1.0, same.k)
        assertEquals(16, same.ov!!.bars)
        val none = ForkV2MixFile.fold(o, 97.0)
        assertNull(none.k)
        assertEquals("drawn at 130 BPM, this song reads 97 BPM: its beats are taken as they are", none.note)
        assertEquals(16, none.ov!!.bars)
        val dbl = ForkV2MixFile.fold(o.copy().also { it.gridBpm = 65.0 }, 130.0)
        assertEquals(32, dbl.ov!!.bars)
        // the planner plays it folded, with a note when a length can't be kept
        val big = o.copy().also { it.gridBpm = 65.0; it.bars = 32 }
        assertEquals("64 bars on this song's grid: 32 play", ForkV2MixFile.fold(big, 130.0).note)
        // gridBpm survives a save and is written; a file without it reads 0
        assertEquals(130.0, ForkV2MixOverride.fromJson(o.toJson())!!.gridBpm)
    }

    @Test
    fun playTheseMixesQueueOrder() {
        val o = ForkV2MixOverride.fromJson(ov("""{"preset":"smooth"}"""))!!
        // A→B, B→C queues A, B, C (the extension's mixQueue)
        val ids = ForkV2MixFile.mixQueue(listOf(mixOf("aaaaaaaaaaa", "bbbbbbbbbbb", o), mixOf("bbbbbbbbbbb", "ccccccccccc", o)))
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), ids)
    }

    @Test
    fun replacingADrawingAsksAndUndoBringsItBack() {
        val cinderella = ForkV2MixFile.read(mixFixture(specDir(), "cinderella-drowning").readText()).mixes[0].override
        // volume and eq are drawn: a preset replaces both, a shape in volume's dropdown only volume's
        assertEquals(listOf("volume", "eq"), ForkV2DrawEditor.replacedDrawings(cinderella, null))
        assertEquals("Replace the drawings?", ForkV2DrawEditor.replaceQuestion(2))
        assertEquals(listOf("volume"), ForkV2DrawEditor.replacedDrawings(cinderella, "volume"))
        assertEquals("Replace the drawing?", ForkV2DrawEditor.replaceQuestion(1))
        assertEquals(emptyList(), ForkV2DrawEditor.replacedDrawings(cinderella, "effect"), "effect isn't drawn: no question")
        assertEquals(emptyList(), ForkV2DrawEditor.replacedDrawings(ForkV2MixOverride.fromJson(ov("""{"preset":"smooth"}""")), null))
        // Yes: the whole override goes on the Undo list; Undo brings the drawings back
        val step = ForkV2DrawEditor.Step("a>b", cinderella.copy(), 0, 0, whole = true)
        val smooth = ForkV2MixOverride.fromJson(ov("""{"preset":"smooth"}"""))
        val back = ForkV2DrawEditor.undone(smooth, step)!!
        assertEquals(ForkV2Json.write(cinderella.toJson()), ForkV2Json.write(back.toJson()))
    }

    /** The extension's test of editor.md §6 "Seconds only" (mix-file.md rule 2), case for case. */
    @Test
    fun aWindowInSecondsOnlyIsKeptAndPlaysAtTheNearestBeat() {
        val o = ForkV2MixOverride.fromJson(ov("""{"v":1,"bars":8,"window":{"out":{"sec":218.1424},"in":{"sec":10.28,"beat":2}}}"""))!!
        // no bar: no beat either; seconds rounded to 1 ms
        assertEquals("""{"out":{"sec":218.142},"in":{"sec":10.28}}""", ForkV2Json.write(o.toJson()["window"]))
        // 218.142 → the nearest beat 218.3 (0.3 + 436 × 0.5); not moved
        val r = ForkV2MixOverride.resolveWindow(ForkV2MixOverride.Position(null, 0, 0, 218.142), 0.3, 0.5)!!
        assertEquals(218.3, r.sec)
        assertEquals(false, r.moved)
        assertEquals(true, r.snapped)
        assertNull(r.bar)
        // a nudge on top stays on top of the snapped beat
        assertEquals(218.32, ForkV2MixOverride.resolveWindow(ForkV2MixOverride.Position(null, 0, 20, 218.162), 0.3, 0.5)!!.sec)
        // with no grid: the seconds as they are
        assertEquals(218.142, ForkV2MixOverride.resolveWindow(ForkV2MixOverride.Position(null, 0, 0, 218.142), Double.NaN, Double.NaN)!!.sec)
        // a position with its bar still resolves as before
        assertEquals(20.3, ForkV2MixOverride.resolveWindow(ForkV2MixOverride.Position(10, 0, 0, 20.3), 0.3, 0.5)!!.sec)
        // a mix file with seconds-only windows reads back unchanged
        val back = ForkV2MixFile.read(ForkV2MixFile.write(listOf(mixOf("rCwSRV7P6hY", "0X3Y2m9wj5c", o)))).mixes[0].override
        assertEquals(ForkV2Json.write(o.toJson()["window"]), ForkV2Json.write(back.toJson()["window"]))
        // the readout leaves out the bar; a nudge keeps it seconds only
        assertEquals("A mixes out at 3:38.1 · B enters at 0:10.3", ForkV2EditorGeometry.readout(o.out!!, o.`in`!!))
        assertNull(ForkV2EditorGeometry.nudged(o.out!!, 1)!!.bar)
    }

    // ---------------------------------------------------------------------------------------------

    private fun ov(json: String): Any? = ForkV2Json.parse(json)

    private fun mixOf(a: String, b: String, o: ForkV2MixOverride) = ForkV2MixFile.Mix(ForkV2MixFile.Song(a), ForkV2MixFile.Song(b), o)

    private fun mix(a: String, b: String, override: String) = """{"from":{"videoId":"$a"},"to":{"videoId":"$b"},"override":$override}"""

    private fun file(vararg mixes: String) = """{"format":"vivi-mix","version":1,"mixes":[${mixes.joinToString(",")}]}"""

    /** The extension's spec/: RULEBOOK_SPEC, else /spec. Missing fails locally and is skipped on CI. */
    private fun specDir(): File {
        val dir = File(System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec")
        if (runCatching { mixFixture(dir, "cinderella-drowning") }.isFailure) {
            org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true")
            fail("the extension's spec is not at $dir (set RULEBOOK_SPEC, or mount it at /spec): no automix/mixes/ there")
        }
        return dir
    }
}
