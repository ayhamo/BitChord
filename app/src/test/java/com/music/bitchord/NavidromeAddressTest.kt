package com.music.bitchord

import com.music.bitchord.data.navidrome.NavidromeClient
import com.music.bitchord.data.navidrome.NavidromeRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A song's addresses go everywhere (the media session, the cache index, logs): none may hold the login. */
class NavidromeAddressTest {

    private val client = NavidromeClient("https://music.example.com", "someone", "not-a-real-password", "abc123")

    private fun assertNoLogin(url: String) {
        val parsed = url.toHttpUrl()
        assertNull(parsed.queryParameter("u"))
        assertNull(parsed.queryParameter("t"))
        assertNull(parsed.queryParameter("s"))
    }

    @Test
    fun `stream and cover addresses carry no login`() {
        assertNoLogin(client.streamUrl("song1"))
        assertNoLogin(client.coverUrl("mf-song1"))
    }

    @Test
    fun `the login is added for this server's rest addresses`() {
        val signed = client.withLogin(client.streamUrl("song1")).toHttpUrl()
        assertEquals("someone", signed.queryParameter("u"))
        assertEquals("abc123", signed.queryParameter("s"))
        assertEquals("song1", signed.queryParameter("id"))
    }

    @Test
    fun `the login never goes anywhere else`() {
        val other = "https://elsewhere.example.org/rest/stream?id=1"
        assertEquals(other, client.withLogin(other))
        val clip = "https://music.example.com/rest/x-clip/a.mp4"
        assertEquals(clip, client.withLogin(clip))
        val page = "https://music.example.com/app/"
        assertEquals(page, client.withLogin(page))
    }

    @Test
    fun `an address saved with its login loses it and keeps the rest`() {
        val signed = client.withLogin(client.coverUrl("mf-song1", 300))
        val clean = NavidromeRepository.atRest(signed)!!
        assertNoLogin(clean)
        assertEquals("mf-song1", clean.toHttpUrl().queryParameter("id"))
        assertEquals("300", clean.toHttpUrl().queryParameter("size"))
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", NavidromeRepository.atRest("https://i.ytimg.com/vi/x/hq.jpg"))
        assertNull(NavidromeRepository.atRest(null))
    }
}
