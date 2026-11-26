package com.music.bitchord.data.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ForkAddonRefusals]: protocol.md 8.1.6 (never a manifest in the audio cache) and 8.1.4 (a refused
 * copy passes to the next row or lane), ported from the extension's `addon_file_cache_tests.mjs`
 * (baa5e1d, "NEXT" and the manifest guard).
 */
class ForkAddonRefusalsTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun aManifestIsToldByItsBytesOrItsType() {
        assertTrue(ForkAddonRefusals.isManifest(bytes("#EXTM3U\n#EXT-X-VERSION:3\n")), "an HLS playlist by its bytes")
        assertTrue(
            ForkAddonRefusals.isManifest(bytes("﻿<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">")),
            "a DASH manifest: BOM, xml header, then MPD",
        )
        assertTrue(ForkAddonRefusals.isManifest(bytes("<MPD>")), "MPD with no header")
        assertTrue(ForkAddonRefusals.isManifest(bytes("<mpd xmlns=\"x\">")), "lowercase")
        assertTrue(ForkAddonRefusals.isManifest(bytes("<?xml version=\"1.0\"?>\n<!-- made by x\n -->\n<MPD>")), "after a comment")
        assertTrue(ForkAddonRefusals.isManifest(bytes("#extm3u\n")), "a lowercase playlist tag")
        assertTrue(ForkAddonRefusals.isManifest(bytes("  \n#EXTM3U")), "leading whitespace")
        assertTrue(ForkAddonRefusals.isManifest(ByteArray(100), "application/dash+xml"), "a manifest by its type")
        assertTrue(ForkAddonRefusals.isManifest(null, "application/vnd.apple.mpegurl"))
        assertTrue(ForkAddonRefusals.isManifest(null, "audio/x-mpegURL"))
        assertFalse(ForkAddonRefusals.isManifest(bytes("fLaC" + "x".repeat(60)), "audio/flac"), "a FLAC file is kept")
        assertFalse(ForkAddonRefusals.isManifest(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())), "WebM")
        assertFalse(ForkAddonRefusals.isManifest(bytes("<?xml version=\"1.0\"?><MPDX>")), "not an MPD element")
        assertFalse(ForkAddonRefusals.isManifest(bytes("<html><MPD>")), "MPD not at the start")
        assertFalse(ForkAddonRefusals.isManifest(null, null))
        assertFalse(ForkAddonRefusals.isManifest(ByteArray(0), "audio/mp4"))
    }

    @Test
    fun aRefusedRowIsPassedOverAndTheSameAddonsNextRowCounts() {
        val book = ForkAddonRefusals.Book()
        book.opened("https://a/1", "A", "A-1")
        val rows = listOf("A-1", "A-2")
        assertEquals(rows, book.left("song", "A", rows) { it }, "nothing refused yet")
        val v = book.refuse("song", "https://a/1")
        assertEquals(ForkAddonRefusals.Row("A", "A-1"), v.row)
        assertTrue(v.next)
        assertEquals(listOf("A-2"), book.left("song", "A", rows) { it })
        assertEquals(listOf("B-1", "B-2"), book.left("song", "B", listOf("B-1", "B-2")) { it }, "the next lane keeps its rows")
        assertTrue(book.wasRefused("song", "https://a/1"))
        assertFalse(book.wasRefused("song", "https://a/2"))
        // per song: another song is not affected
        assertFalse(book.skips("other", "A", "A-1"))
        assertEquals(rows, book.left("other", "A", rows) { it })
    }

    @Test
    fun atMostFourMoreCopiesPerSongThenWhatPlaysStays() {
        val book = ForkAddonRefusals.Book()
        val verdicts = (1..5).map { i ->
            book.opened("u$i", "A", "A-$i")
            book.refuse("song", "u$i")
        }
        assertEquals(listOf(true, true, true, true, false), verdicts.map { it.next })
        assertEquals(5, book.refusals("song"))
        assertTrue(book.exhausted("song"))
        assertTrue(book.skips("song", "B", "B-1"), "an exhausted song opens no copy")
        assertEquals(emptyList(), book.left("song", "B", listOf("B-1")) { it })
        book.forget("song")
        assertFalse(book.skips("song", "A", "A-1"))
    }

    @Test
    fun anAddonsManifestAnswerIsRefusedAndTheNextRowOpens() {
        val book = ForkAddonRefusals.Book()
        book.opened("https://a/1.mpd", "A", "A-1")
        val v = book.refuseManifest("song", "https://a/1.mpd", isAddon = true, isManifest = true)
        assertEquals(ForkAddonRefusals.Row("A", "A-1"), v?.row)
        assertTrue(v!!.next)
        assertEquals(listOf("A-2"), book.left("song", "A", listOf("A-1", "A-2")) { it }, "the next ready row opens")
        assertNull(book.refuseManifest("song", "https://a/2.flac", isAddon = true, isManifest = false), "a file is kept")
        assertNull(book.refuseManifest("song", "https://m/1.m3u8", isAddon = false, isManifest = true), "a module's HLS is its normal answer")
        assertEquals(1, book.refusals("song"))
    }

    @Test
    fun aRefusalOfAnUnseenUrlStillCounts() {
        val book = ForkAddonRefusals.Book()
        val v = book.refuse("song", "never-opened")
        assertNull(v.row)
        assertEquals(1, v.refusals)
        assertTrue(book.wasRefused("song", "never-opened"))
    }

    @Test
    fun theBookIsBounded() {
        val book = ForkAddonRefusals.Book(cap = 2)
        listOf("s1", "s2", "s3").forEach { s -> book.opened("$s-u", "A", "$s-r"); book.refuse(s, "$s-u") }
        assertEquals(0, book.refusals("s1"))
        assertEquals(1, book.refusals("s3"))
    }

    @Test
    fun theSameUrlRefusedTwiceCountsOnce() {
        val book = ForkAddonRefusals.Book()
        book.opened("u1", "A", "A-1")
        book.refuse("song", "u1")
        assertEquals(1, book.refuse("song", "u1").refusals)
    }

    @Test
    fun aRowOutlivesManyOpensOfOtherSongs() {
        // Every search opens streams; with the row map at the song cap the refused row was forgotten
        // and opened again. It now keeps `cap * 8` URLs.
        val book = ForkAddonRefusals.Book(cap = 2)
        book.opened("u-mine", "A", "A-1")
        (1..10).forEach { book.opened("u-other-$it", "B", "B-$it") }
        assertEquals(ForkAddonRefusals.Row("A", "A-1"), book.refuse("song", "u-mine").row)
        assertEquals(listOf("A-2"), book.left("song", "A", listOf("A-1", "A-2")) { it })
    }

    @Test
    fun aSongsCountAndRowsGoTogether() {
        val book = ForkAddonRefusals.Book(cap = 2)
        book.opened("u1", "A", "A-1")
        book.refuse("s1", "u1")
        book.refuse("s2", "x"); book.refuse("s3", "y")
        assertEquals(0, book.refusals("s1"))
        assertFalse(book.skips("s1", "A", "A-1"), "evicted whole, not half")
    }

    @Test
    fun aWindowThatFailsOnceIsReadAgainBeforeItIsARefusal() {
        var reads = 0
        assertEquals("w", ForkAddonRefusals.copyWindow { reads++; if (reads == 1) null else "w" })
        assertEquals(2, reads)
        reads = 0
        assertNull(ForkAddonRefusals.copyWindow<String> { reads++; null })
        assertEquals(2, reads, "twice, then it is the copy's")
        reads = 0
        ForkAddonRefusals.copyWindow { reads++; "w" }
        assertEquals(1, reads)
    }

    @Test
    fun thePhonesSongKeyIgnoresCaseAndSpacing() {
        assertEquals(ForkAddonRefusals.songKey(" Poker Face ", "Lady Gaga"), ForkAddonRefusals.songKey("poker face", "LADY GAGA "))
        assertFalse(ForkAddonRefusals.songKey("A", "BC") == ForkAddonRefusals.songKey("AB", "C"))
    }
}
