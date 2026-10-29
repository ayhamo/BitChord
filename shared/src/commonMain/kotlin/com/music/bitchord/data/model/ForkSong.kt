package com.music.bitchord.data.model

/** fork: the id prefix every Navidrome song carries (see the app's `NavidromeSettings.ID_PREFIX`). */
const val NAVIDROME_ID_PREFIX = "navidrome:"

/**
 * fork: a song from the owner's Navidrome server. Readable from the shared UI,
 * which can't see the app's Navidrome code: such a song plays from a stream
 * URL (so `localUri` is set) yet can still be starred, on the server.
 */
val Song.isNavidrome: Boolean get() = videoId.startsWith(NAVIDROME_ID_PREFIX)
