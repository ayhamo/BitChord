package com.music.bitchord.playback.smart.automixv2

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's override store ([ForkV2OverrideStore], editor.md §1): what a save writes, Reset, the
 * 300-unlocked cap by `savedAt` with locks exempt and capped on their own, the persisted JSON, and
 * the one-time move of the fork's older picks. The spec's own cases are in ForkV2EditorCasesTest.
 */
class ForkV2OverrideStoreTest {

    private var t = 1_000L

    @AfterTest
    fun clear() {
        ForkV2OverrideStore.clear()
    }

    private fun installed(saved: String? = null): MutableList<String> {
        val writes = ArrayList<String>()
        ForkV2OverrideStore.install(load = { saved }, save = { writes += it })
        ForkV2OverrideStore.clock = { t++ }
        return writes
    }

    private fun preset(p: String, bars: Int = 0) = ForkV2MixOverride().also {
        it.preset = p
        it.bars = bars
    }

    @Test
    fun aSaveIsNormalizedStampedAndPersisted() {
        val writes = installed()
        val before = ForkV2OverrideStore.changes.value
        val o = preset("smooth", 3) // 3 bars is no length the editor offers
        o.family = "blend" // dropped by a preset
        assertNull(ForkV2OverrideStore.put("a", "b", o))
        val got = ForkV2OverrideStore.get("a", "b")!!
        assertEquals("smooth", got.preset)
        assertEquals(0, got.bars)
        assertNull(got.family)
        assertEquals(ForkV2OverrideStore.ENGINE, got.engine)
        assertEquals(1_000L, got.savedAt)
        assertTrue(ForkV2OverrideStore.changes.value > before)
        assertEquals("""{"a>b":{"v":1,"preset":"smooth","engine":3,"savedAt":1000}}""", writes.last())
        // get hands out a copy: changing it changes nothing stored
        got.preset = "slam"
        assertEquals("smooth", ForkV2OverrideStore.get("a", "b")!!.preset)
    }

    @Test
    fun resetAndAnAllAutoSaveDropThePair() {
        val writes = installed()
        ForkV2OverrideStore.put("a", "b", preset("rise"))
        ForkV2OverrideStore.reset("a", "b")
        assertNull(ForkV2OverrideStore.get("a", "b"))
        assertEquals("{}", writes.last())
        ForkV2OverrideStore.put("a", "b", preset("rise"))
        ForkV2OverrideStore.put("a", "b", preset("auto"))
        assertNull(ForkV2OverrideStore.get("a", "b"), "Auto is the reset")
    }

    @Test
    fun theOldestSavedUnlockedGoFirstPastThreeHundred() {
        installed()
        // saved out of key order: c is the oldest by savedAt though written last
        ForkV2OverrideStore.clock = { 5_000L }
        ForkV2OverrideStore.put("a", "x", preset("wave"))
        ForkV2OverrideStore.clock = { 6_000L }
        ForkV2OverrideStore.put("b", "x", preset("wave"))
        ForkV2OverrideStore.clock = { 100L }
        ForkV2OverrideStore.put("c", "x", preset("wave"))
        var n = 10_000L
        ForkV2OverrideStore.clock = { n++ }
        for (i in 0 until ForkV2MixOverride.MAX_UNLOCKED - 2) ForkV2OverrideStore.put("p$i", "x", preset("melt"))
        assertEquals(ForkV2MixOverride.MAX_UNLOCKED, ForkV2OverrideStore.overrides.value.size)
        assertNull(ForkV2OverrideStore.get("c", "x"), "the oldest savedAt, not the first in the map")
        assertNotNull(ForkV2OverrideStore.get("a", "x"))
        ForkV2OverrideStore.put("q", "x", preset("melt"))
        assertNull(ForkV2OverrideStore.get("a", "x"))
        assertNotNull(ForkV2OverrideStore.get("b", "x"))
    }

    @Test
    fun locksDontCountNeverGoAndHaveTheirOwnCap() {
        installed()
        for (i in 0 until ForkV2MixOverride.MAX_LOCKED) {
            ForkV2OverrideStore.put("L$i", "x", preset("slam"))
            assertNull(ForkV2OverrideStore.setLocked("L$i", "x", true))
        }
        for (i in 0 until ForkV2MixOverride.MAX_UNLOCKED + 10) ForkV2OverrideStore.put("u$i", "x", preset("blend"))
        val m = ForkV2OverrideStore.overrides.value
        assertEquals(ForkV2MixOverride.MAX_LOCKED + ForkV2MixOverride.MAX_UNLOCKED, m.size)
        assertTrue((0 until ForkV2MixOverride.MAX_LOCKED).all { "L$it>x" in m }, "the oldest, but locked: kept")
        ForkV2OverrideStore.put("one", "more", preset("rise"))
        assertEquals(ForkV2OverrideStore.LOCK_CAP_REFUSAL, ForkV2OverrideStore.setLocked("one", "more", true))
        assertFalse(ForkV2OverrideStore.get("one", "more")!!.lock)
        assertNull(ForkV2OverrideStore.setLocked("L0", "x", false))
        assertNull(ForkV2OverrideStore.setLocked("one", "more", true))
        assertTrue(ForkV2OverrideStore.get("one", "more")!!.lock)
    }

    @Test
    fun aLockNeedsAnEdit() {
        installed()
        assertEquals(ForkV2OverrideStore.NOTHING_TO_LOCK, ForkV2OverrideStore.setLocked("a", "b", true))
        assertNull(ForkV2OverrideStore.setLocked("a", "b", false))
        assertNull(ForkV2OverrideStore.get("a", "b"))
    }

    @Test
    fun theSavedMapLoadsBackSkippingWhatItCantRead() {
        installed(
            """{"a>b":{"v":1,"preset":"wave","lock":true,"engine":3,"savedAt":7},"nokey":{"preset":"wave"},""" +
                """"c>d":{"preset":"wobble"},"e>f":{"style":"filter","bars":16},"g>h":3}""",
        )
        val m = ForkV2OverrideStore.overrides.value
        assertEquals(setOf("a>b", "e>f"), m.keys)
        assertTrue(m["a>b"]!!.lock)
        assertEquals(7L, m["a>b"]!!.savedAt)
        assertEquals("filter", m["e>f"]!!.family, "a {style, bars} value migrates as it is read")
        installed("not json")
        assertTrue(ForkV2OverrideStore.overrides.value.isEmpty())
    }

    @Test
    fun theOldPicksMoveInOnceInTheirOrderAndKeepWhatTheStoreHas() {
        val writes = installed()
        ForkV2OverrideStore.put("a", "b", preset("melt"))
        val old = "a->b\tloop\t\nc-1->d\tfilter\t32\nbad line\ne->f\tcrossfade\t16\ng->h\t\t7\ni->j\tloop\t8\nk->l\tblend\t4"
        assertEquals(4, ForkV2OverrideStore.migrateLegacyPicks(old))
        val m = ForkV2OverrideStore.overrides.value
        assertEquals("melt", m["a>b"]!!.preset, "the store's own edit stays")
        assertEquals("filter", m["c-1>d"]!!.family)
        assertEquals(32, m["c-1>d"]!!.bars, "a picked filter length is kept (editor.md §6, 2026-11-26)")
        assertEquals("off", m["e>f"]!!.beatMatch)
        assertEquals(0, m["e>f"]!!.bars, "a fade had no length")
        assertNull(m["g>h"], "7 bars was never a length: nothing but Auto")
        assertEquals("loop", m["i>j"]!!.preset)
        assertEquals("blend", m["k>l"]!!.family)
        assertTrue(m["c-1>d"]!!.savedAt < m["e>f"]!!.savedAt && m["e>f"]!!.savedAt < m["k>l"]!!.savedAt, "oldest first, as saved")
        assertTrue(m["k>l"]!!.savedAt < m["a>b"]!!.savedAt, "older than anything saved in the editor")
        assertTrue(writes.last().contains("\"k>l\""))
        assertEquals(0, ForkV2OverrideStore.migrateLegacyPicks(old), "a second run moves nothing")
        assertEquals(0, ForkV2OverrideStore.migrateLegacyPicks(null))
    }

    @Test
    fun pairKeysBothWays() {
        assertEquals("a>b", ForkV2OverrideStore.pairKey("a", "b"))
        assertEquals("x-1" to "y-2", ForkV2OverrideStore.idsOf("x-1>y-2"))
        assertEquals("x-1" to "y-2", ForkV2OverrideStore.idsOf("x-1->y-2"))
        assertNull(ForkV2OverrideStore.idsOf("nokey"))
    }
}
