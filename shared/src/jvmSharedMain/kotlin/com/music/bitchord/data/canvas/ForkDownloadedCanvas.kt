package com.music.bitchord.data.canvas

/**
 * Fork: a downloaded song's canvas by its id (the canvas rulebook's rules 1.5 and 2.5), phone and PC.
 *
 * [ForkCanvasStore] keys Spotify's and Apple's answers on title and artist (rule 2.4), and a
 * downloaded song can read differently from the streamed one: the owner's phone extension found
 * YouTube's offline title and artist differing from the online ones, so a song saved for offline
 * listening asked again, without a network, and showed no clip. The rulebook therefore also keeps a
 * downloaded song's answers by its id: its Spotify canvas under `spid:<id>` and its art clip (the
 * motion cover: Apple, and the fork's Tidal and community index beside it) under `id:<id>`, and a
 * downloaded song reads those first, Spotify's before the art clip's, before any search. The music
 * video's loop needs nothing new: it is keyed by the id already (`mv:<id>`).
 *
 * What the fork does not copy: the phone extension fetches both clips into its files when the song
 * is downloaded (`OfflineExtras`). BitChord's downloads don't, so an id key is written when a lookup
 * for the downloaded song finds a clip that matches it, and the clip itself plays offline from the
 * clip cache (`CanvasCache` on the phone, the media cache on the PC) while that still holds it.
 *
 * Only clips are kept by id, never a miss: a miss here would stop nothing (the title-and-artist
 * keys and the searches still follow) and would only hide a clip found later.
 */
object ForkDownloadedCanvas {

    /**
     * The id keys a downloaded song reads first, in rule 1.5's order: Spotify's canvas while the
     * Spotify backdrop is asked for ([spotify]), then the art clip while cover clips are ([covers]).
     */
    fun keys(songId: String, spotify: Boolean, covers: Boolean): List<String> = buildList {
        if (songId.isBlank()) return@buildList
        if (spotify) add(ForkCanvasRules.downloadedSpotifyKey(songId))
        if (covers) add(ForkCanvasRules.downloadedKey(songId))
    }

    /** The first saved clip under [keys], with no network; null when none is saved. */
    fun saved(keys: List<String>, read: (String) -> ForkCanvasRules.Saved? = { ForkCanvasStore.read(it) }): CanvasArtwork? {
        for (key in keys) {
            val clip = read(key) as? ForkCanvasRules.Saved.Clip ?: continue
            ForkCanvasStore.artworkOf(clip)?.let { return it }
        }
        return null
    }

    /**
     * One source for a downloaded song: its clip saved under [key] if there is one, else [lookUp],
     * whose answer is kept under [key] when it is the song ([accept], the caller's own match). A
     * null [key] (not a downloaded song) is [lookUp] alone.
     */
    suspend fun through(
        key: String?,
        accept: (CanvasArtwork) -> Boolean,
        put: (String, CanvasArtwork) -> Unit = { k, a -> ForkCanvasStore.put(k, a) },
        read: (String) -> ForkCanvasRules.Saved? = { ForkCanvasStore.read(it) },
        lookUp: suspend () -> CanvasArtwork?,
    ): CanvasArtwork? {
        if (key == null) return lookUp()
        saved(listOf(key), read)?.let { return it }
        return lookUp()?.also { if (accept(it)) put(key, it) }
    }
}
