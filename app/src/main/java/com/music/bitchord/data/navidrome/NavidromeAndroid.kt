package com.music.bitchord.data.navidrome

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import java.io.File
import okhttp3.Request
import okhttp3.Response

/** Fork: the phone's side of [NavidromePlatform]: its prefs, cache folder, offline files and switches. */
object NavidromeAndroid {

    fun init(context: Context) {
        NavidromePlatform.kept = KeptFiles
        NavidromePlatform.offline = com.music.bitchord.fork.ForkNetwork.offline
        NavidromePlatform.onSong = com.music.bitchord.fork.ForkYouTubeHistory::remember
        NavidromePlatform.scrobble = { com.music.bitchord.fork.ForkSettings.navidromeScrobble.value }
        NavidromeSettings.init(PrefsStore(EncryptedPrefs.open(context, "bitchord_navidrome", "bitchord_navidrome_plain")))
        // Plain prefs: only "no clip for this song" notes, nothing secret.
        NavidromeClips.init(PrefsStore(context.getSharedPreferences("bitchord_navidrome_clips", Context.MODE_PRIVATE)))
        NavidromeSongInfo.init()
        NavidromeOffline.init(context)
        NavidromeLyrics.init(File(context.cacheDir, "navidrome_lyrics"))
        NavidromeRadio.init(context)
    }

    private class PrefsStore(private val prefs: SharedPreferences) : NavidromePlatform.Store {
        override fun string(key: String): String? = prefs.getString(key, null)
        override fun long(key: String): Long = prefs.getLong(key, 0L)
        override fun putStrings(values: Map<String, String>) {
            prefs.edit().apply { values.forEach { (key, value) -> putString(key, value) } }.apply()
        }
        override fun putLong(key: String, value: Long) {
            prefs.edit().putLong(key, value).apply()
        }
        override fun keys(): Set<String> = prefs.all.keys
        override fun remove(keys: Collection<String>) {
            prefs.edit().apply { keys.forEach(::remove) }.apply()
        }
        override fun clear() {
            prefs.edit().clear().apply()
        }
    }

    private object KeptFiles : NavidromePlatform.Kept {
        override fun localUri(songId: String): String? = NavidromeOffline.localFile(songId)?.let { Uri.fromFile(it).toString() }
        override fun lyricsFile(songId: String): File? = NavidromeOffline.lyricsFile(songId)
        override fun songIdForFile(uri: String): String? = NavidromeOffline.songIdForFile(uri)
        override fun clipUrl(songId: String, backdrop: Boolean): String? =
            NavidromeOffline.clipUrl(songId, if (backdrop) NavidromeClips.Kind.BACKDROP else NavidromeClips.Kind.COVER)
        override fun keptSongs(): List<Song> = NavidromeOffline.keptSongs()
        override fun noteSize(songId: String, bytes: Long) = NavidromeOffline.noteSize(songId, bytes)
        override fun serve(request: Request): Response? = NavidromeOffline.serve(request)
        override suspend fun pageState(browseId: String): UiState<List<Song>>? = NavidromeOffline.pageState(browseId)
    }
}
