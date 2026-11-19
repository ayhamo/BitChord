package com.music.bitchord.desktop

import com.music.bitchord.data.ForkSettingsBadge
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings > Navidrome (owner, 2026-11-19): the server rows say who is signed in where, or that no
 * server is set up (a user without an address, or the reverse, is no server), and the Fork mark reads
 * the phone's badge text.
 */
class ForkNavidromeSettingsPageTest {

    @Test
    fun theServerLineNamesTheUserAndTheAddress() {
        assertEquals("me on https://music.example.com", forkNavidromeServerLine("https://music.example.com", "me"))
    }

    @Test
    fun noServerUntilBothAreSet() {
        assertEquals("Not connected", forkNavidromeServerLine("", ""))
        assertEquals("Not connected", forkNavidromeServerLine("https://music.example.com", " "))
        assertEquals("Not connected", forkNavidromeServerLine("", "me"))
    }

    @Test
    fun theMarkIsThePhonesBadge() {
        assertEquals("Fork", ForkSettingsBadge.TEXT)
    }
}
