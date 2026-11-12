package com.music.bitchord.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ForkAddonUserAgents]: hosts, agents, and the one browser retry when an addon refuses the app's agent. */
class ForkAddonUserAgentsTest {

    private class MapStore : ForkAddonUserAgents.Store {
        val map = mutableMapOf<String, String>()
        override fun picked(host: String) = map[host]
        override fun pick(host: String, presetId: String?) {
            if (presetId == null) map.remove(host) else map[host] = presetId
        }
    }

    private val url = "https://Tidotido.Example.org:443/abc123/manifest.json"

    @Test
    fun hostIsLowerCaseWithoutPortOrPath() {
        assertEquals("tidotido.example.org", ForkAddonUserAgents.hostOf(url))
        assertEquals("a.b", ForkAddonUserAgents.hostOf("http://user@a.b/x?y=1"))
        assertNull(ForkAddonUserAgents.hostOf("not a url"))
        assertEquals("[::1]", ForkAddonUserAgents.hostOf("http://[::1]:8080/manifest.json"))
    }

    @Test
    fun defaultIsTheAppsAgentAndBrowsersHaveNoBitChordInThem() {
        assertEquals("BitChord/v1", ForkAddonUserAgents.agentFor(null, "BitChord/v1"))
        assertEquals("BitChord/v1", ForkAddonUserAgents.agentFor("unknown", "BitChord/v1"))
        ForkAddonUserAgents.presets.filter { it.agent != null }.forEach {
            assertTrue(!it.agent!!.contains("bitchord", ignoreCase = true), it.id)
        }
    }

    @Test
    fun a403IsTriedOnceAsFirefoxAndTheChoiceKept() = runBlocking {
        val store = MapStore()
        val seen = mutableListOf<String?>()
        val result = ForkAddonUserAgents.retryingRefused(url, store) {
            seen += store.picked("tidotido.example.org")
            if (store.picked("tidotido.example.org") == null) Result.failure(Exception("HTTP 403")) else Result.success("ok")
        }
        assertEquals("ok", result.getOrNull())
        assertEquals(listOf(null, ForkAddonUserAgents.FIREFOX), seen)
        assertEquals(ForkAddonUserAgents.FIREFOX, store.map["tidotido.example.org"])
    }

    @Test
    fun aRefusalTheBrowserDoesntCureLeavesNothingStored() = runBlocking {
        val store = MapStore()
        var calls = 0
        val result = ForkAddonUserAgents.retryingRefused(url, store) { calls++; Result.failure<String>(Exception("HTTP 403")) }
        assertTrue(result.isFailure)
        assertEquals(2, calls)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun otherFailuresAndChosenAgentsAreNotRetried() = runBlocking {
        val store = MapStore()
        var calls = 0
        ForkAddonUserAgents.retryingRefused(url, store) { calls++; Result.failure<String>(Exception("HTTP 500")) }
        assertEquals(1, calls)
        store.map["tidotido.example.org"] = ForkAddonUserAgents.EDGE
        ForkAddonUserAgents.retryingRefused(url, store) { calls++; Result.failure<String>(Exception("HTTP 403")) }
        assertEquals(2, calls)
        assertEquals(ForkAddonUserAgents.EDGE, store.map["tidotido.example.org"])
    }
}
