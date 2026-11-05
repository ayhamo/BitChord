package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** What the Navidrome code asks of the server, offline and when it is down; and how many requests at once. */
class NavidromeServerRulesTest {

    private class MemoryStore : NavidromePlatform.Store {
        private val values = HashMap<String, String>()
        override fun string(key: String) = values[key]
        override fun long(key: String) = values[key]?.toLongOrNull() ?: 0L
        override fun putStrings(values: Map<String, String>) { this.values += values }
        override fun putLong(key: String, value: Long) { values[key] = value.toString() }
        override fun keys(): Set<String> = values.keys.toSet()
        override fun remove(keys: Collection<String>) { keys.forEach { values.remove(it) } }
        override fun clear() = values.clear()
    }

    private val kept = Song(videoId = NavidromeSettings.ID_PREFIX + "k1", title = "Kept", artist = "A", thumbnailUrl = null)

    private fun login(server: MockWebServer) {
        NavidromeSettings.init(MemoryStore())
        NavidromeSettings.save(server.url("/").toString(), "me", "not-a-real-password")
    }

    @AfterTest
    fun restore() {
        NavidromePlatform.kept = object : NavidromePlatform.Kept {}
        NavidromePlatform.offline = MutableStateFlow(false)
        NavidromePlatform.onGenres = {}
        runCatching { NavidromeSettings.clear() }
    }

    @Test
    fun `the server down on a working network shows the kept songs, and the error when none are kept`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        try {
            server.start()
            login(server)
            NavidromePlatform.kept = object : NavidromePlatform.Kept {
                override fun keptSongs() = listOf(kept)
            }
            assertEquals(listOf(kept), NavidromeRepository.getSongs())
            NavidromePlatform.kept = object : NavidromePlatform.Kept {}
            try {
                NavidromeRepository.getSongs()
                fail("nothing kept: the error stands")
            } catch (expected: java.io.IOException) {
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `offline the lyrics don't ask the server`() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            login(server)
            NavidromePlatform.offline = MutableStateFlow(true)
            assertNull(NavidromeLyrics.forSong("offline-song-1"))
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the login goes only to this server's own path`() {
        val client = NavidromeClient("https://music.example.test/navidrome", "me", "not-a-real-password", "abc")
        assertTrue("t=" in client.withLogin("https://music.example.test/navidrome/rest/stream?id=1"))
        // Another app behind the same host and port.
        assertEquals("https://music.example.test/other/rest/stream?id=1", client.withLogin("https://music.example.test/other/rest/stream?id=1"))
        assertEquals("https://elsewhere.test/navidrome/rest/stream?id=1", client.withLogin("https://elsewhere.test/navidrome/rest/stream?id=1"))
        val atRoot = NavidromeClient("https://music.example.test", "me", "not-a-real-password", "abc")
        assertTrue("t=" in atRoot.withLogin("https://music.example.test/rest/getCoverArt?id=mf-1"))
    }

    @Test
    fun `genre tiles look their covers up a few at a time`() = runBlocking {
        val server = MockWebServer()
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val genres = (1..30).joinToString(",") { """{"value":"Genre $it","songCount":${100 - it}}""" }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if ("getGenres" in path) {
                    return MockResponse().setBody("""{"subsonic-response":{"status":"ok","genres":{"genre":[$genres]}}}""")
                }
                val now = inFlight.incrementAndGet()
                most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(60)
                inFlight.decrementAndGet()
                return MockResponse().setBody("""{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}""")
            }
        }
        try {
            server.start()
            var tiles = 0
            NavidromePlatform.onGenres = { tiles = it.size }
            NavidromeGenres.load(NavidromeClient(server.url("/").toString(), "me", "not-a-real-password", "abc"))
            assertEquals(30, tiles)
            assertTrue(most.get() <= NavidromeGenres.COVER_LOOKUPS, "at most ${NavidromeGenres.COVER_LOOKUPS} at once, saw ${most.get()}")
        } finally {
            server.shutdown()
        }
    }
}
