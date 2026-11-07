package com.music.bitchord.data.navidrome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavidromeUrlTest {

    @Test
    fun aTypedSchemeIsKept() {
        assertEquals("http://192.168.1.5:4533", NavidromeSettings.normalizeUrl("http://192.168.1.5:4533/"))
        assertEquals("https://192.168.1.5", NavidromeSettings.normalizeUrl("https://192.168.1.5"))
        assertEquals("http://music.example.com", NavidromeSettings.normalizeUrl(" http://music.example.com "))
        assertEquals("HTTP://music.example.com", NavidromeSettings.normalizeUrl("HTTP://music.example.com"))
    }

    @Test
    fun aBarePrivateOrTailscaleAddressIsHttp() {
        assertEquals("http://192.168.1.5:4533", NavidromeSettings.normalizeUrl("192.168.1.5:4533"))
        assertEquals("http://10.0.0.2:4533/navidrome", NavidromeSettings.normalizeUrl("10.0.0.2:4533/navidrome/"))
        assertEquals("http://172.20.1.1", NavidromeSettings.normalizeUrl("172.20.1.1"))
        assertEquals("http://100.88.139.51:4533", NavidromeSettings.normalizeUrl("100.88.139.51:4533"))
        assertEquals("http://127.0.0.1:4533", NavidromeSettings.normalizeUrl("127.0.0.1:4533"))
    }

    @Test
    fun aBareNameOrPublicAddressIsHttps() {
        assertEquals("https://music.example.com", NavidromeSettings.normalizeUrl("music.example.com/"))
        assertEquals("https://nas.tail1234.ts.net", NavidromeSettings.normalizeUrl("nas.tail1234.ts.net"))
        assertEquals("https://8.8.8.8:4533", NavidromeSettings.normalizeUrl("8.8.8.8:4533"))
        assertEquals("https://172.32.0.1", NavidromeSettings.normalizeUrl("172.32.0.1"))
        assertEquals("https://100.128.0.1", NavidromeSettings.normalizeUrl("100.128.0.1"))
        assertEquals("https://192.168.1.5.example.com", NavidromeSettings.normalizeUrl("192.168.1.5.example.com"))
    }

    @Test
    fun notAnAddressIsBlank() {
        assertEquals("", NavidromeSettings.normalizeUrl("  "))
        assertEquals("", NavidromeSettings.normalizeUrl("not a url"))
        assertEquals("", NavidromeSettings.normalizeUrl("192.168.1.5:99999"))
    }

    @Test
    fun privateRangesStopAtTheirEdges() {
        assertTrue(NavidromeSettings.isPrivateIpv4("172.16.0.0"))
        assertTrue(NavidromeSettings.isPrivateIpv4("172.31.255.255"))
        assertTrue(NavidromeSettings.isPrivateIpv4("100.64.0.1"))
        assertTrue(NavidromeSettings.isPrivateIpv4("100.127.255.254"))
        assertFalse(NavidromeSettings.isPrivateIpv4("172.15.0.1"))
        assertFalse(NavidromeSettings.isPrivateIpv4("100.63.0.1"))
        assertFalse(NavidromeSettings.isPrivateIpv4("192.169.0.1"))
        assertFalse(NavidromeSettings.isPrivateIpv4("192.168.1"))
        assertFalse(NavidromeSettings.isPrivateIpv4("192.168.1.256"))
        assertFalse(NavidromeSettings.isPrivateIpv4("192.168.1.0005"))
        assertFalse(NavidromeSettings.isPrivateIpv4("192.168.1.-1"))
        assertFalse(NavidromeSettings.isPrivateIpv4("[::1]"))
    }
}
