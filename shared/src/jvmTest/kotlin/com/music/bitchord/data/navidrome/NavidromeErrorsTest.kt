package com.music.bitchord.data.navidrome

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The login test's failures read as what to fix, on the phone and the PC (both call testConnection). */
class NavidromeErrorsTest {

    private fun failure(url: String): String = runBlocking {
        val result = NavidromeRepository.testConnection(url, "me", "not-a-real-password")
        assertTrue(result.isFailure, "expected a failure for $url")
        result.exceptionOrNull()!!.message!!
    }

    private fun withServer(response: MockResponse, block: (String) -> Unit) {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try {
            block(server.url("/").toString().trimEnd('/'))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aClosedPortSaysNothingAnswered() {
        val port = ServerSocket(0).use { it.localPort }
        val message = failure("http://127.0.0.1:$port")
        assertTrue(message.startsWith("Nothing answered at that address"), message)
    }

    @Test
    fun aTailscaleAddressAlsoMentionsTailscale() {
        val message = NavidromeErrors.describe(IOException(java.net.ConnectException("Failed to connect")), "100.88.1.2:4533")
        assertTrue(message.endsWith("and that Tailscale is on"), message)
        val lan = NavidromeErrors.describe(java.net.ConnectException("Failed to connect"), "192.168.1.5:4533")
        assertTrue(!lan.contains("Tailscale"), lan)
    }

    @Test
    fun httpsToAPlainHttpServerSuggestsHttp() {
        withServer(MockResponse().setBody("{}")) { url ->
            val message = failure(url.replaceFirst("http://", "https://"))
            assertTrue(message.startsWith("Couldn't make a secure (https) connection"), message)
            assertTrue(message.contains("http://"), message)
        }
    }

    @Test
    fun aWrongPasswordKeepsTheServersOwnWords() {
        val body = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
        withServer(MockResponse().setBody(body)) { url ->
            assertEquals("Wrong username or password", failure(url))
        }
    }

    @Test
    fun aWrongPathSaysNoNavidromeThere() {
        withServer(MockResponse().setResponseCode(404)) { url ->
            assertTrue(failure(url).startsWith("No Navidrome at that address"))
        }
    }

    @Test
    fun anotherWebPageSaysNotNavidrome() {
        withServer(MockResponse().setBody("<html><body>Router login</body></html>")) { url ->
            assertTrue(failure(url).startsWith("Something answered at that address, but not Navidrome"))
        }
        withServer(MockResponse().setBody("""{"hello":"world"}""")) { url ->
            assertTrue(failure(url).startsWith("Something answered at that address, but not Navidrome"))
        }
    }

    @Test
    fun aServerErrorSaysTryAgain() {
        withServer(MockResponse().setResponseCode(502)) { url ->
            assertEquals("The server had an error (HTTP 502). Try again in a moment", failure(url))
        }
    }

    @Test
    fun notAnAddress() {
        assertTrue(failure("not a url").startsWith("That isn't a server address"))
        assertTrue(failure("").startsWith("That isn't a server address"))
    }

    @Test
    fun theOtherFailuresByType() {
        assertTrue(
            NavidromeErrors.describe(java.net.UnknownHostException("music.nowhere"), "music.nowhere")
                .startsWith("Can't find a server called music.nowhere"),
        )
        assertTrue(
            NavidromeErrors.describe(UnknownServiceException("CLEARTEXT communication to 192.168.1.5 not permitted by network security policy"), "http://192.168.1.5")
                .startsWith("This version of the app can't use http"),
        )
        assertTrue(NavidromeErrors.describe(SocketTimeoutException("timeout"), "192.168.1.5").startsWith("The server didn't answer in time"))
        assertEquals("The server turned the login away (HTTP 401)", NavidromeErrors.describe(IOException("HTTP 401"), "music.example.com"))
        assertEquals("Something odd", NavidromeErrors.describe(IOException("Something odd"), "music.example.com"))
        assertEquals("Couldn't connect", NavidromeErrors.describe(IOException(), "music.example.com"))
    }
}
