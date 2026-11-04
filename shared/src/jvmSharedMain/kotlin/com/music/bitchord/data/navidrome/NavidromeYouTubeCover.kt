package com.music.bitchord.data.navidrome

/**
 * Fork: the cover for a server song whose file has none of its own.
 *
 * Navidrome gives a song with embedded art its own cover id (`mf-…`), and one without the
 * album's (`al-…`). The library groups albums by folder (`ND_PID_ALBUM=folder`), so in a
 * collection folder ("Youtube Topic") the album's cover is just one of its songs' covers: the
 * "test" album's Opus copies, written by ffmpeg without art, all showed Zara Larsson's "Midnight
 * Sun" (FAITH by Madkid among them).
 *
 * The owner's files come from yt-dlp, whose `comment` tag ends with the video's address
 * (`…;https://www.youtube.com/watch?v=<id>`). For a song with no own art that names its video,
 * that video's thumbnail is the song's cover (the same image yt-dlp would have embedded), with
 * no lookup and nothing asked of the server. Songs with their own art, and songs without a
 * video id, keep the server's cover as before; there is deliberately no title/artist search.
 */
object NavidromeYouTubeCover {

    /** `watch?v=<id>`, `youtu.be/<id>`, `/shorts/<id>`: a YouTube video id is 11 of these characters. */
    private val VIDEO = Regex("""(?:[?&]v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")

    /** The video id in [comment], the last one when it names several (the address is at the end). */
    fun youtubeId(comment: String?): String? =
        comment?.let { VIDEO.findAll(it).lastOrNull()?.groupValues?.get(1) }

    /** Whether [coverArt] is the album's cover rather than the song's own: none of its own in the file. */
    fun isAlbumCover(coverArt: String?): Boolean = coverArt == null || coverArt.startsWith("al-")

    /**
     * The YouTube thumbnail for a song whose [coverArt] is not its own and whose [comment] names
     * its video, or null to use the server's cover. `hqdefault` because every video has one
     * (`maxresdefault` doesn't), and the app already uses it for YouTube rows.
     */
    fun artFor(coverArt: String?, comment: String?): String? {
        if (!isAlbumCover(coverArt)) return null
        val id = youtubeId(comment) ?: return null
        return "https://$HOST/vi/$id/hqdefault.jpg"
    }

    const val HOST = "i.ytimg.com"

    /** Exactly what [artFor] writes, so a kept file stands for that one picture. */
    private val THUMBNAIL = Regex("""https://i\.ytimg\.com/vi/([A-Za-z0-9_-]{11})/hqdefault\.jpg""")

    /**
     * The name `NavidromeOffline` keeps [url] under (`yt-<video id>`), when it is a thumbnail
     * [artFor] wrote; null for anything else. Kept songs carry it as their cover, and offline
     * nothing else would answer it: `keepCover` only knew the server's `getCoverArt` ids.
     */
    fun keptName(url: String): String? = THUMBNAIL.matchEntire(url)?.groupValues?.get(1)?.let { "yt-$it" }
}
