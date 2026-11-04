package com.music.bitchord.data.canvas

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork: the pure parts of ForkSpotifyTotpToken (the YT Music Extension's signed token request).
 * No network: the expected codes were computed from the algorithm itself (Python's hmac, and the
 * extension's own `makeTotp` in Node, which agree), and the RFC ones are RFC 6238's SHA-1 vectors.
 */
class ForkSpotifyTotpTokenTest {

    @Test
    fun `the HOTP core matches RFC 6238's SHA-1 vectors, six digits`() {
        val key = "12345678901234567890".toByteArray(Charsets.US_ASCII)
        assertEquals("287082", ForkSpotifyTotpToken.totp(key, 59))
        assertEquals("081804", ForkSpotifyTotpToken.totp(key, 1_111_111_109))
        assertEquals("279037", ForkSpotifyTotpToken.totp(key, 2_000_000_000))
    }

    @Test
    fun `the secret transform is XOR with i mod 33 plus 9, joined as decimal digits`() {
        val (_, secret) = ForkSpotifyTotpToken.FALLBACK_SECRET
        assertEquals(
            "98915853788388658481597337365767711075051",
            String(ForkSpotifyTotpToken.secretKey(secret), Charsets.US_ASCII),
        )
        assertEquals("888", String(ForkSpotifyTotpToken.secretKey(intArrayOf(1, 2, 3)), Charsets.US_ASCII))
    }

    @Test
    fun `secret v12 gives the known code at a fixed server time`() {
        val key = ForkSpotifyTotpToken.secretKey(ForkSpotifyTotpToken.FALLBACK_SECRET.second)
        assertEquals("165105", ForkSpotifyTotpToken.totp(key, 1_700_000_000))
        // 30 s steps: 1_700_000_010 starts the next one.
        assertEquals("518490", ForkSpotifyTotpToken.totp(key, 1_700_000_029))
        assertEquals("518490", ForkSpotifyTotpToken.totp(key, 1_700_000_030))
    }

    @Test
    fun `the newest secret version is picked from the list`() {
        val (version, bytes) = assertNotNull(
            ForkSpotifyTotpToken.parseSecretDict("""{"59":[1,2],"61":[3,4,5],"60":[6]}"""),
        )
        assertEquals(61, version)
        assertEquals(listOf(3, 4, 5), bytes.toList())
        assertNull(ForkSpotifyTotpToken.parseSecretDict("not json"))
        assertNull(ForkSpotifyTotpToken.parseSecretDict("{}"))
    }

    @Test
    fun `appServerConfig is read out of the page`() {
        val config = """{"clientVersion":"1.2.80.123.gabcdef12","clientId":"d8a5ed958d274c2e8ee717e6a4b0971d","other":1}"""
        val encoded = Base64.getEncoder().encodeToString(config.toByteArray())
        val html = """<html><head><script id="appServerConfig" type="text/plain">$encoded</script></head></html>"""
        val parsed = assertNotNull(ForkSpotifyTotpToken.parseAppServerConfig(html))
        assertEquals("1.2.80.123.gabcdef12", parsed.clientVersion)
        assertEquals("d8a5ed958d274c2e8ee717e6a4b0971d", parsed.clientId)
        assertNull(ForkSpotifyTotpToken.parseAppServerConfig("<html>no config</html>"))
    }

    @Test
    fun `server time and the token answer are read, anonymous tokens refused`() {
        assertEquals(1_700_000_000L, ForkSpotifyTotpToken.parseServerTime("""{"serverTime":1700000000}"""))
        assertNull(ForkSpotifyTotpToken.parseServerTime("""{}"""))

        val now = 1_000L
        val token = assertNotNull(
            ForkSpotifyTotpToken.parseToken(
                """{"accessToken":"abc","accessTokenExpirationTimestampMs":5000,"isAnonymous":false,"clientId":"cid"}""",
                now,
            ),
        )
        assertEquals("abc", token.accessToken)
        assertEquals(5_000L, token.expiresAtMs)
        assertEquals("cid", token.clientId)
        assertNull(ForkSpotifyTotpToken.parseToken("""{"accessToken":"abc","isAnonymous":true}""", now))
        assertNull(ForkSpotifyTotpToken.parseToken("""{"accessToken":""}""", now))
        // A missing or past expiry gets the hour Spotify gives.
        assertEquals(now + 3_600_000, ForkSpotifyTotpToken.parseToken("""{"accessToken":"abc"}""", now)?.expiresAtMs)
    }

    @Test
    fun `the token request carries the extension's parameters`() {
        val url = ForkSpotifyTotpToken.tokenUrl("165105", 12, 1_700_000_000, 1_700_000_000_123)
        assertTrue(url.startsWith("https://open.spotify.com/api/token?reason=transport&productType=web-player"))
        for (part in listOf("totp=165105", "totpVer=12", "totpServer=165105", "sTime=1700000000", "cTime=1700000000", "ts=1700000000123")) {
            assertTrue(part in url, "missing $part in $url")
        }
    }
}
