package com.music.bitchord.fork

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.navidrome.NavidromeRepository
import com.music.bitchord.data.stats.StoredBucket
import java.io.File

/**
 * Fork: listening stats without a server's login in their cover addresses.
 *
 * A Navidrome song's cover address carried the Subsonic login (`u`, `t`, `s`, a full-account
 * credential that never expires) until the login moved into the HTTP layer, and the stats kept
 * each track's, artist's and album's cover as it was then: in the month's file on disk, in a
 * backup's export, read back by every Replay page. A song recorded now has none; this takes it
 * out of what older builds wrote, as each month is read, and writes the month back once without it
 * ([NavidromeRepository.atRest]; the HTTP layer adds the login again when the cover is drawn).
 */
object ForkStatsArt {

    private const val TAG = "BitChordNavidrome"

    /** [bucket] with every cover address login-free; when anything changed, [file] is rewritten through [encode]. */
    fun scrub(bucket: StoredBucket, file: File, encode: (StoredBucket) -> String): StoredBucket {
        val clean = clean(bucket)
        if (clean == bucket) return bucket
        runCatching {
            val temporary = File(file.parentFile, file.name + ".fork.tmp")
            temporary.writeText(encode(clean))
            if (!temporary.renameTo(file) && !(file.delete() && temporary.renameTo(file))) temporary.delete()
        }.onFailure { Log.d(TAG, "stats ${file.name}: ${it.message}") }
        return clean
    }

    /** [bucket] with [NavidromeRepository.atRest] applied to every `art`. */
    internal fun clean(bucket: StoredBucket): StoredBucket {
        fun art(url: String?) = NavidromeRepository.atRest(url)
        if (bucket.tracks.none { art(it.art) != it.art } && (bucket.artists + bucket.albums).none { art(it.art) != it.art }) return bucket
        return bucket.copy(
            tracks = bucket.tracks.map { if (art(it.art) == it.art) it else it.copy(art = art(it.art)) },
            artists = bucket.artists.map { if (art(it.art) == it.art) it else it.copy(art = art(it.art)) },
            albums = bucket.albums.map { if (art(it.art) == it.art) it else it.copy(art = art(it.art)) },
        )
    }
}
