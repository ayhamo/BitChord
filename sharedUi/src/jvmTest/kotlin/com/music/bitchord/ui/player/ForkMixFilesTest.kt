package com.music.bitchord.ui.player

import com.music.bitchord.playback.smart.automixv2.ForkV2MixOverride
import com.music.bitchord.playback.smart.automixv2.ForkV2OverrideStore
import com.music.bitchord.data.model.Song
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The mix files' import and export (spec/automix/mix-file.md rules 5 to 8) over the editor's store: a ready-made
 * file imported, imported again, imported over a different saved edit (the confirm lists what changes; declining
 * keeps it), and exported under the spec's file name. The ready-made file is read from the spec where it is mounted
 * (RULEBOOK_SPEC, else /spec).
 */
class ForkMixFilesTest {

    @AfterTest
    fun reset() {
        ForkMixFiles.confirm = null
        ForkMixFiles.status = null
        ForkMixFiles.saveAs = null
        ForkMixFiles.queue = null
        ForkMixFiles.lastImport = null
        ForkMixFiles.lookup = { null }
    }

    @Test
    fun aReadyMadeMixImportsAndExports() {
        var stored: String? = null
        ForkV2OverrideStore.install(load = { null }, save = { stored = it })
        val text = File(specDir(), "automix/mixes/cinderella-drowning.vivimix.json").readText()

        ForkMixFiles.imported(text)
        assertEquals("Imported 1 mix. Play these mixes queues them.", ForkMixFiles.status)
        val saved = ForkV2OverrideStore.get("rCwSRV7P6hY", "0X3Y2m9wj5c")
        assertNotNull(saved)
        assertEquals(ForkV2OverrideStore.ENGINE, saved.engine, "saved as the editor saves")
        assertNotNull(saved.drawing("volume"))
        assertTrue(stored!!.contains("rCwSRV7P6hY>0X3Y2m9wj5c"), "persisted")

        ForkMixFiles.imported(text)
        assertEquals("Imported 0 mixes · 1 already saved as they are. Play these mixes queues them.", ForkMixFiles.status)

        val crossfade = ForkV2MixOverride.fromJson(mapOf("preset" to "custom", "lanes" to mapOf("volume" to "crossfade", "eq" to "none", "effect" to "none")))!!
        assertNull(ForkV2OverrideStore.put("rCwSRV7P6hY", "0X3Y2m9wj5c", crossfade))
        ForkMixFiles.imported(text)
        val c = ForkMixFiles.confirm
        assertNotNull(c, "a saved edit is replaced only after a confirm")
        assertEquals(
            "One pair already has an edit. Replace with the file's?\n\nCinderella → Drowning (feat. Kodak Black):\n" +
                "  volume: crossfade → drawn\n  eq: none → drawn\n  length: auto → 16 bars\n  beat match: auto → glide",
            c.text,
        )
        assertEquals("Imported 0 mixes · 1 kept as saved. Play these mixes queues them.", ForkMixFiles.applied(c.read, c.plan, replace = false))
        assertEquals("crossfade", ForkV2OverrideStore.get("rCwSRV7P6hY", "0X3Y2m9wj5c")!!.volume, "declined: the saved edit kept")
        assertEquals("Imported 1 mix. Play these mixes queues them.", ForkMixFiles.applied(c.read, c.plan, replace = true))
        assertNotNull(ForkV2OverrideStore.get("rCwSRV7P6hY", "0X3Y2m9wj5c")!!.drawing("volume"), "replaced")

        var name: String? = null
        var out: String? = null
        ForkMixFiles.saveAs = { n, t, done -> name = n; out = t; done(true) }
        runBlocking { ForkMixFiles.exportNow("rCwSRV7P6hY", "0X3Y2m9wj5c") }
        assertEquals("cinderella-drowningfeatkodakblack.vivimix.json", name)
        assertEquals("Exported 1 mix as cinderella-drowningfeatkodakblack.vivimix.json.", ForkMixFiles.status)
        assertEquals(text.replace("\r\n", "\n").trimEnd(), out!!.trimEnd(), "the export is the ready-made file (the round trip)")

        runBlocking { ForkMixFiles.exportNow("aaaaaaaaaaa", "bbbbbbbbbbb") }
        assertEquals("These two songs have no edit to export.", ForkMixFiles.status)
        ForkV2OverrideStore.reset("rCwSRV7P6hY", "0X3Y2m9wj5c")
        runBlocking { ForkMixFiles.exportNow() }
        assertEquals("No edited pairs to export.", ForkMixFiles.status)

        ForkMixFiles.imported("{")
        assertEquals("Not imported: not a Vivimusic mix file: it is not JSON.", ForkMixFiles.status)
    }

    /** A queue as the apps keep one: songs and the playing index; [restarted] when the playing song was replaced. */
    private class FakeQueue(val songs: MutableList<String>, var index: Int) : ForkMixFiles.Queue {
        var played: String? = null
        var restarted = false
        override fun refusal(): String? = null
        override fun playingId(): String? = songs.getOrNull(index)
        override fun fresh(songs: List<Song>, keep: Boolean) {
            restarted = !keep
            this.songs.clear(); this.songs.addAll(songs.map { it.videoId }); index = 0; played = this.songs[0]
        }
    }

    @Test
    fun playTheseMixesQueuesTheFileInItsOrder() {
        ForkV2OverrideStore.install(load = { null }, save = {})
        ForkMixFiles.lookup = { null }
        val text = File(specDir(), "automix/mixes/cinderella-drowning.vivimix.json").readText()
        ForkMixFiles.imported(text)
        assertEquals(listOf("rCwSRV7P6hY", "0X3Y2m9wj5c"), ForkMixFiles.lastImport!!.ids)

        // another song plays: a fresh queue of the two replaces it and what was queued, and Cinderella plays
        val q = FakeQueue(mutableListOf("xxxxxxxxxxx", "yyyyyyyyyyy"), 0)
        ForkMixFiles.queue = q
        assertEquals("Playing the mix: 2 songs in the queue, in the file's order.", runBlocking { ForkMixFiles.playNow() })
        assertEquals(listOf("rCwSRV7P6hY", "0X3Y2m9wj5c"), q.songs)
        assertEquals("rCwSRV7P6hY", q.played)
        assertTrue(q.restarted)
        // pressed again with Cinderella playing: the same fresh queue, Cinderella plays on
        q.songs.add("zzzzzzzzzzz")
        runBlocking { ForkMixFiles.playNow() }
        assertEquals(listOf("rCwSRV7P6hY", "0X3Y2m9wj5c"), q.songs)
        assertFalse(q.restarted, "the first song, playing, plays on")

        // nothing playing: the songs start from the first
        val empty = FakeQueue(mutableListOf(), -1)
        ForkMixFiles.queue = empty
        runBlocking { ForkMixFiles.playNow() }
        assertEquals(listOf("rCwSRV7P6hY", "0X3Y2m9wj5c"), empty.songs)
        assertEquals("rCwSRV7P6hY", empty.played)
    }

    /** A Spotify mix export (spotify-mixes.md): imported as it is, the summary's notes, and the playlist queued in its order. */
    @Test
    fun aSpotifyExportImportsAndQueuesItsPlaylist() {
        ForkV2OverrideStore.install(load = { null }, save = {})
        fun t(yt: String, title: String) = """{"uri":"spotify:track:$yt","youtubeVideoId":"$yt","title":"$title","artists":["X"],"durationMs":200000}"""
        val a = t("aaaaaaaaaaa", "A")
        val b = t("bbbbbbbbbbb", "B")
        val c = t("ccccccccccc", "C")
        val text = """{"format":"spotify-mix-export","version":3,"playlist":{"name":"P","tracks":[$a,$b,$c]},"mixes":[""" +
            """{"index":1,"from":$a,"to":$b,"fromStartMs":180000,"toStartMs":-2000,"durationMs":8000,"bars":4,"fromBpm":120,"style":{"volume":7,"eq":4,"filter":0}}]}"""
        ForkMixFiles.imported(text)
        assertEquals(
            "Imported 1 mix. Play these mixes queues them.",
            ForkMixFiles.status,
        )
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), ForkMixFiles.lastImport!!.ids, "the playlist, a song in no mix too")
        val q = FakeQueue(mutableListOf(), -1)
        ForkMixFiles.queue = q
        assertEquals("Playing the mix: 3 songs in the queue, in the playlist's order.", runBlocking { ForkMixFiles.playNow() })
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), q.songs)
    }

    @Test
    fun anExportLooksUpTheSongsItHasNotSeen() {
        ForkV2OverrideStore.install(load = { null }, save = {})
        val smooth = ForkV2MixOverride.fromJson(mapOf("preset" to "smooth"))!!
        assertNull(ForkV2OverrideStore.put("ppppppppppp", "qqqqqqqqqqq", smooth))
        ForkMixFiles.lookup = { id -> Song(videoId = id, title = "Song $id", artist = "Artist", thumbnailUrl = null) }
        var out: String? = null
        var name: String? = null
        ForkMixFiles.saveAs = { n, t, done -> name = n; out = t; done(true) }
        runBlocking { ForkMixFiles.exportNow("ppppppppppp", "qqqqqqqqqqq") }
        assertTrue(out!!.contains("\"title\": \"Song ppppppppppp\""), out)
        assertEquals("songppppppppppp-songqqqqqqqqqqq.vivimix.json", name)
    }

    @Test
    fun fileNamesAsTheExtensionMakesThem() {
        assertEquals("yquefue", ForkMixFiles.slug("Y Que Fue?"))
        assertEquals("iliketomoveit", ForkMixFiles.slug("I Like To Move It"))
        assertEquals("cancion", ForkMixFiles.slug("Canción"))
        assertEquals("abcdefghijklmnopqrstuvwx", ForkMixFiles.slug("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("song", ForkMixFiles.slug("夜に駆ける"))
    }

    /** The extension's spec/: RULEBOOK_SPEC, else /spec. Missing fails locally and is skipped on CI. */
    private fun specDir(): File {
        val dir = File(System.getenv("RULEBOOK_SPEC")?.takeIf { it.isNotBlank() } ?: "/spec")
        if (!File(dir, "automix/mixes/cinderella-drowning.vivimix.json").isFile) {
            org.junit.Assume.assumeTrue("the rulebook spec is not mounted on CI; skipped there", System.getenv("CI") != "true")
            fail("the extension's spec is not at $dir (set RULEBOOK_SPEC, or mount it at /spec): no automix/mixes/ there")
        }
        return dir
    }
}
