package com.music.bitchord.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Fork: answers read to a cap, an empty one an error ([ForkCappedBody], [ForkCappedHttp]); and [forkLruMap]'s bound. */
class ForkCappedBodyTest {

    @Test
    fun aStreamUpToTheCapIsReadWhole() {
        assertEquals(10, ForkCappedBody.read(ByteArrayInputStream(ByteArray(10)), 10).size)
        assertEquals("héllo", ForkCappedBody.text(ByteArrayInputStream("héllo".toByteArray()), 64))
    }

    @Test
    fun aStreamOverTheCapOrEmptyThrows() {
        assertFailsWith<IOException> { ForkCappedBody.read(ByteArrayInputStream(ByteArray(11)), 10) }
        assertFailsWith<IOException> { ForkCappedBody.read(ByteArrayInputStream(ByteArray(0)), 10) }
    }

    private fun okhttp(body: String, contentType: String = "application/json; charset=utf-8"): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.test/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()

    @Test
    fun anOkHttpAnswerIsCapped() {
        assertEquals("{\"a\":1}", ForkCappedBody.text(okhttp("{\"a\":1}"), 64))
        assertFailsWith<IOException> { ForkCappedBody.text(okhttp("x".repeat(65)), 64) }
        assertFailsWith<IOException> { ForkCappedBody.text(okhttp(""), 64) }
    }

    private fun jdk(body: ByteArray, headers: Map<String, List<String>> = emptyMap()): HttpResponse<InputStream> =
        object : HttpResponse<InputStream> {
            override fun statusCode() = 200
            override fun request(): HttpRequest = HttpRequest.newBuilder(URI("https://example.test/")).build()
            override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
            override fun headers(): HttpHeaders = HttpHeaders.of(headers) { _, _ -> true }
            override fun body(): InputStream = ByteArrayInputStream(body)
            override fun sslSession(): Optional<SSLSession> = Optional.empty()
            override fun uri(): URI = URI("https://example.test/")
            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }

    @Test
    fun aJavaNetHttpAnswerIsCapped() {
        assertEquals("#EXTM3U", ForkCappedHttp.text(jdk("#EXTM3U".toByteArray()), 64))
        assertFailsWith<IOException> { ForkCappedHttp.text(jdk(ByteArray(65) { 'x'.code.toByte() }), 64) }
        assertFailsWith<IOException> { ForkCappedHttp.text(jdk(ByteArray(0)), 64) }
        // a declared length past the cap is refused before reading
        assertFailsWith<IOException> { ForkCappedHttp.text(jdk("ok".toByteArray(), mapOf("Content-Length" to listOf("1000"))), 64) }
    }

    @Test
    fun theLruMapKeepsAtMostItsCapLeastRecentlyUsedFirst() {
        val map = forkLruMap<String, Int>(3)
        map["a"] = 1; map["b"] = 2; map["c"] = 3
        map["a"] // a use: "b" is now the eldest
        map["d"] = 4
        assertEquals(setOf("a", "c", "d"), synchronized(map) { map.keys.toSet() })
        val set = forkLruSet<Int>(2)
        (1..5).forEach { set.add(it) }
        assertEquals(setOf(4, 5), synchronized(set) { set.toSet() })
    }
}
