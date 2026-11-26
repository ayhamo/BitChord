package com.music.bitchord.data.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [ForkAddonStream.missOf]: protocol.md §4 ("`error`: the answer is a miss", `https` only) and §6
 * (a `/stream` miss is never cached). The clients throw the miss inside their shared call, so it is
 * dropped from the cache and the row's own `streamURL` is tried instead.
 */
class ForkAddonStreamMissTest {

    private val base = "https://addon.example/tok"

    @Test
    fun anErrorIsAMissEvenWithAUrl() {
        val miss = assertNotNull(ForkAddonStream.missOf("https://cdn.example/a.flac", "Deezer ARL required", base))
        assertEquals("Deezer ARL required", miss.message)
    }

    @Test
    fun aBlankUrlIsAMiss() {
        assertEquals("no stream", assertNotNull(ForkAddonStream.missOf("", null, base)).message)
        assertNotNull(ForkAddonStream.missOf("  ", "  ", base))
    }

    @Test
    fun aPlainHttpLinkFromARemoteAddonIsAMiss() {
        assertNotNull(ForkAddonStream.missOf("http://cdn.example/a.flac", null, base))
        assertNotNull(ForkAddonStream.missOf("http://127.0.0.1:9/a.flac", null, base), "a remote addon may not point at localhost")
    }

    @Test
    fun aCleanAnswerIsAHit() {
        assertNull(ForkAddonStream.missOf("https://cdn.example/a.flac", null, base))
        assertNull(ForkAddonStream.missOf("https://cdn.example/a.flac", " ", base), "a blank error says nothing")
        assertNull(ForkAddonStream.missOf("http://127.0.0.1:9/a.flac", null, "http://localhost:9"), "a local mock server")
    }
}
