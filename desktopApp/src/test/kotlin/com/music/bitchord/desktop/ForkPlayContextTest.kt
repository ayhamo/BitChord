package com.music.bitchord.desktop

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.ContextSnapshot
import com.music.bitchord.playback.ContinuationPage
import com.music.bitchord.playback.ContinuationState
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.QueueTimeline
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

class ForkPlayContextTest {

    private val source = QueueSource("Long list", PlaybackSourceType.BROWSE, "VLlong")

    private fun song(id: String, slot: String? = null) = Song(id, id.uppercase(), "Artist", null, setVideoId = slot)

    private fun songs(vararg ids: String) = ids.map { song(it, "s-$it") }

    /** The window's queue state, and the hooks [ForkPlayContext] reads and writes it through. */
    private class Window(var queue: DesktopQueue, var shuffle: Boolean = false) {
        var party = false
        var preShuffle: List<String> = emptyList()
        val mirrored = mutableListOf<ContextSnapshot>()
        val hooks = ForkPlayContext.Hooks(
            queue = { queue },
            shuffleOn = { shuffle },
            prepare = { it },
            apply = { grown, pre ->
                queue = grown
                preShuffle = pre(preShuffle)
            },
            mirror = { mirrored += it },
            inParty = { party },
        )
    }

    /** As the window's playSongs builds it: the page around the pick, in the page's order. */
    private fun started(page: List<Song>, at: Int): DesktopQueue {
        val built = QueueTimeline.buildContextQueue(emptyList(), 0, page, at, source)
        return DesktopQueue.startingAt(built.timeline, built.startIndex)
    }

    private fun pages(vararg answers: ContinuationPage?): Pair<MutableList<String>, suspend (String) -> ContinuationPage?> {
        val asked = mutableListOf<String>()
        var next = 0
        val loader: suspend (String) -> ContinuationPage? = { token ->
            asked += token
            answers.getOrNull(next++)
        }
        return asked to loader
    }

    private fun CoroutineScope.context(loader: suspend (String) -> ContinuationPage?) =
        ForkPlayContext(this, loader, io = EmptyCoroutineContext, retryDelaysMs = listOf(0L, 0L))

    @Test
    fun playFollowsEveryLaterPageIntoTheQueue() = runBlocking {
        val page = songs("a", "b", "c")
        val window = Window(started(page, 1))
        val (asked, loader) = pages(
            ContinuationPage(songs("d", "e"), "t2"),
            ContinuationPage(songs("f"), null),
        )
        val context = context(loader)
        context.hooks = window.hooks
        context.start("VLlong", page, window.queue.current!!, "t1", source, shuffled = false, started = window.queue)
        context.awaitWalk()

        assertEquals(listOf("t1", "t2"), asked)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), window.queue.songs.map { it.videoId })
        assertEquals("b", window.queue.current?.videoId)
        assertTrue(window.queue.songs.all { it.queueTier == QueueTier.CONTEXT && it.queueEntryId != null })
        assertTrue(window.queue.songs.drop(3).all { it.playbackSource == "Long list" && it.playbackSourceId == "VLlong" })
        val last = window.mirrored.last()
        assertEquals(6, last.songs.size)
        assertEquals(ContinuationState.EXHAUSTED, last.continuationState)
    }

    @Test
    fun aPartyTakingTheQueueStopsTheWalkBeforeAnyPageIsSpliced() = runBlocking {
        val page = songs("a", "b")
        val window = Window(started(page, 0))
        window.party = true // joined after Play: the adopted queue still holds this context's rows
        val (_, loader) = pages(ContinuationPage(songs("c"), "t2"), ContinuationPage(songs("d"), null))
        val context = context(loader)
        context.hooks = window.hooks
        context.start("VLlong", page, window.queue.current!!, "t1", source, shuffled = false, started = window.queue)
        context.awaitWalk()

        assertEquals(listOf("a", "b"), window.queue.songs.map { it.videoId })
        assertNull(context.snapshot()?.continuationToken)
    }

    @Test
    fun aSongListedTwiceStaysTwiceButARepeatedRowDoesNot() = runBlocking {
        // The same video in two playlist rows (two setVideoIds) is two rows; the same row arriving
        // again on a later page is one, as the phone's registry has it.
        val page = listOf(song("a", "s1"), song("b", "s2"), song("a", "s3"))
        val window = Window(started(page, 0))
        val (_, loader) = pages(ContinuationPage(listOf(song("b", "s2"), song("a", "s4"), song("c", "s5")), null))
        val context = context(loader)
        context.hooks = window.hooks
        context.start("VLlong", page, window.queue.current!!, "t1", source, shuffled = false, started = window.queue)
        context.awaitWalk()

        assertEquals(listOf("a", "b", "a", "a", "c"), window.queue.songs.map { it.videoId })
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), window.queue.songs.map { it.setVideoId })
    }

    @Test
    fun aPageWithNothingNewStopsTheWalkInsteadOfLooping() = runBlocking {
        val page = songs("a", "b")
        val window = Window(started(page, 0))
        val (asked, loader) = pages(
            ContinuationPage(songs("a", "b"), "t2"),
            ContinuationPage(songs("c"), null),
        )
        val context = context(loader)
        context.hooks = window.hooks
        context.start("VLlong", page, window.queue.current!!, "t1", source, shuffled = false, started = window.queue)
        context.awaitWalk()

        assertEquals(listOf("t1"), asked)
        assertEquals(listOf("a", "b"), window.queue.songs.map { it.videoId })
        assertEquals(ContinuationState.BLOCKED, context.snapshot()?.continuationState)
    }

    @Test
    fun laterPagesGoAheadOfAutoplayAlreadyQueued() = runBlocking {
        val page = songs("a", "b")
        val radio = Song("r1", "R1", "Artist", null, fromAutoplay = true).asQueueEntry(QueueTier.AUTOPLAY)
        val window = Window(started(page, 0).append(listOf(radio)))
        val (_, loader) = pages(ContinuationPage(songs("c", "d"), null))
        val context = context(loader)
        context.hooks = window.hooks
        context.start("VLlong", page, window.queue.current!!, "t1", source, shuffled = false, started = window.queue)
        context.awaitWalk()

        assertEquals(listOf("a", "b", "c", "d", "r1"), window.queue.songs.map { it.videoId })
    }

    @Test
    fun aQueueThatMovedOnIsLeftAlone() = runBlocking {
        val page = songs("a", "b")
        val window = Window(started(page, 0))
        val other = DesktopQueue.of(song("x").asQueueEntry(QueueTier.CONTEXT))
        val (asked, loader) = pages(ContinuationPage(songs("c"), "t2"), ContinuationPage(songs("d"), null))
        val context = context { token ->
            // Something else was played while the page was on its way.
            window.queue = other
            loader(token)
        }
        context.hooks = window.hooks
        context.start("VLlong", page, window.queue.current!!, "t1", source, shuffled = false, started = window.queue)
        context.awaitWalk()

        assertEquals(listOf("t1"), asked)
        assertEquals(listOf("x"), window.queue.songs.map { it.videoId })
        assertTrue(window.mirrored.isEmpty())
    }

    @Test
    fun aFailedPageIsAskedAgain() = runBlocking {
        val page = songs("a")
        val window = Window(started(page, 0))
        val (asked, loader) = pages(null, ContinuationPage(songs("b"), null))
        val context = context(loader)
        context.hooks = window.hooks
        context.start("VLlong", page, window.queue.current!!, "t1", source, shuffled = false, started = window.queue)
        context.awaitWalk()

        assertEquals(listOf("t1", "t1"), asked)
        assertEquals(listOf("a", "b"), window.queue.songs.map { it.videoId })
    }

    @Test
    fun noTokenMeansNoWalk() = runBlocking {
        val page = songs("a", "b")
        val window = Window(started(page, 0))
        val (asked, loader) = pages(ContinuationPage(songs("c"), null))
        val context = context(loader)
        context.hooks = window.hooks
        context.start("VLlong", page, window.queue.current!!, null, source, shuffled = false, started = window.queue)
        context.awaitWalk()

        assertTrue(asked.isEmpty())
        assertEquals(listOf("a", "b"), window.queue.songs.map { it.videoId })
    }

    @Test
    fun shuffledLaterPagesAreAllQueuedAndUnshuffleInThePlaylistsOrder() = runBlocking {
        val page = songs("a", "b", "c")
        val window = Window(started(page, 0), shuffle = true)
        window.preShuffle = window.queue.orderKeys()
        val more = songs("d", "e", "f", "g", "h", "i", "j", "k")
        val (_, loader) = pages(ContinuationPage(more, null))
        val context = context(loader)
        context.hooks = window.hooks
        context.start("VLlong", page, window.queue.current!!, "t1", source, shuffled = true, started = window.queue)
        context.awaitWalk()

        val ids = window.queue.songs.map { it.videoId }
        assertEquals(listOf("a", "b", "c"), ids.take(3))
        assertEquals(more.map { it.videoId }.toSet(), ids.drop(3).toSet())
        assertEquals(11, ids.size)
        // Turning shuffle off goes back to the playlist's order, later pages included.
        val restored = window.queue.inOrderOf(window.preShuffle)
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"), restored.songs.map { it.videoId })
    }

    @Test
    fun mergeRowsKeepsEveryRowAndAddsOnlyNewOnes() {
        val existing = listOf(song("a", "s1"), song("a", "s2"))
        val merged = ForkPlayContext.mergeRows(existing, listOf(song("a", "s2"), song("a", "s3"), song("b")))
        assertEquals(listOf("s1", "s2", "s3", null), merged.map { it.setVideoId })
        assertTrue(ForkPlayContext.mergeRows(existing, listOf(song("a", "s1"))) === existing)
    }

    @Test
    fun onlyThePagesOwnListIsGrown() {
        val page = songs("a", "b", "c")
        assertTrue(ForkPlayContext.isPageList(page.shuffled(), page))
        assertFalse(ForkPlayContext.isPageList(songs("a", "b"), page))
        assertFalse(ForkPlayContext.isPageList(songs("a", "b", "x"), page))
    }

    @Test
    fun growthKeysGoAfterTheContextsLastKey() {
        val order = listOf("u1", "c1", "c2", "r1")
        assertEquals(
            listOf("u1", "c1", "c2", "n1", "n2", "r1"),
            ForkPlayContext.withGrowthKeys(order, setOf("c1", "c2"), listOf("n1", "n2")),
        )
        assertEquals(emptyList(), ForkPlayContext.withGrowthKeys(emptyList(), setOf("c1"), listOf("n1")))
    }

    @Test
    fun theMirroredPageNeverMovesItsTokenBack() {
        val snapshot = ContextSnapshot(
            contextToken = "x",
            sourceKey = "BROWSE:VLlong",
            browseId = "VLlong",
            songs = songs("a", "b", "c"),
            version = 2,
            continuationToken = "t2",
            continuationState = ContinuationState.AVAILABLE,
        )
        val (rows, token) = ForkPlayContext.mirrored(songs("a"), "t1", snapshot)!!
        assertEquals(listOf("a", "b", "c"), rows.map { it.videoId })
        assertEquals("t2", token)
        // The page's own loading is ahead: it keeps its rows' token.
        val ahead = songs("a", "b", "c", "d")
        assertNull(ForkPlayContext.mirrored(ahead, "t3", snapshot))
        val done = snapshot.copy(continuationToken = null, continuationState = ContinuationState.EXHAUSTED)
        // Every page read: the page has no "more" row left to ask with.
        val finished = ForkPlayContext.mirrored(songs("a", "b", "c"), "t2", done)!!
        assertNull(finished.second)
    }
}
