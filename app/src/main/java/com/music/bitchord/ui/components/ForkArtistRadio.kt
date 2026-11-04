package com.music.bitchord.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.navidrome.NavidromeArtistInfo
import com.music.bitchord.ui.icons.SfIcons

/**
 * Fork: "Start radio" for a Navidrome artist, in the sheet a Navidrome artist page's overflow and a
 * held artist row of the Navidrome library open (YouTube artists keep upstream's menu, which has
 * none). Like "Start radio (Instant Mix)" on a song, the station is the server's similar songs,
 * here asked with the artist's id ([NavidromeArtistInfo.radio]).
 *
 * The main screen owns the player and the sheet, so it binds [play] while it is shown
 * ([ForkArtistRadioBinding]): called at once on the tap with the loader, it closes the sheet and
 * plays the loaded station the way a Top Picks station is played.
 */
object ForkArtistRadio {

    private var play: ((suspend () -> List<Song>?) -> Unit)? = null
    private var context: Context? = null

    internal fun bind(context: Context?, play: ((suspend () -> List<Song>?) -> Unit)?) {
        this.context = context?.applicationContext
        this.play = play
    }

    fun start(name: String) {
        val app = context
        play?.invoke {
            NavidromeArtistInfo.radio(name).fold(
                onSuccess = { songs ->
                    if (songs.isEmpty()) app?.let { Toast.makeText(it, R.string.fork_no_similar_songs, Toast.LENGTH_SHORT).show() }
                    songs.ifEmpty { null }
                },
                onFailure = {
                    app?.let { Toast.makeText(it, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show() }
                    null
                },
            )
        }
    }
}

/** Lends [ForkArtistRadio] the main screen's player while it is shown; let go with the screen (see `ForkQueuesBinding`). */
@Composable
fun ForkArtistRadioBinding(play: (suspend () -> List<Song>?) -> Unit) {
    val current = rememberUpdatedState(play)
    val context = LocalContext.current
    DisposableEffect(context) {
        ForkArtistRadio.bind(context) { load -> current.value(load) }
        onDispose { ForkArtistRadio.bind(null, null) }
    }
}

/** The "Start radio" row, for a Navidrome artist's page or row only. */
@Composable
internal fun ForkArtistRadioRow(target: BrowseTarget, accent: Color) {
    val name = NavidromeArtistInfo.nameOf(target.browseId) ?: return
    // Offline the server can't be asked: no row (as the PC hides its button), rather than a "couldn't load" toast.
    if (com.music.bitchord.fork.ForkNetwork.offline.collectAsState().value) return
    ActionRow(
        icon = SfIcons.Radio,
        label = stringResource(R.string.start_radio),
        accent = accent,
    ) { ForkArtistRadio.start(name) }
}

/**
 * The detail page's shelves plus, on a Navidrome artist page, "Similar artists" once the server has
 * answered (none while it hasn't, or when it knows none). Read through [NavidromeArtistInfo], which
 * asks once per artist and session.
 */
@Composable
fun forkWithSimilarArtists(browseId: String, sections: List<HomeShelf>): List<HomeShelf> {
    if (NavidromeArtistInfo.nameOf(browseId) == null) return sections
    val similar = produceState<HomeShelf?>(null, browseId) { value = NavidromeArtistInfo.similarShelf(browseId) }.value
    return if (similar == null) sections else sections + similar
}
