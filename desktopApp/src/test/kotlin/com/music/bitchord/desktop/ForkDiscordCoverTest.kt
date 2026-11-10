package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeDiscordCover
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The PC's Discord pushes with their cover ([ForkDiscordCoverQueue]): in order on the serial scope
 * DesktopDiscordRpc uses, and never an older track's push after a newer one or after a clear.
 */
class ForkDiscordCoverTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun song(id: String) = Song(videoId = id, title = id, artist = "A", thumbnailUrl = null)

    private val pushed = Collections.synchronizedList(mutableListOf<Pair<String, ForkDiscordArt>>())

    /** Lets lookups finish, then waits until everything queued on [serial] so far has run. */
    private suspend fun settle() {
        delay(150)
        val done = CompletableDeferred<Unit>()
        serial.launch { done.complete(Unit) }
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun aKnownCoverIsPushedAtOnceWithTheTimeTheStateCameIn() = runBlocking {
        val queue = ForkDiscordCoverQueue(
            px = 480,
            known = { _, _ -> NavidromeDiscordCover.Answer.Ready("https://i.ytimg.com/vi/x/hqdefault.jpg") },
            lookUp = { _, _ -> error("not asked") },
            clock = { 1_000L },
        )
        queue.then(serial, song("yt1")) { pushed += "yt1" to it }
        settle()
        assertEquals(listOf("yt1" to ForkDiscordArt("https://i.ytimg.com/vi/x/hqdefault.jpg", 1_000L)), pushed.toList())
    }

    @Test
    fun aServerSongWaitsForItsLookupOffTheSerialScope() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val queue = ForkDiscordCoverQueue(
            px = 480,
            known = { _, _ -> NavidromeDiscordCover.Answer.Pending },
            lookUp = { _, _ -> gate.await(); "https://is1-ssl.mzstatic.com/a/480x480bb.jpg" },
            clock = { 5L },
        )
        queue.then(serial, song("navidrome:1")) { pushed += "navidrome:1" to it }
        // The serial scope is free while the lookup runs: another push there isn't held up.
        val other = CompletableDeferred<Unit>()
        serial.launch { other.complete(Unit) }
        withTimeout(2_000) { other.await() }
        assertTrue(pushed.isEmpty())
        gate.complete(Unit)
        withTimeout(5_000) { while (pushed.isEmpty()) delay(10) }
        assertEquals(listOf("navidrome:1" to ForkDiscordArt("https://is1-ssl.mzstatic.com/a/480x480bb.jpg", 5L)), pushed.toList())
    }

    @Test
    fun aLookupThatLandsAfterTheNextTrackIsDropped() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val queue = ForkDiscordCoverQueue(
            px = 480,
            known = { s, _ -> if (s.videoId.startsWith("navidrome:")) NavidromeDiscordCover.Answer.Pending else NavidromeDiscordCover.Answer.Ready(null) },
            lookUp = { _, _ -> gate.await(); "https://is1-ssl.mzstatic.com/old.jpg" },
        )
        queue.then(serial, song("navidrome:old")) { pushed += "navidrome:old" to it }
        queue.then(serial, song("yt-new")) { pushed += "yt-new" to it }
        settle()
        gate.complete(Unit)
        settle()
        assertEquals(listOf("yt-new"), pushed.map { it.first })
    }

    @Test
    fun aClearWhileTheLookupRunsKeepsTheCardDown() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val queue = ForkDiscordCoverQueue(
            px = 480,
            known = { _, _ -> NavidromeDiscordCover.Answer.Pending },
            lookUp = { _, _ -> gate.await(); "https://is1-ssl.mzstatic.com/a.jpg" },
        )
        queue.then(serial, song("navidrome:1")) { pushed += "navidrome:1" to it }
        queue.drop()
        gate.complete(Unit)
        settle()
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun aFailedLookupStillPushesWithoutACover() = runBlocking {
        val queue = ForkDiscordCoverQueue(
            px = 480,
            known = { _, _ -> NavidromeDiscordCover.Answer.Pending },
            lookUp = { _, _ -> throw java.io.IOException("offline") },
        )
        queue.then(serial, song("navidrome:1")) { pushed += "navidrome:1" to it }
        withTimeout(5_000) { while (pushed.isEmpty()) delay(10) }
        assertEquals(null, pushed.single().second.url)
    }

    @Test
    fun theRealGuardKeepsAServerCoverAwayFromDiscord() {
        val server = Song(
            videoId = "navidrome:1",
            title = "t",
            artist = "a",
            thumbnailUrl = "http://192.168.0.214:4533/rest/getCoverArt?id=mf-1&size=600",
        )
        assertEquals(NavidromeDiscordCover.Answer.Pending, NavidromeDiscordCover.now(server, 480))
        val webdav = server.copy(videoId = "webdav:1", thumbnailUrl = "http://192.168.0.214/dav/cover.jpg")
        // A share's song never sends its own cover: it waits on the public lookup as a server song does.
        assertEquals(NavidromeDiscordCover.Answer.Pending, NavidromeDiscordCover.now(webdav, 480))
        assertFalse(NavidromeDiscordCover.isPublic("https://cdn.example.com/a.jpg?u=me&t=x&s=y"))
    }
}
