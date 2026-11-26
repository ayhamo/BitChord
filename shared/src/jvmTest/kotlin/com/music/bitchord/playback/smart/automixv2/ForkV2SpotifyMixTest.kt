package com.music.bitchord.playback.smart.automixv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The extension's "Spotify mix exports" and seconds-only confirm tests (spotify-mixes.md, mix-file.md rule 5), case for case. */
class ForkV2SpotifyMixTest {

    private fun t(id: String, title: String, yt: String?) =
        """{"uri":"spotify:track:$id",${if (yt != null) "\"youtubeVideoId\":\"$yt\"," else ""}"title":"$title","artists":["X","Y"],"durationMs":400000}"""

    private val s1 = t("a", "All The Stars", "rCwSRV7P6hY")
    private val s2 = t("b", "Wild Thoughts", "0X3Y2m9wj5c")
    private val s3 = t("c", "Interlude", "JGwWNGJdvx8")
    private val s4 = t("d", "Gone", null)

    // BUDGET's first mix: 4 bars from 218.142 s, a drawn eq on A (low knob to 0 at the end)
    private fun mix1(extra: String = "", index: Int = 1, from: String = s1, to: String = s2) =
        """{"index":$index,"from":$from,"to":$to,"fromStartMs":218142,"durationMs":9919,"bars":4,"fromBpm":98$extra,"style":{"volume":7,"eq":4,"filter":0},""" +
            """"curves":{"eq":{"from":{"low":[{"start":0,"end":0.5,"points":[[0,0.5],[1,0.5]]},{"start":0.5,"end":1,"points":[[0,0.5],[1,0]]}]}}}}"""

    // a straight cut in Spotify (1 ms, no start)
    private val mix2 = """{"index":2,"from":$s2,"to":$s3,"durationMs":1,"style":{}}"""

    // a song with no YouTube id
    private fun mix3(to: String = s4) = """{"index":3,"from":$s3,"to":$to,"fromStartMs":1000,"durationMs":8000,"bars":0,"style":{"volume":10,"eq":11,"filter":9,"fx":2}}"""

    private fun export(version: Int = 3, vararg mixes: String = arrayOf(mix1(), mix2, mix3())) =
        """{"format":"spotify-mix-export","version":$version,"playlist":{"name":"Test","tracks":[$s1,$s2,$s3]},"mixes":[${mixes.joinToString(",")}]}"""

    private fun window(o: ForkV2MixOverride) = ForkV2Json.write(o.toJson()["window"])

    @Test
    fun spotifyMixExportsImportDirectly() {
        val r = ForkV2MixFile.read(export())
        assertNull(r.refused)
        assertEquals(2, r.mixes.size)
        // the straight cut: Spotify's No transition (spotify-mixes.md rule 3)
        assertEquals("""{"v":1,"preset":"none"}""", ForkV2Json.write(r.mixes[1].override.toJson()))
        val ov = r.mixes[0].override
        assertEquals("rCwSRV7P6hY>0X3Y2m9wj5c", r.mixes[0].key)
        assertEquals("X, Y", r.mixes[0].from.artist)
        assertEquals("custom", ov.preset)
        assertEquals(4, ov.bars)
        assertEquals(98.0, ov.gridBpm)
        // seconds-only windows (editor.md §6): A out at 218.142 s, B in at 0
        assertEquals("""{"out":{"sec":218.142},"in":{"sec":0}}""", window(ov))
        assertEquals("overlap", ov.volume)
        // eq knob 0.5 → 0 dB, 0 → -18 dB, in beats over the 16 beats
        val eq = ov.drawing("eq")!!
        assertEquals("beat", eq.unit)
        assertEquals("[[8,0],[16,-18]]", ForkV2Json.write(eq.points("bassDb", true).takeLast(2).map { listOf(it[0], it[1]) }))
        // B's deck keeps the center bass swap's own shape
        assertTrue(eq.points("bassDb", false).isNotEmpty())
        assertEquals(listOf(3), r.skipped.map { it.n })
        assertEquals("no YouTube id for Gone", r.skipped[0].why)
        // the queue: the playlist's tracks in order, a song in no mix too
        assertEquals(listOf("rCwSRV7P6hY", "0X3Y2m9wj5c", "JGwWNGJdvx8"), r.queue!!.map { it.videoId })
        assertEquals("Test", r.name)
        // version 2 has no ids: refused, with why; a newer one too
        assertTrue("no YouTube ids" in ForkV2MixFile.read(export(2)).refused!!)
        assertTrue("newer than this app reads" in ForkV2MixFile.read(export(4)).refused!!)
        // a mix with no bars: drawn in seconds, beat match off; Start bass swap and LP in drawn here
        val r2 = ForkV2MixFile.read(export(3, mix3(to = s1)))
        val o2 = r2.mixes[0].override
        assertEquals("off", o2.beatMatch)
        assertEquals("cutInFadeOut", o2.volume)
        // Start bass swap and Low pass filter in: shapes of their own now
        assertEquals("startBassSwap", o2.eq)
        assertEquals("lpIn", o2.effect)
        assertEquals("[[0,400],[8,20000]]", ForkV2Json.write(ForkV2MixOverride.presetCurves(o2, 8.0, Double.NaN)!!.`in`["lowpassHz"]!!.map { listOf(it[0], it[1]) }))
        // Effects style 2 (Reverb cut end) is named: the shape, nothing left out
        assertEquals("reverbCutEnd", o2.fx)
        assertFalse("not carried" in r2.notes.joinToString(" | "), r2.notes.joinToString(" | "))
        // a negative B start (B's 0:00 comes in 1.972 s and 14.486 s after the mix starts): B from 0, that long into the
        // mix (mix-file.md rule 2, B's late entry), no note
        fun late(v: String) = export(3, mix1(",\"toStartMs\":$v"), mix1(",\"toStartMs\":-14486", index = 4, from = s2, to = s3))
        val rl = ForkV2MixFile.read(late("-1972"))
        assertEquals("""{"out":{"sec":218.142},"in":{"sec":0,"delaySec":1.972}}""", window(rl.mixes[0].override))
        assertEquals(0.0, rl.mixes[1].override.`in`!!.sec)
        assertEquals(14.486, rl.mixes[1].override.`in`!!.delaySec)
        assertFalse(rl.notes.any { "late" in it }, rl.notes.joinToString(" | "))
        // an older export's unsigned 2^64 − x can't be read back: B with the mix, and the summary says so
        val wrapped = ForkV2MixFile.read(late("18446744073709549644"))
        assertEquals(0.0, wrapped.mixes[0].override.`in`!!.sec)
        assertEquals(0.0, wrapped.mixes[0].override.`in`!!.delaySec)
        assertTrue("1 mix brings B in late in an older export's unsigned time: here B starts with the mix" in wrapped.notes, wrapped.notes.joinToString(" | "))
    }

    @Test
    fun anUnsetLanePlaysItsBasePresets() {
        val ids = listOf("rCwSRV7P6h", "0X3Y2m9wj5", "JGwWNGJdvx", "ju4KQT0wL0", "G8-3EX34Qv")
        fun mix(i: Int, style: String) =
            """{"index":$i,"from":${t("a$i", "a$i", ids[i] + "A")},"to":${t("b$i", "b$i", ids[i] + "B")},"fromStartMs":100000,"durationMs":16000,"bars":8,"fromBpm":120,"style":{$style}}"""
        val r = ForkV2MixFile.read(
            """{"format":"spotify-mix-export","version":3,"playlist":{"name":"T","tracks":[]},"mixes":[""" + listOf(
                mix(0, "\"basePresetId\":4,\"eq\":4,\"filter\":0"), // Melt: volume unset
                mix(1, "\"basePresetId\":21"), // Surge: all unset, its Effects too
                mix(2, "\"basePresetId\":3,\"volume\":6"), // Rise: eq and filter unset
                mix(3, "\"basePresetId\":19"), // an older preset: its lanes
                mix(4, "\"basePresetId\":99,\"volume\":3"), // an unknown id and an unnamed volume: the default
            ).joinToString(",") + "]}",
        )
        val l = r.mixes.map { ForkV2Json.write(it.override.toJson()["lanes"]) }
        assertTrue(l[0].startsWith("""{"volume":"fadeInFadeOut""""), l[0])
        assertEquals("""{"volume":"fadeInCutOut","eq":"endBassSwap","effect":"hpHalfOut","fx":"reverbOutEnd"}""", l[1])
        assertEquals("""{"volume":"smooth","eq":"endBassSwap","effect":"lpInHpOut"}""", l[2])
        assertEquals("""{"volume":"overlap","eq":"centerBassSwap","effect":"lpBoth"}""", l[3])
        assertEquals("""{"volume":"overlap","eq":"centerBassSwap","effect":"none"}""", l[4])
    }

    @Test
    fun aSecondsOnlyPositionReadsAsItsTimeInTheConfirm() {
        val was = ForkV2MixOverride.fromJson(ForkV2Json.parse("""{"v":1,"bars":8,"window":{"out":{"bar":40,"beat":2,"sec":200.5}}}"""))
        val now = ForkV2MixOverride.fromJson(ForkV2Json.parse("""{"v":1,"bars":8,"window":{"out":{"sec":218.142,"nudgeMs":20},"in":{"sec":0}}}"""))
        val lines = ForkV2MixFile.mixChanges(was, now)
        assertTrue("mix-out: bar 40.3 → 218.142 s +20 ms" in lines, lines.joinToString("\n"))
        assertTrue("mix-in: auto → 0 s" in lines, lines.joinToString("\n"))
        assertFalse("undefined" in lines.joinToString("\n") || "null" in lines.joinToString("\n"))
    }
}
