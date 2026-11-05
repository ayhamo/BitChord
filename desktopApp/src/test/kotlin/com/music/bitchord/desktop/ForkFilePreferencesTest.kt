package com.music.bitchord.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The portable app's settings files (ForkFilePreferences): what goes in comes back after a restart. */
class ForkFilePreferencesTest {

    private val scratch = Files.createTempDirectory("bitchord-prefs").toFile()

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    /** A second process's view: a fresh tree read from the same folder. */
    private fun reopened() = ForkFilePreferences.rootAt(scratch)

    @Test
    fun valuesSurviveAFlushAndARestart() {
        val node = ForkFilePreferences.rootAt(scratch).node("com.music.bitchord.desktop")
        val odd = mapOf(
            "plain" to "value",
            "key with = and : and #" to "a=b:c#d!e",
            "unicode" to "Ünïcödé 日本語 \uD83C\uDFB5",
            "lines" to "one\ntwo\r\nthree\ttab",
            "backslash" to "C:\\Users\\x\\BitChord Portable\\data",
            "empty" to "",
            "  spaced  " to "  kept  ",
        )
        odd.forEach { (key, value) -> node.put(key, value) }
        node.putInt("number", 42)
        node.putBoolean("flag", true)
        node.flush()

        val back = reopened().node("com.music.bitchord.desktop")
        odd.forEach { (key, value) -> assertEquals(value, back.get(key, null), key) }
        assertEquals(42, back.getInt("number", 0))
        assertTrue(back.getBoolean("flag", false))
        assertEquals((odd.keys + "number" + "flag").toSet(), back.keys().toSet())
    }

    @Test
    fun aRemovedKeyStaysRemoved() {
        val node = ForkFilePreferences.rootAt(scratch).node("com/music/bitchord/navidrome")
        node.put("a", "1")
        node.put("b", "2")
        node.flush()
        node.remove("a")
        node.flush()
        val back = reopened().node("com/music/bitchord/navidrome")
        assertNull(back.get("a", null))
        assertEquals("2", back.get("b", null))
    }

    @Test
    fun oddNodeNamesAreKeptApartAndListedBack() {
        val names = listOf(
            "com.music.bitchord.desktop", "UPPER", "upper", "Mixed.Case", "con", "nul.txt", "com1",
            "trailing.", "..", ".", "=node", "with space", "ünï", "%", "%41", "a\\b",
        )
        val root = ForkFilePreferences.rootAt(scratch)
        names.forEachIndexed { index, name -> root.node(name).putInt("i", index) }
        root.flush()

        val back = reopened()
        assertEquals(names.toSet(), back.childrenNames().toSet())
        names.forEachIndexed { index, name -> assertEquals(index, back.node(name).getInt("i", -1), name) }
    }

    @Test
    fun aNestedPathAndAnEmptyNodeExistAfterARestart() {
        val root = ForkFilePreferences.rootAt(scratch)
        root.node("com/music/bitchord/navidrome_clips")
        root.flush()
        val back = reopened()
        assertTrue(back.nodeExists("com/music/bitchord/navidrome_clips"))
        assertFalse(back.nodeExists("com/music/bitchord/never_made"))
        assertEquals(listOf("com"), back.childrenNames().toList())
    }

    @Test
    fun aRemovedNodeIsGoneFromDisk() {
        val root = ForkFilePreferences.rootAt(scratch)
        root.node("com/music/bitchord/old").put("k", "v")
        root.node("com/music/bitchord/keep").put("k", "v")
        root.flush()
        root.node("com/music/bitchord/old").removeNode()
        root.flush()
        val back = reopened()
        assertEquals(setOf("keep"), back.node("com/music/bitchord").childrenNames().toSet())
        assertFalse(back.nodeExists("com/music/bitchord/old"))
    }

    @Test
    fun aFolderPutThereByHandIsNotAChild() {
        val root = ForkFilePreferences.rootAt(scratch)
        root.node("real").put("k", "v")
        root.flush()
        File(scratch, "Backup").mkdirs()
        File(scratch, "%4a").mkdirs()
        File(scratch, "%zz").mkdirs()
        assertEquals(listOf("real"), reopened().childrenNames().toList())
    }

    @Test
    fun encodedNamesAreSafeFolderNamesThatDecodeBack() {
        val reserved = Regex("""^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$""")
        listOf("con", "CON", "aux.x", "lpt9", "a.", "..", "x y", "Ü", "a=b", "/").forEach { name ->
            val folder = ForkFilePreferences.encode(name)
            assertTrue(folder.all { it in 'a'..'z' || it in '0'..'9' || it in "._-%" || it in 'A'..'F' }, folder)
            assertFalse(reserved.matches(folder), folder)
            assertFalse(folder.endsWith("."), folder)
            assertFalse('=' in folder, folder)
            assertEquals(name, ForkFilePreferences.decode(folder))
        }
        // Windows folders ignore case: two names that differ only in case get different folders.
        assertTrue(!ForkFilePreferences.encode("Abc").equals(ForkFilePreferences.encode("abc"), ignoreCase = true))
    }

    @Test
    fun aDamagedValuesFileStartsEmptyInsteadOfFailing() {
        val root = ForkFilePreferences.rootAt(scratch)
        root.node("n").put("k", "v")
        root.flush()
        File(scratch, "n/=node.properties").writeText("k=\\u00")
        assertNull(reopened().node("n").get("k", null))
    }

    /** Puts a non-empty folder where [node]'s values file goes, so the write's move fails. */
    private fun block(name: String): File =
        File(File(scratch, name), "=node.properties").also { File(it, "inside").mkdirs() }

    @Test
    fun aFailedFlushThrowsAndTheChangeIsKeptForTheNextTry() {
        val node = ForkFilePreferences.rootAt(scratch).node("blocked")
        val inTheWay = block("blocked")
        node.put("k", "v")
        assertFailsWith<java.util.prefs.BackingStoreException> { node.flush() }
        assertTrue((node as ForkFilePreferences).unsaved)

        inTheWay.deleteRecursively()
        node.flush()
        assertFalse(node.unsaved)
        assertEquals("v", reopened().node("blocked").get("k", null))
    }

    @Test
    fun aFailedTimedWriteIsRetriedOnItsOwn() {
        val node = ForkFilePreferences.rootAt(scratch).node("retried")
        val inTheWay = block("retried")
        node.put("k", "v")
        // The timed write (a second after the change) fails against the folder in the way...
        Thread.sleep(1_500)
        assertTrue((node as ForkFilePreferences).unsaved)
        // ...and lands on a later pass once it is gone, with no flush or change to prompt it.
        inTheWay.deleteRecursively()
        val deadline = System.currentTimeMillis() + 20_000
        while (node.unsaved && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertFalse(node.unsaved)
        assertEquals("v", reopened().node("retried").get("k", null))
    }
}
