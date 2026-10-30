package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fork: an Instant Mix keeps the server's order, without the seed or a second copy of a song. */
class NavidromeInstantMixTest {

    private fun song(id: String, title: String, artist: String) =
        Song(videoId = "navidrome:$id", title = title, artist = artist, thumbnailUrl = null)

    private val seed = song("seed", "Poker Face", "Lady Gaga")

    @Test
    fun keepsTheServersOrderAndEveryArtist() {
        val songs = (1..6).map { song("s$it", "Song $it", "Lady Gaga") }
        assertEquals(songs, NavidromeRepository.withoutRepeats(seed, songs))
    }

    @Test
    fun dropsTheSeedAndItsOtherCopy() {
        val other = song("other", "Just Dance", "Lady Gaga")
        val mix = listOf(seed, song("copy", "Poker Face", "Lady Gaga"), other)
        assertEquals(listOf(other), NavidromeRepository.withoutRepeats(seed, mix))
    }

    @Test
    fun aSongInTheLibraryTwiceComesOnce() {
        val flac = song("flac", "Just Dance", "Lady Gaga")
        val opus = song("opus", "Just Dance", "Lady Gaga")
        assertEquals(listOf(flac), NavidromeRepository.withoutRepeats(seed, listOf(flac, opus)))
    }

    @Test
    fun anEmptyAnswerStaysEmpty() {
        assertEquals(emptyList<Song>(), NavidromeRepository.withoutRepeats(seed, emptyList()))
    }
}
