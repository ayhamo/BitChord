package com.music.bitchord.data.backup

import com.music.bitchord.fork.ForkSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fork: the Drive backup's device manifest carries the fork's section (`ForkBackup`), as the file
 * export does; a manifest from before it still reads, and the Spotify switches join after a restore.
 */
class ForkDriveBackupTest {

    /** As `ReplaySync`'s own. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun theManifestKeepsTheForkSectionAndAnOldOneStillReads() {
        val section = JsonObject(mapOf("settings" to JsonObject(mapOf("cover_fade" to JsonPrimitive("x")))))
        val text = json.encodeToString(DeviceManifest.serializer(), DeviceManifest(deviceId = "a", fork = section))
        assertEquals(section, json.decodeFromString(DeviceManifest.serializer(), text).fork)
        val old = json.decodeFromString(DeviceManifest.serializer(), """{"schema":1,"deviceId":"b","months":{}}""")
        assertNull(old.fork)
        // A Spotify switch off on either side keeps Spotify Canvas off on both.
        assertTrue(ForkSettings.spotifyJoined(disableFlag = false, listHasSpotify = true))
        assertFalse(ForkSettings.spotifyJoined(disableFlag = true, listHasSpotify = true))
        assertFalse(ForkSettings.spotifyJoined(disableFlag = false, listHasSpotify = false))
    }
}
