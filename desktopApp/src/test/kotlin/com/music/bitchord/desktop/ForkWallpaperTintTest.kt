package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ForkWallpaperTintTest {
    private val ground = Color(0xFF202020)

    @Test
    fun noWallpaper_keepsTheGround() {
        assertEquals(ground, ForkWallpaperTint.surface(null, ground))
    }

    @Test
    fun wallpaper_tintsTheGroundAndStaysSolid() {
        val warm = ForkWallpaperTint.surface(Color(0xFFFF5018), ground)
        val cool = ForkWallpaperTint.surface(Color(0xFF1850FF), ground)
        assertNotEquals(warm, cool)
        assertNotEquals(ground, warm)
        assertEquals(1f, warm.alpha)
        assertEquals(1f, cool.alpha)
    }

    @Test
    fun extremes_stayDarkEnoughForWhiteText() {
        for (wallpaper in listOf(Color.White, Color.Red, Color.Blue, Color.Green, Color.Black)) {
            val tinted = ForkWallpaperTint.surface(wallpaper, ground)
            assertTrue(tinted.red < 0.25f && tinted.green < 0.25f && tinted.blue < 0.25f, "$wallpaper -> $tinted")
        }
    }

    @Test
    fun tint_isNotASystemMaterial_andSitsBetweenMicaAndAcrylic() {
        assertEquals(
            listOf(DesktopBackdrop.OFF, DesktopBackdrop.MICA, DesktopBackdrop.TINT, DesktopBackdrop.ACRYLIC),
            DesktopBackdrop.entries.toList(),
        )
        assertTrue(!DesktopBackdrop.TINT.isMaterial && !DesktopBackdrop.OFF.isMaterial)
        assertTrue(DesktopBackdrop.MICA.isMaterial && DesktopBackdrop.ACRYLIC.isMaterial)
    }
}
