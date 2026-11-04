package com.music.bitchord.data.navidrome

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import java.io.File
import java.nio.file.Files

/**
 * [NavidromeOffline.init] reads the folder and the manifest off the caller's thread; whatever asks
 * what is kept straight after still gets it, by waiting for that read.
 */
class NavidromeOfflineInitTest {
    @Test
    fun `what is kept is answered right after init, read off the caller's thread`() {
        val dir = Files.createTempDirectory("offline").toFile()
        try {
            File(dir, "s1").writeText("audio")
            File(dir, "s1.part").writeText("half")
            File(dir, "offline.json").writeText(
                """[{"id":"${NavidromePlaylists.PREFIX}a1","title":"Album","subtitle":"Artist",""" +
                    """"songs":[{"videoId":"${NavidromeSettings.ID_PREFIX}s1","title":"One","artist":"Artist"}]}]""",
            )
            val caller = Thread.currentThread()
            var row: Thread? = null
            NavidromePlatform.onKeptRow = { row = Thread.currentThread() }
            NavidromeOffline.init(dir, "Available offline", "Deleted on server")

            val songs = NavidromeOffline.keptSongs()
            assertEquals(listOf("${NavidromeSettings.ID_PREFIX}s1"), songs.map { it.videoId })
            assertTrue(songs.single().localUri!!.startsWith("file:///"))
            assertEquals(1, NavidromeOffline.pages.value.size)
            assertTrue(row != null && row !== caller, "the read ran off the caller's thread")
            assertTrue(!File(dir, "s1.part").exists(), "a dead download's .part is cleared")
        } finally {
            NavidromePlatform.onKeptRow = {}
            dir.deleteRecursively()
        }
    }
}
