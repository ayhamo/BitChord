package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: the debug bridge answers local tools only ([ForkDebugBridge.allowed]). */
class ForkDebugBridgeTest {

    private val port = 47123

    @Test
    fun curlAndPythonAreLetIn() {
        assertTrue(ForkDebugBridge.allowed(mapOf("Host" to listOf("127.0.0.1:$port"), "User-agent" to listOf("curl/8.4.0"), "Accept" to listOf("*/*")), port))
        assertTrue(ForkDebugBridge.allowed(mapOf("Host" to listOf("localhost:$port"), "Accept-encoding" to listOf("identity")), port))
        assertTrue(ForkDebugBridge.allowed(mapOf("host" to listOf("LocalHost:$port")), port))
    }

    @Test
    fun anotherHostIsRefused() {
        assertFalse(ForkDebugBridge.allowed(mapOf("Host" to listOf("rebind.evil.example:$port")), port))
        assertFalse(ForkDebugBridge.allowed(mapOf("Host" to listOf("127.0.0.1:${port + 1}")), port))
        assertFalse(ForkDebugBridge.allowed(mapOf("Host" to listOf("127.0.0.1")), port))
        assertFalse(ForkDebugBridge.allowed(emptyMap(), port))
    }

    @Test
    fun browserHeadersAreRefused() {
        val host = "Host" to listOf("127.0.0.1:$port")
        for (name in listOf("Origin", "Referer", "Sec-Fetch-Site", "Sec-fetch-mode", "sec-fetch-dest")) {
            assertFalse(ForkDebugBridge.allowed(mapOf(host, name to listOf("x")), port), name)
        }
    }
}
