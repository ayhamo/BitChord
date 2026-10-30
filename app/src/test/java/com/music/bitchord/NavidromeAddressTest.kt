package com.music.bitchord

import com.music.bitchord.data.navidrome.NavidromeClient
import com.music.bitchord.data.navidrome.NavidromeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: an address counts as a server only if the client can use it. One that passed saved and crashed every start. */
class NavidromeAddressTest {

    @Test
    fun aPortOutOfRangeIsNotAServer() {
        for (address in listOf("https://nd.local:99999", "nd.local:99999", "https://nd.local:0", "music example.com")) {
            assertEquals(address, "", NavidromeSettings.normalizeUrl(address))
            assertFalse(address, NavidromeSettings.isConfigured(address, "someone"))
        }
    }

    @Test
    fun everyAddressThatCountsMakesAClient() {
        for (address in listOf("music.example.com/", "http://192.168.0.5:4533", "https://nd.example.com:8443/navidrome")) {
            assertTrue(address, NavidromeSettings.isConfigured(address, "someone"))
            NavidromeClient(address, "someone", "secret") // throws if it can't
        }
    }
}
