package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: a station closed by its server is opened again, a few times a minute at most. */
class ForkRadioReconnectTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val station = Song(videoId = NavidromeRadio.ID_PREFIX + "1", title = "1LIVE", artist = "", thumbnailUrl = null)
    private val song = Song(videoId = "navidrome:1", title = "Song", artist = "Artist", thumbnailUrl = null)

    @AfterTest
    fun tearDown() {
        ForkRadioReconnect.reset()
        scope.cancel()
    }

    @Test
    fun aSongThatEndsIsLeftToEnd() {
        assertFalse(ForkRadioReconnect.closed(song, scope, { true }) { error("not a station") })
    }

    @Test
    fun aStationIsOpenedAgainWhileStillCurrent() {
        val reopened = CountDownLatch(1)
        assertTrue(ForkRadioReconnect.closed(station, scope, { true }) { reopened.countDown() })
        assertTrue(reopened.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aStationReplacedMeanwhileIsNotOpened() {
        var opened = 0
        val checked = CountDownLatch(1)
        assertTrue(ForkRadioReconnect.closed(station, scope, { checked.countDown(); false }) { opened++ })
        assertTrue(checked.await(5, TimeUnit.SECONDS))
        assertEquals(0, opened)
    }

    @Test
    fun aServerThatKeepsClosingIsLetGo() {
        repeat(5) { assertTrue(ForkRadioReconnect.closed(station, scope, { false }, now = 1_000L + it) {}) }
        assertFalse(ForkRadioReconnect.closed(station, scope, { false }, now = 2_000L) {})
        // A minute on, it may try again.
        assertTrue(ForkRadioReconnect.closed(station, scope, { false }, now = 70_000L) {})
    }
}
