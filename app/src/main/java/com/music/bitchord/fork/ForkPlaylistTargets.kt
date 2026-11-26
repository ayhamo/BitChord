package com.music.bitchord.fork

import com.music.bitchord.data.model.NAVIDROME_ID_PREFIX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeRadio
import com.music.bitchord.data.navidrome.NavidromeSettings

/**
 * Fork: what v1.10's two new "Add to playlist" surfaces may send to YouTube (audit 2026-11-24, 3B #5).
 *
 * The song menu already leaves the row out for a Navidrome song (`SongActionsMenu`: neither YouTube's
 * playlists nor the device's hold a server's song). The queue's selection picker
 * (`QueuePlaylistPicker`) took every song that isn't a device file for YouTube's, a server song or
 * a radio station included, and the album menu (`MainActivity`'s `BrowseActionsMenu`) offered it on
 * a Navidrome album page; YouTube then refused the `navidrome:` ids after the pick.
 */
object ForkPlaylistTargets {

    /** Whether [song] is one YouTube's playlists can hold as far as the fork knows: not a server's song nor a station. */
    fun youTubeCanHold(song: Song): Boolean =
        !song.videoId.startsWith(NAVIDROME_ID_PREFIX) && !NavidromeRadio.isRadio(song.videoId)

    /** Whether the album page [browseId] may offer "Add to playlist": not a Navidrome album's. */
    fun albumOffersPlaylists(browseId: String): Boolean = !browseId.startsWith(NavidromeSettings.albumPageId(""))
}
