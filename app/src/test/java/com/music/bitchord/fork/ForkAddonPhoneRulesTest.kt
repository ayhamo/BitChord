package com.music.bitchord.fork

import com.music.bitchord.data.sources.ForkAddonFloor
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.addon.AddonQualityOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The phone's side of the addon rulebook's batch 2: the quality pick in the cache key (8.4.2), which
 * addons the pass asks and what it takes (8.2.1), and the decoder's MIME read as a codec.
 */
class ForkAddonPhoneRulesTest {

    private val options = listOf(AddonQualityOption("hires", "Hi-Res"), AddonQualityOption("cd", "CD"), AddonQualityOption("mp3", "MP3 320"))

    private fun addon(id: String, pick: String?, listed: List<AddonQualityOption> = options) =
        SourceConfig(id = id, kind = SourceKind.ADDON, qualityOptions = listed, pickedQuality = pick)

    @Test
    fun thePickIsPartOfTheKeyOnlyOffTheFirstOption() {
        assertNull(ForkAddonQualityKey.pickOf(addon("a", null))) // none picked: the first option
        assertNull(ForkAddonQualityKey.pickOf(addon("a", "hires"))) // the first option: kept as none
        assertNull(ForkAddonQualityKey.pickOf(addon("a", "gone"))) // no longer listed: falls back to the first
        assertNull(ForkAddonQualityKey.pickOf(addon("a", "cd", listed = emptyList()))) // no choice offered
        assertEquals("a=cd", ForkAddonQualityKey.pickOf(addon("a", "cd")))
        assertEquals("", ForkAddonQualityKey.of(emptyList())) // entries cached before keep their key
        val cd = ForkAddonQualityKey.of(listOf("a=cd"))
        assertTrue(cd.startsWith("-q"))
        assertEquals(cd, ForkAddonQualityKey.of(listOf("a=cd")))
        assertNotEquals(cd, ForkAddonQualityKey.of(listOf("a=mp3")))
        assertNotEquals(cd, ForkAddonQualityKey.of(listOf("b=cd")))
    }

    private fun source(id: String): MusicSource =
        Proxy.newProxyInstance(MusicSource::class.java.classLoader, arrayOf(MusicSource::class.java)) { proxy, method, args ->
            when (method.name) {
                "getConfigId" -> id
                "getKind" -> SourceKind.ADDON
                "getDisplayName" -> id
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> id
                else -> null
            }
        } as MusicSource

    @Test
    fun thePassAsksTheAddonsItShould() {
        val ranked = listOf("a", "b", "c", "d").map(::source)
        fun ids(look: ForkAddonFloorPhone.Look?, servedBy: String?) =
            ForkAddonFloorPhone.sources(look, ranked, servedBy).map { it.configId }
        val lossy = ForkAddonFloorPhone.Look.Pass(ForkAddonFloor.Pass(aboveOnly = false, minRank = 1), above = "c")
        assertEquals(listOf("a", "b", "d"), ids(lossy, "c")) // every addon but the one serving it
        val cached = ForkAddonFloorPhone.Look.Pass(ForkAddonFloor.Pass(aboveOnly = true, minRank = 2), above = "c")
        assertEquals(listOf("a", "b"), ids(cached, null)) // only those ranked above the cached copy's addon
        val unknown = ForkAddonFloorPhone.Look.Pass(ForkAddonFloor.Pass(aboveOnly = true, minRank = 2), above = null)
        assertEquals(listOf("a", "b", "c", "d"), ids(unknown, null)) // a cached copy that names no addon: all
        assertEquals(listOf("a", "c", "d"), ids(null, "b")) // upstream's: every other source
        assertEquals(listOf("a", "c", "d"), ids(ForkAddonFloorPhone.Look.OverYouTube, "b"))
        // No pass: nobody is asked (a search and /stream calls whose answers worth() refuses anyway spend the addons' quota).
        assertEquals(emptyList<String>(), ids(ForkAddonFloorPhone.Look.None, "b"))
    }

    @Test
    fun whatThePassTakes() {
        val mp3 = StreamFormat(codec = "mp3", kbps = 320)
        val flac = StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)
        val lossy = ForkAddonFloorPhone.Look.Pass(ForkAddonFloor.Pass(aboveOnly = false, minRank = 1), above = "c")
        assertTrue(ForkAddonFloorPhone.worth(lossy, flac, mp3) { false })
        assertFalse(ForkAddonFloorPhone.worth(lossy, StreamFormat(codec = "aac", kbps = 512), mp3) { true }) // lossless or better only
        assertFalse(ForkAddonFloorPhone.worth(ForkAddonFloorPhone.Look.None, flac, mp3) { true })
        assertTrue(ForkAddonFloorPhone.worth(null, mp3, null) { true }) // no look given: upstream's answer
        // YouTube's own copy: upstream's worthSwapping when its bitrate is known, the floor at 160 kbps when not.
        assertFalse(ForkAddonFloorPhone.worth(ForkAddonFloorPhone.Look.OverYouTube, mp3, StreamFormat(kbps = 256)) { false })
        assertTrue(ForkAddonFloorPhone.worth(ForkAddonFloorPhone.Look.OverYouTube, mp3, null) { false })
        assertFalse(ForkAddonFloorPhone.worth(ForkAddonFloorPhone.Look.OverYouTube, StreamFormat(codec = "aac", kbps = 192), null) { true })
    }

    @Test
    fun theDecodersMimeAsACodec() {
        assertEquals("flac", ForkAddonFloorPhone.codecOf("audio/flac"))
        assertEquals("alac", ForkAddonFloorPhone.codecOf("audio/alac"))
        assertEquals("wav", ForkAddonFloorPhone.codecOf("audio/raw"))
        assertEquals("aac", ForkAddonFloorPhone.codecOf("audio/mp4a-latm"))
        assertEquals("opus", ForkAddonFloorPhone.codecOf("audio/opus"))
        assertEquals("eac3-joc", ForkAddonFloorPhone.codecOf("audio/eac3-joc"))
        assertNull(ForkAddonFloorPhone.codecOf(null))
    }
}
