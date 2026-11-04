package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ForkCrossfadeSkipTest {

    /** Just enough of an ExoPlayer: a queue, a current item, and the seeks made on it. */
    private class FakePlayer(ids: List<String>, var current: Int = 0) {
        val items = ids.map { MediaItem.Builder().setMediaId(it).build() }.toMutableList()
        var shuffle = false
        val seeks = mutableListOf<Pair<Int, Long>>()
        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args[0] as Int]
                "getCurrentMediaItemIndex" -> current
                "getCurrentMediaItem" -> items.getOrNull(current)
                "isPlaying" -> true
                "getPlayerError" -> null
                "getShuffleModeEnabled" -> shuffle
                "setShuffleModeEnabled" -> { shuffle = args[0] as Boolean; null }
                "seekTo" -> { if (args.size == 2) seeks += (args[0] as Int) to (args[1] as Long); null }
                "removeMediaItems" -> {
                    val from = args[0] as Int
                    val to = args[1] as Int
                    repeat(to - from) { items.removeAt(from) }
                    if (current >= to) current -= to - from
                    null
                }
                "addMediaItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    if (args.size == 2) {
                        val at = args[0] as Int
                        val added = args[1] as List<MediaItem>
                        items.addAll(at, added)
                        if (at <= current) current += added.size
                    } else {
                        items.addAll(args[0] as List<MediaItem>)
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        } as ExoPlayer

        fun ids() = items.map { it.mediaId }
    }

    private class Rig(out: FakePlayer) {
        var phase = "IDLE"
        var handedOff = false
        var outgoing: ExoPlayer? = null
        var bails = 0
        val skip = ForkCrossfadeSkip(
            idle = { phase == "IDLE" },
            arming = { phase == "ARMING" },
            handedOff = { handedOff },
            outgoing = { outgoing },
            active = { out.player },
            configuredFadeMs = { 4_000L },
            versionSwapActive = { false },
            smartFallbackMs = 6_000L,
            begin = { _, _ ->
                phase = "ARMING"
                outgoing = out.player
                true
            },
            bail = {
                bails++
                phase = "IDLE"
            },
        )
    }

    @Test
    fun `an insert made while a skip arms reaches the incoming player`() {
        val out = FakePlayer(listOf("a", "b", "c", "d"))
        val rig = Rig(out)
        var told = 0
        rig.skip.onMaskChanged = { told++ }
        assertTrue(rig.skip.crossfadeSkip(1))
        assertEquals(1, told)
        assertEquals(1, rig.skip.pendingSkipIndex())
        // The standby was loaded with a copy taken at arm time, positioned on "b".
        val into = FakePlayer(listOf("a", "b", "c", "d"), current = 1)
        // "Play next" on the outgoing player while arming: after "b", the song shown.
        out.items.add(2, MediaItem.Builder().setMediaId("x").build())
        assertTrue(rig.skip.mirrorQueue(out.player, into.player))
        assertEquals(listOf("a", "b", "x", "c", "d"), into.ids())
        assertEquals(1, into.current)
    }

    @Test
    fun `a row removed before the target moves it, and the incoming player follows`() {
        val out = FakePlayer(listOf("a", "b", "c", "d"))
        val rig = Rig(out)
        assertTrue(rig.skip.crossfadeSkip(2))
        val into = FakePlayer(listOf("a", "b", "c", "d"), current = 2)
        out.items.removeAt(1)
        assertEquals(1, rig.skip.pendingSkipIndex())
        assertTrue(rig.skip.mirrorQueue(out.player, into.player))
        assertEquals(listOf("a", "c", "d"), into.ids())
        assertEquals("c", into.items[into.current].mediaId)
    }

    @Test
    fun `a target removed while arming is not handed off`() {
        val out = FakePlayer(listOf("a", "b", "c"))
        val rig = Rig(out)
        assertTrue(rig.skip.crossfadeSkip(1))
        out.items.removeAt(1)
        assertFalse(rig.skip.mirrorQueue(out.player, FakePlayer(listOf("a", "b", "c"), current = 1).player))
    }

    @Test
    fun `an untouched queue is left as it is`() {
        val out = FakePlayer(listOf("a", "b"))
        val rig = Rig(out)
        assertTrue(rig.skip.crossfadeSkip(1))
        val into = FakePlayer(listOf("a", "b"), current = 1)
        val before = into.items.toList()
        assertTrue(rig.skip.mirrorQueue(out.player, into.player))
        assertEquals(before, into.items)
    }

    @Test
    fun `a seek while arming lands in the song shown, at that second`() {
        val out = FakePlayer(listOf("a", "b", "c"))
        val rig = Rig(out)
        assertTrue(rig.skip.crossfadeSkip(1))
        assertTrue(rig.skip.carryOut(42_000L))
        assertEquals(listOf(1 to 42_000L), out.seeks)
        assertEquals(1, rig.bails)
        assertFalse(rig.skip.isSkipBlend())
        assertEquals(C.INDEX_UNSET, rig.skip.pendingSkipIndex())
        // Nothing arming: a seek is the player's own.
        assertFalse(rig.skip.carryOut(1_000L))
    }

    @Test
    fun `a skip dropped while arming with nothing to carry out says the mask is off`() {
        val out = FakePlayer(listOf("a", "b"))
        val rig = Rig(out)
        var told = 0
        rig.skip.onMaskChanged = { told++ }
        assertTrue(rig.skip.crossfadeSkip(1))
        out.items.removeAt(1)
        // bail(): nothing to carry out (the row is gone), then finish().
        val carry = rig.skip.take()
        rig.skip.drop(ended = true)
        carry()
        assertEquals(2, told)
    }

    @Test
    fun `Next again while a skip arms carries the first out at once, so the second goes on from there`() {
        val out = FakePlayer(listOf("a", "b", "c"))
        var phase = "IDLE"
        lateinit var skip: ForkCrossfadeSkip
        skip = ForkCrossfadeSkip(
            idle = { phase == "IDLE" },
            arming = { phase == "ARMING" },
            handedOff = { false },
            outgoing = { out.player.takeIf { phase != "IDLE" } },
            active = { out.player },
            configuredFadeMs = { 4_000L },
            versionSwapActive = { false },
            smartFallbackMs = 6_000L,
            begin = { _, _ -> phase = "ARMING"; true },
            // The controller's bail() with nothing audible yet: take(), finish(), then the carry.
            bail = {
                val carry = skip.take()
                phase = "IDLE"
                skip.drop(ended = true)
                carry()
            },
        )
        assertTrue(skip.crossfadeSkip(1))
        // The second press: not taken as a blend, and "b" is reached before the caller's plain skip.
        assertFalse(skip.crossfadeSkip(1))
        assertEquals(listOf(1 to 0L), out.seeks)
        // The caller's onSkipRequested() then has nothing left to cancel.
        skip.drop()
        assertEquals(listOf(1 to 0L), out.seeks)
        assertEquals(C.INDEX_UNSET, skip.pendingSkipIndex())
    }

    @Test
    fun `a natural blend is untouched`() {
        val out = FakePlayer(listOf("a", "b"))
        val rig = Rig(out)
        assertFalse(rig.skip.isSkipBlend())
        assertEquals(12_000L, rig.skip.armTimeoutMs(C.INDEX_UNSET, 12_000L))
        assertEquals(ForkCrossfadeSkip.SKIP_ARM_TIMEOUT_MS, rig.skip.armTimeoutMs(1, 12_000L))
        assertTrue(rig.skip.mirrorQueue(out.player, FakePlayer(listOf("z")).player))
    }

    @Test
    fun `lyrics still loading past the wait stand in, and say so in the key`() {
        var now = 0L
        val spans = HashMap<String, String>()
        val wait = ForkLyricsWait(spans, "none") { now }
        assertNull(wait.spansFor("a"))
        wait.asked("a")
        now = ForkLyricsWait.FORK_LYRICS_WAIT_MS - 1
        assertNull(wait.spansFor("a"))
        now = ForkLyricsWait.FORK_LYRICS_WAIT_MS
        assertSame("none", wait.spansFor("a"))
        spans["a"] = "real"
        assertEquals("real", wait.spansFor("a"))
        assertEquals("|truefalse", wait.keyPart("none", "real"))
    }
}
