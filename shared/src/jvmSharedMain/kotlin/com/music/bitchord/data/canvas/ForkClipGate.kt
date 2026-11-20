package com.music.bitchord.data.canvas

import com.music.bitchord.data.model.NAVIDROME_ID_PREFIX
import com.music.bitchord.data.model.Song
import java.util.Locale

/**
 * Fork: Prism's `ClipGate` (`PR/canvas/ClipFinder.kt:284-331`) and the parts of its `ClipFinder`
 * gate that need no network, for the music-video canvas (audit 2026-11-20, part C 2-5):
 *
 *  - [isSong]: only a song is looked up. Prism leaves out podcasts, feed episodes, files on the
 *    phone, the listener's uploads and live stations; BitChord's `Song` marks two of those, a
 *    device file (a `localUri` with no catalogue or server id) and a server's internet radio
 *    station (`radio:`). Podcast episodes and YouTube uploads carry no mark on BitChord's `Song`,
 *    so they still are (FORK.md).
 *  - [official]: YouTube Music's own word for the video must be the official music video (`OMV`);
 *    canvas.md §5.6.
 *  - [titleSays]: a video whose title says it is not the music video itself (the audio, a lyric
 *    video, a live take, a stage, a fancam, a jukebox, a recording session and their Korean,
 *    Japanese and Chinese words), unless the song's own title has the word.
 *  - [playable]: a live stream or a private video is never cut.
 *
 * What fails [official] or [titleSays] is not the last word: the next candidates are tried, and with
 * none better the song's own video is taken as it is ([ForkVideoCanvas]), as Prism does.
 */
object ForkClipGate {

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Whether [song] is a song at all: not a device file and not a live station. */
    fun isSong(song: Song): Boolean {
        val id = song.videoId
        if (id.isBlank() || id.startsWith("radio:")) return false
        if (id.startsWith(NAVIDROME_ID_PREFIX)) return true
        if (YOUTUBE_ID.matches(id)) return true
        // Neither a catalogue id nor a server's: a file on the device (its id is its own).
        return song.localUri == null
    }

    private const val OMV = "MUSIC_VIDEO_TYPE_OMV"

    /** canvas.md §5.6: YouTube Music calls the video the official music video. */
    fun official(type: String?): Boolean = type == OMV || type == "OMV"

    /** Prism's `PlayerInfo.playable`: an OK answer, not private, not live now (or live with no length yet). */
    fun playable(status: String?, isPrivate: Boolean, isLive: Boolean, isLiveContent: Boolean, lengthSec: Long): Boolean =
        status == "OK" && !isPrivate && !isLive && !(isLiveContent && lengthSec <= 0)

    /** A word only where it stands alone ("live" is not in "alive", "stage" not in "backstage"). */
    private fun words(vararg w: String) = w.map { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])") }

    private val WORDS = words(
        "audio", "karaoke", "chords", "performance", "live", "stage", "cover", "fancam", "fan cam",
        "recording", "jukebox", "bgm",
        // Spanish, Portuguese.
        "en vivo", "ao vivo", "en directo",
    )

    /** "Lyric video" and its like: let through for a song with no other video ([titleSays] `lyricsOk`). */
    private val LYRIC_WORDS = words("lyric", "lyrics", "lyrical", "letra", "letras", "paroles", "testo")
    private val LYRIC_SCRIPT = setOf("가사", "歌詞", "歌词")
    private val FULL_SONG = words("full song").single()
    private val VIDEO = words("video").single()

    /** Words in scripts without spaces between words: matched anywhere in the title. */
    private val SCRIPT = listOf(
        "직캠", "무대", "가사", "라이브", // Korean: fancam, stage, lyrics, live
        "推しカメラ", "歌詞", "ライブ", "歌ってみた", // Japanese: member cam, lyrics, live, "I sang it" covers
        "歌词", "现场", "翻唱", // Chinese: lyrics, live, cover
    )

    /**
     * Whether a video's title says it is not the music video itself. A word in the song's own title
     * doesn't count (a live album's song may use its live video). "Full song" counts only when the
     * title doesn't also say video ("Full Video Song" is the video). [lyricsOk]: a lyric video passes.
     */
    fun titleSays(songTitle: String, videoTitle: String, lyricsOk: Boolean = false): Boolean {
        val song = songTitle.lowercase(Locale.ROOT)
        val video = videoTitle.lowercase(Locale.ROOT)
        fun counts(r: Regex) = r.containsMatchIn(video) && !r.containsMatchIn(song)
        if (WORDS.any(::counts)) return true
        if (!lyricsOk && LYRIC_WORDS.any(::counts)) return true
        if (FULL_SONG.containsMatchIn(video) && !VIDEO.containsMatchIn(video) && !FULL_SONG.containsMatchIn(song)) return true
        return (if (lyricsOk) SCRIPT - LYRIC_SCRIPT else SCRIPT).any { it in video && it !in song }
    }

    /** Why a video was turned away, Prism's `NoClip` names; [SEARCH_AGAIN] sends the finder to the next one. */
    enum class Refusal { NOT_OFFICIAL, TITLE, NOT_PLAYABLE, NO_STREAM, STILL, FLASHING }

    /** A first video refused for these is not the last word: another is tried. */
    val SEARCH_AGAIN = setOf(Refusal.NOT_OFFICIAL, Refusal.TITLE)
}
