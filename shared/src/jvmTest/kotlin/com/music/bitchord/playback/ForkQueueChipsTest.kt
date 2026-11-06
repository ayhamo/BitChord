package com.music.bitchord.playback

import com.music.bitchord.data.model.NAVIDROME_ID_PREFIX
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork: the queue chips' rules (ForkQueueChips) — which rows a chip replaces, when the row shows. */
class ForkQueueChipsTest {

    @AfterTest
    fun clear() = ForkQueueChips.reset()

    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT, artist: String = "Artist $id") =
        Song(videoId = id, title = "Title $id", artist = artist, thumbnailUrl = null, queueTier = tier)

    private fun radioAnswer(seed: String) = Json.parseToJsonElement(
        """
        { "musicQueueRenderer": { "subHeaderChipCloud": { "chipCloudRenderer": { "chips": [
          { "chipCloudChipRenderer": { "text": { "runs": [ { "text": "All" } ] }, "isSelected": true,
            "navigationEndpoint": { "queueUpdateCommand": { "fetchContentsCommand": { "watchEndpoint": { "playlistId": "RDAMVM$seed", "params": "a" } } } } } },
          { "chipCloudChipRenderer": { "text": { "runs": [ { "text": "Deep cuts" } ] }, "isSelected": false,
            "navigationEndpoint": { "queueUpdateCommand": { "fetchContentsCommand": { "watchEndpoint": { "playlistId": "RDATpX$seed", "params": "b" } } } } } }
        ] } } } }
        """,
    )

    @Test
    fun aChipReplacesOnlyAutoplaysRows() {
        val queue = listOf(
            song("played"),
            song("now"),
            song("mine", QueueTier.USER_QUEUE),
            song("album", QueueTier.CONTEXT),
            song("auto1", QueueTier.AUTOPLAY),
            song("auto2", QueueTier.AUTOPLAY),
        )
        val tail = ForkQueueChips.tailFor(
            queue,
            currentIndex = 1,
            candidates = listOf(song("now"), song("mine"), song("played"), song("auto1"), song("new1"), song("new2")),
        )
        // The playing song, history and the listener's and album's rows are not offered again;
        // the AutoPlay rows being replaced are.
        assertEquals(listOf("auto1", "new1", "new2"), tail.map { it.videoId })
    }

    @Test
    fun aChipFillsAtMostTheTail() {
        val queue = listOf(song("now"))
        val candidates = (1..30).map { song("c$it") }
        assertEquals(ForkQueueChips.TAIL_SIZE, ForkQueueChips.tailFor(queue, 0, candidates).size)
        assertTrue(ForkQueueChips.tailFor(queue, 5, candidates).isEmpty())
    }

    @Test
    fun theRadioAnswerBringsTheChips() {
        ForkQueueChips.record("seed", radioAnswer("seed"), listOf(song("seed"), song("r1")), ForkQueueChips.radioToken())
        val state = assertNotNull(ForkQueueChips.state.value)
        assertEquals(listOf("All", "Deep cuts"), state.chips.map { it.title })
        assertEquals(0, state.selected)
        assertEquals("seed", state.seedVideoId)
        // An answer without chips (a chip's own queue, a failed parse) clears them.
        ForkQueueChips.record("other", Json.parseToJsonElement("{}"), emptyList(), ForkQueueChips.radioToken())
        assertNull(ForkQueueChips.state.value)
    }

    @Test
    fun theRowShowsOnlyOverItsOwnStation() {
        ForkQueueChips.record("seed", radioAnswer("seed"), listOf(song("seed"), song("r1"), song("r2")), ForkQueueChips.radioToken())
        val state = ForkQueueChips.state.value
        val queue = listOf(song("seed"), song("r1", QueueTier.AUTOPLAY))
        assertTrue(ForkQueueChips.shows(state, true, queue, 0, inParty = false))
        // An album playing with this station's AutoPlay after it.
        assertTrue(ForkQueueChips.shows(state, true, listOf(song("album1"), song("r2", QueueTier.AUTOPLAY)), 0, false))
        // Not in a party, not where the app can't edit its queue.
        assertFalse(ForkQueueChips.shows(state, true, queue, 0, inParty = true))
        assertFalse(ForkQueueChips.shows(state, false, queue, 0, inParty = false))
        // Another queue whose AutoPlay is another station's.
        assertFalse(ForkQueueChips.shows(state, true, listOf(song("x"), song("y", QueueTier.AUTOPLAY)), 0, false))
        // A Navidrome song: its AutoPlay is the server's.
        val server = song("${NAVIDROME_ID_PREFIX}42")
        assertFalse(ForkQueueChips.shows(state, true, listOf(server, song("r1", QueueTier.AUTOPLAY)), 0, false))
        assertFalse(ForkQueueChips.shows(null, true, queue, 0, false))
    }

    @Test
    fun noStationUntilAChipIsPicked() {
        ForkQueueChips.record("seed", radioAnswer("seed"), listOf(song("seed")), ForkQueueChips.radioToken())
        assertNull(kotlinx.coroutines.runBlocking { ForkQueueChips.stationFor(song("seed"), emptyList(), 10) })
    }

    @Test
    fun anOvertakenRadioAnswerIsIgnored() {
        ForkQueueChips.record("seed", radioAnswer("seed"), listOf(song("seed")), ForkQueueChips.radioToken())
        // A top-up asks the radio for the next song; a tap (or a newer radio) lands first.
        val asked = ForkQueueChips.radioToken()
        ForkQueueChips.record("seed", radioAnswer("seed"), listOf(song("seed")), ForkQueueChips.radioToken())
        ForkQueueChips.record("next", radioAnswer("next"), listOf(song("next")), asked)
        assertEquals("seed", ForkQueueChips.state.value?.seedVideoId)
    }

    @Test
    fun theNextPageIsSeededOnTheChipsFurthestQueuedSong() {
        val chip = listOf(song("seed"), song("c1"), song("c2"), song("c3"), song("c4"))
        // The queue (seed playing, c1 and c2 queued), then the session's history ending
        // with the playing song, as both apps pass it with "Don't repeat suggestions" on.
        val existing = listOf(song("seed"), song("c1", QueueTier.AUTOPLAY), song("c2", QueueTier.AUTOPLAY), song("old"), song("seed"))
        assertEquals("c2", ForkQueueChips.refillSeed(chip, existing))
        assertNull(ForkQueueChips.refillSeed(chip, listOf(song("x"))))
    }
}
