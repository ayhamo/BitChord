package com.music.bitchord.data.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** java.net.URI threw on these, and the unread scheme let them through (audit 2026-11-26 2b #4). */
    @Test
    fun aPlainHttpLinkWithUnencodedCharactersIsStillAMiss() {
        assertNotNull(ForkAddonStream.missOf("http://cdn.example/01 Track [FLAC].flac", null, base))
        assertNotNull(ForkAddonStream.missOf("HTTP://cdn.example/a|b{c}.flac", null, base))
        assertNull(ForkAddonStream.missOf("https://cdn.example/01 Track [FLAC].flac", null, base))
    }

    /** protocol.md §4 `url`: "https only: any other link is a miss". */
    @Test
    fun anyOtherSchemeIsAMiss() {
        assertNotNull(ForkAddonStream.missOf("ftp://cdn.example/a.flac", null, base))
        assertNotNull(ForkAddonStream.missOf("file:///sdcard/a.flac", null, base))
        assertNotNull(ForkAddonStream.missOf("cdn.example/a.flac", null, base))
    }

    @Test
    fun aCleanAnswerIsAHit() {
        assertNull(ForkAddonStream.missOf("https://cdn.example/a.flac", null, base))
        assertNull(ForkAddonStream.missOf("https://cdn.example/a.flac", " ", base), "a blank error says nothing")
        assertNull(ForkAddonStream.missOf("http://127.0.0.1:9/a.flac", null, "http://localhost:9"), "a local mock server")
    }

    /** A malformed stream link is logged by its host only, never its key or token (AddonSource.openable's fork hook). */
    @Test
    fun aMalformedLinkIsLoggedByItsHostOnly() {
        assertEquals("cdn.example", ForkAddonStream.shownHost("https://cdn.example/KEY123/track?token=SECRET"))
        assertEquals("cdn.example:8443", ForkAddonStream.shownHost("https://user:pass@cdn.example:8443/a b|c.flac#x"))
        assertEquals("cdn.example", ForkAddonStream.shownHost("cdn.example/KEY123"))
        assertEquals("cdn.example", ForkAddonStream.shownHost("https://cdn.example?token=SECRET"))
        assertEquals("a link with no host", ForkAddonStream.shownHost("https:///KEY123"))
        for (u in listOf("https://cdn.example/KEY123/track?token=SECRET", "https://u:SECRET@cdn.example/KEY123")) {
            val shown = ForkAddonStream.shownHost(u)
            assertTrue("SECRET" !in shown && "KEY123" !in shown, shown)
        }
    }

    /** protocol.md §1: a remote addon may not point the player at this machine, over https either. */
    @Test
    fun httpsToThisMachineFromARemoteAddonIsAMiss() {
        for (u in listOf("https://127.0.0.1/a.flac", "https://localhost:8443/a.flac", "https://2130706433/a.flac", "https://[::ffff:127.0.0.1]/a.flac", "https://127.1/a"))
            assertNotNull(ForkAddonStream.missOf(u, null, base), u)
        assertNull(ForkAddonStream.missOf("https://127.0.0.1:9/a.flac", null, "http://localhost:9"), "a local addon's own")
        assertNull(ForkAddonStream.missOf("https://cdn.example:8443/a.flac", null, base))
    }
}
