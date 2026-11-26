package com.music.bitchord.fork

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ForkAddonRefusals
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's side of the addon rulebook's 8.1.4: `streamBest`'s rows less the ones refused for the
 * song ([ForkNextRowPhone.left]), the song being its title and artist.
 */
class ForkNextRowPhoneTest {

    private fun row(source: String, id: String) =
        Song(videoId = SourceRegistry.trackKey(source, id), title = "Refused", artist = "X", thumbnailUrl = null, durationText = "3:20")

    @Test
    fun aRefusedRowIsPassedOverForTheSongOnly() {
        val target = TrackMatcher.Target("Refused", "X", 200)
        val song = ForkAddonRefusals.songKey(target.title, target.artist)
        ForkNextRowPhone.book.forget(song)
        val rows = listOf(row("idA", "a1"), row("idA", "a2"))
        assertEquals(rows, ForkNextRowPhone.left(target, "idA", rows))
        ForkNextRowPhone.opened("https://a.example/1", "idA", "a1")
        ForkNextRowPhone.book.refuse(song, "https://a.example/1")
        assertEquals(listOf(rows[1]), ForkNextRowPhone.left(target, "idA", rows))
        // the same title and artist, spelt another way, is the same song; another addon keeps its rows
        assertEquals(listOf(rows[1]), ForkNextRowPhone.left(target.copy(title = "refused ", durationSec = 201), "idA", rows))
        val other = listOf(row("idB", "a1"))
        assertEquals(other, ForkNextRowPhone.left(target, "idB", other))
        // another song is not affected
        assertEquals(rows, ForkNextRowPhone.left(target.copy(title = "Other"), "idA", rows))
        ForkNextRowPhone.book.forget(song)
    }

    /** 8.1.4's cap stops the searches; "Upgrade quality" by hand (8.1.7) gives the song its rows back. */
    @Test
    fun anExhaustedSongIsGivenItsRowsBackByHand() {
        val target = TrackMatcher.Target("Exhausted", "Y", 200)
        val song = ForkAddonRefusals.songKey(target.title, target.artist)
        ForkNextRowPhone.book.forget(song)
        (1..ForkAddonRefusals.NEXT_ROWS + 1).forEach { ForkNextRowPhone.book.refuse(song, "https://a.example/$it") }
        assertTrue(ForkNextRowPhone.exhausted(target))
        ForkNextRowPhone.keep("mid-1", target, null, null, null)
        ForkNextRowPhone.forgetSong("mid-1") { error("the kept look names the song") }
        assertFalse(ForkNextRowPhone.exhausted(target))
    }

    /** protocol.md 8.6: the badge is up while the next row is searched (upstream's forget put it out). */
    @Test
    fun theNextRowsSearchShowsTheUpgradingBadge() = kotlinx.coroutines.runBlocking {
        val target = TrackMatcher.Target("Badge", "Z", null) // no length: upgradeFor answers null at once
        val song = ForkAddonRefusals.songKey(target.title, target.artist)
        ForkNextRowPhone.book.forget(song)
        ForkNextRowPhone.keep("mid-badge", target, null, null, null)
        val refused = com.music.bitchord.data.sources.SourceStream("https://a.example/refused")
        com.music.bitchord.data.NerdStats.onLosslessRaceEnd("mid-badge")
        // Not refused by the window check: no retry, the badge stays down.
        assertEquals(null, ForkNextRowPhone.next("mid-badge", "bitchord://watch?v=x", refused))
        assertFalse("mid-badge" in com.music.bitchord.data.NerdStats.racingLossless.value)
        ForkNextRowPhone.book.refuse(song, refused.url)
        ForkNextRowPhone.next("mid-badge", "bitchord://watch?v=x", refused)
        assertTrue("mid-badge" in com.music.bitchord.data.NerdStats.racingLossless.value)
        com.music.bitchord.data.NerdStats.onLosslessRaceEnd("mid-badge")
        ForkNextRowPhone.book.forget(song)
    }
}
