package com.music.bitchord.data.spotify

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/**
 * Spotify's mixes for the signed-in account (fork-only), read for the phone's and the desktop's
 * Home: Discover Weekly, Release Radar, the Daily Mixes and the rest Spotify makes for the listener.
 *
 * Two places they come from, merged: Spotify's Web API "Made For You" category (the public
 * `browse/categories/…/playlists` endpoint, with the signed-in web player's token, which is what
 * makes it personal), and the playlists in the library that Spotify itself owns (mixes the listener
 * saved). Either can come back empty.
 */
object ForkSpotifyMadeForYou {

    private const val TAG = "BitChordFork"
    private const val MADE_FOR_YOU = "0JQ5DAt0tbjZptfcdMSKl3"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Whatever Spotify answers: a failed source only leaves its own mixes out. */
    suspend fun mixes(): List<SpotifyPlaylist> {
        val fromCategory = runCatching { madeForYou() }
            .onFailure { Log.d(TAG, "spotify mixes (category): ${it.message}") }
            .getOrDefault(emptyList())
        val fromLibrary = runCatching { SpotifyLibrary.playlists().filter { it.owner.equals("Spotify", ignoreCase = true) } }
            .onFailure { Log.d(TAG, "spotify mixes (library): ${it.message}") }
            .getOrDefault(emptyList())
        Log.d(TAG, "spotify mixes: ${fromCategory.size} made for you, ${fromLibrary.size} saved")
        return (fromCategory + fromLibrary).distinctBy { it.id }
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
