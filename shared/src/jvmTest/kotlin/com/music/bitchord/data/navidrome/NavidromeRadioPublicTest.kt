package com.music.bitchord.data.navidrome

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which station streams [NavidromeRadio] may name to radio-browser.info. */
class NavidromeRadioPublicTest {
    private fun public(url: String) = NavidromeRadio.isPublicStream(url, serverHost = "music.example.org")

    @Test
    fun `an internet station is asked about`() {
        assertTrue(public("https://wdr-1live-live.icecastssl.wdr.de/wdr/1live/live/mp3/128/stream.mp3"))
        assertTrue(public("http://8.8.4.4:8000/stream"))
    }

    @Test
    fun `private hosts stay here`() {
        listOf(
            "http://192.168.0.214:8000/radio",
            "http://10.1.2.3/stream",
            "http://172.20.0.5/stream",
            "http://100.88.139.51:8000/live", // Tailscale
            "http://127.0.0.1/stream",
            "http://169.254.1.1/stream",
            "http://[fd7a:115c:a1e0::1]:8000/live",
            "http://[::1]/live",
            "https://home.tail1234.ts.net/live",
            "http://nas.local:8000/radio",
            "http://router.lan/radio",
            "http://nas/radio",
            "https://music.example.org/rest/stream",
            "rtsp://example.com/radio",
            "not a url",
        ).forEach { assertFalse(public(it), it) }
    }
}
