package com.music.bitchord.data

import java.util.Collections

/**
 * Fork: a map that keeps at most [max] entries, dropping the least recently used, for the fork's
 * long-lived per-song and per-address memos. Several were plain `ConcurrentHashMap`s that only ever
 * grew: one entry per song played or address opened, for as long as the app ran, which on a PC left
 * running for days is a slow leak. Each of those memos is a cache (losing an entry costs one more
 * lookup), so a bound loses nothing that matters.
 *
 * Access-ordered (a `get` counts as a use) and wrapped in [Collections.synchronizedMap], so every
 * single call is atomic, `putIfAbsent` and `computeIfAbsent` included. Kotlin's `getOrPut` is not
 * (a get, then a put), nor is iterating: those go inside `synchronized(map) { }`.
 */
fun <K, V> forkLruMap(max: Int): MutableMap<K, V> = Collections.synchronizedMap(
    object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    },
)

/** [forkLruMap] as a set: at most [max] members, the least recently added dropped first (`contains` does not count as a use). */
fun <T> forkLruSet(max: Int): MutableSet<T> = Collections.newSetFromMap(forkLruMap<T, Boolean>(max))
