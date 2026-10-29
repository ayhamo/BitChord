package com.music.bitchord.fork

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/**
 * Spotify's mixes for the signed-in account (fork-only): Discover Weekly,
 * Release Radar, the Daily Mixes and the rest Spotify makes for the listener,
 * as a "Made for you" shelf on Home and tiles on Explore.
 *
 * Each opens as upstream's Spotify playlist page, which finds every track's
 * YouTube Music counterpart ([com.music.bitchord.data.spotify.SpotifyImporter.matchTrack]),
 * so playback is the usual YouTube path, quality upgrades from the sources
 * included. Nothing here plays or reports anything to Spotify.
 *
 * Two places they come from, merged: Spotify's Web API "Made For You"
 * category (the public `browse/categories/…/playlists` endpoint, with the
 * signed-in web player's token, which is what makes it personal), and the
 * playlists in the library that Spotify itself owns (mixes the listener saved).
 * Either can come back empty; the shelf shows what there is.
 */
object ForkSpotifyMixes {

    private const val TAG = "BitChordFork"
    private const val MADE_FOR_YOU = "0JQ5DAt0tbjZptfcdMSKl3"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _mixes = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val mixes: StateFlow<List<SpotifyPlaylist>> = _mixes.asStateFlow()

    /** Re-reads the mixes; signed out (or any failure) leaves none. Called as Home loads. */
    suspend fun load() {
        if (AppSettings.spotifySpdcToken.value.isBlank()) {
            publish(emptyList())
            return
        }
        val fromCategory = runCatching { madeForYou() }
            .onFailure { Log.d(TAG, "spotify mixes (category): ${it.message}") }
            .getOrDefault(emptyList())
        val fromLibrary = runCatching { SpotifyLibrary.playlists().filter { it.owner.equals("Spotify", ignoreCase = true) } }
            .onFailure { Log.d(TAG, "spotify mixes (library): ${it.message}") }
            .getOrDefault(emptyList())
        Log.d(TAG, "spotify mixes: ${fromCategory.size} made for you, ${fromLibrary.size} saved")
        publish((fromCategory + fromLibrary).distinctBy { it.id })
    }

    /** The mixes as Home's "Made for you" shelf, or null with none. */
    fun shelf(title: String): HomeShelf? = _mixes.value.takeIf { it.isNotEmpty() }?.let { list ->
        HomeShelf(
            title,
            list.map { ShelfItem(title = it.name, subtitle = "Spotify", thumbnailUrl = it.imageUrl, videoId = null, browseId = SPOTIFY_PAGE_PREFIX + it.id) },
            "Spotify",
        )
    }

    fun isMix(browseId: String?): Boolean = browseId?.startsWith(SPOTIFY_PAGE_PREFIX) == true

    private fun publish(list: List<SpotifyPlaylist>) {
        _mixes.value = list
        com.music.bitchord.ui.screens.ForkExplore.spotifyMixes.value = list.map {
            MoodGenre(title = it.name, browseId = SPOTIFY_PAGE_PREFIX + it.id, params = null, thumbnailUrl = it.imageUrl)
        }
    }

    private suspend fun madeForYou(): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val tokens = SpotifyLibrary.auth?.tokens() ?: return@withContext emptyList()
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/browse/categories/$MADE_FOR_YOU/playlists?limit=50&offset=0")
            .header("Authorization", "Bearer ${tokens.bearer}")
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val items = json.parseToJsonElement(response.body.string()).jsonObject["playlists"]
                ?.let { it as? JsonObject }?.get("items") as? JsonArray ?: return@use emptyList()
            items.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                SpotifyPlaylist(
                    id = o.str("id") ?: return@mapNotNull null,
                    name = o.str("name") ?: return@mapNotNull null,
                    owner = (o["owner"] as? JsonObject)?.str("display_name"),
                    imageUrl = (o["images"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("url") },
                )
            }
        }
    }

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
