package com.music.bitchord.desktop

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork: the PC's hidden Spotify page ([ForkSpotifyToken]): the bridge only on the web player itself,
 * and only spotify.com's cookies dropped from the WebView jar after a mint or on Disconnect.
 */
class ForkSpotifyPageTest {

    @Test
    fun onlyTheWebPlayerOverHttpsIsThePlayer() {
        assertTrue(ForkSpotifyPage.isPlayer("https://open.spotify.com/"))
        assertTrue(ForkSpotifyPage.isPlayer("https://open.spotify.com/track/abc?si=1"))
        assertFalse(ForkSpotifyPage.isPlayer("http://open.spotify.com/"))
        assertFalse(ForkSpotifyPage.isPlayer("https://accounts.spotify.com/login"))
        assertFalse(ForkSpotifyPage.isPlayer("https://open.spotify.com.evil.example/"))
        assertFalse(ForkSpotifyPage.isPlayer("https://evil.example/?u=https://open.spotify.com/"))
        assertFalse(ForkSpotifyPage.isPlayer("https://user@open.spotify.com/"))
        assertFalse(ForkSpotifyPage.isPlayer("https://open.spotify.com:8443/"))
        assertFalse(ForkSpotifyPage.isPlayer("about:blank"))
        assertFalse(ForkSpotifyPage.isPlayer(null))
    }

    @Test
    fun onlySpotifysCookiesAreDropped() {
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val player = URI("https://open.spotify.com/")
        val youtube = URI("https://music.youtube.com/")
        jar.put(player, mapOf("Set-Cookie" to listOf("sp_dc=test; Domain=.spotify.com; Path=/; Secure")))
        jar.put(player, mapOf("Set-Cookie" to listOf("sp_t=device; Path=/; Secure")))
        jar.put(youtube, mapOf("Set-Cookie" to listOf("PREF=keep; Domain=.youtube.com; Path=/; Secure")))
        assertEquals(2, jar.cookieStore.cookies.count { it.name.startsWith("sp_") })

        ForkSpotifyPage.dropCookies(jar)

        assertEquals(emptyList(), jar.get(player, emptyMap())["Cookie"].orEmpty())
        assertEquals(listOf("PREF"), jar.cookieStore.cookies.map { it.name })
    }
}
