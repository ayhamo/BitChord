package com.music.bitchord.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals

/** A clip kept or handed over as OTHER reads back as v1.10's APPLE when it is on Apple's hosts. */
class ForkAppleOriginTest {

    @Test
    fun appleHostsBecomeApple() {
        assertEquals(CanvasSource.APPLE, appleOrigin(CanvasSource.OTHER, "https://mvod.itunes.apple.com/itunes-assets/x/P1.m3u8"))
        assertEquals(CanvasSource.APPLE, appleOrigin(CanvasSource.OTHER, "https://is1-ssl.mzstatic.com/a/b.mp4"))
    }

    @Test
    fun otherClipsAndKnownSourcesStay() {
        assertEquals(CanvasSource.OTHER, appleOrigin(CanvasSource.OTHER, "https://example.com/apple.com/x.m3u8"))
        assertEquals(CanvasSource.OTHER, appleOrigin(CanvasSource.OTHER, null))
        assertEquals(CanvasSource.SPOTIFY, appleOrigin(CanvasSource.SPOTIFY, "https://mzstatic.com/x"))
        assertEquals(CanvasSource.VIDEO, appleOrigin(CanvasSource.VIDEO, "https://mvod.itunes.apple.com/x"))
    }
}
