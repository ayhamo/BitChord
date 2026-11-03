package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ForkShelfKeysTest {
    private fun song(title: String, id: String? = null) = ShelfItem(title, "", null, videoId = id, browseId = null)
    private fun playlist(id: String) = ShelfItem(id, "", null, videoId = null, browseId = id)

    @Test
    fun `same-titled songs without ids get distinct keys`() {
        val keys = forkShelfKeys(listOf(song("Intro"), song("Intro"), song("Intro")))
        assertEquals(listOf("card:Intro", "card:Intro#1", "card:Intro#2"), keys)
    }

    @Test
    fun `a reorder keeps every card's key`() {
        val a = playlist("VLa")
        val b = playlist("VLb")
        val c = song("Song", "vid")
        val before = listOf(a, b, c).zip(forkShelfKeys(listOf(a, b, c))).toMap()
        val after = listOf(c, a, b).zip(forkShelfKeys(listOf(c, a, b))).toMap()
        assertEquals(before, after)
    }
}
