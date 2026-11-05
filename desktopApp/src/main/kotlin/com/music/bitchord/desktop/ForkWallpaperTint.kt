package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Reads the wallpaper's location; changes nothing on the desktop. */
private interface ForkWallpaperUser32 : StdCallLibrary {
    fun SystemParametersInfoW(action: Int, parameter: Int, output: Pointer, flags: Int): Boolean
}

/**
 * fork: the "Wallpaper tint" window material ([DesktopBackdrop.TINT]), Desktop Revived's
 * `DesktopSidebarMica` idea (revived/1.9.1), picked by the owner on 2026-11-05 as the second choice
 * after Mica because Acrylic, a live blur of whatever window lies behind, came out patchy gray.
 *
 * The window stays solid: no DWM material, no blur, no see-through. The wallpaper's average colour is
 * sampled off the UI thread (a ~48x48 grid of a subsampled decode) and only again when the file
 * changes, and a little of it is mixed into the app's dark ground, so the app takes the desktop's
 * hue the way Mica does without ever showing a window behind it. Off (the plain ground) when Windows'
 * transparency effects or high contrast are off, as Windows' own materials are.
 */
internal object ForkWallpaperTint {
    private const val SPI_GETDESKWALLPAPER = 0x0073
    private const val WALLPAPER_BUFFER_CHARS = 4096
    private const val MAX_IMAGE_BYTES = 64L * 1024L * 1024L

    /**
     * How much of the wallpaper goes into the ground. Revived's 6.5% was hard to see on our
     * lighter 0x202020 ground; at 10% a blue or orange wallpaper reads as a tint and white text
     * keeps over 12:1 (tested with pure white and pure red in [ForkWallpaperTintTest]).
     */
    internal const val MIX = 0.10f

    /** How often the wallpaper file is checked for a change while the tint is on. */
    private const val RECHECK_MILLIS = 30_000L

    private val user32 by lazy {
        if (!DesktopPlatform.isWindows) null else runCatching {
            Native.load("user32", ForkWallpaperUser32::class.java)
        }.getOrNull()
    }

    private val _tone = MutableStateFlow<Color?>(null)
    val tone: StateFlow<Color?> = _tone
    private val cacheLock = Any()
    private var cachedSignature: String? = null
    private var cachedTone: Color? = null

    /** The ground with the wallpaper mixed in; the ground itself when there is no wallpaper colour. */
    fun surface(wallpaper: Color?, ground: Color): Color =
        if (wallpaper == null) ground else lerp(ground, wallpaper, MIX).copy(alpha = 1f)

    /** The tinted ground while the tint is the active material, else null. Re-reads the wallpaper. */
    @Composable
    fun ground(base: Color): Color? {
        val active by DesktopWindowBackdrop.active.collectAsState()
        val on = active == DesktopBackdrop.TINT
        LaunchedEffect(on) {
            while (on) {
                refresh()
                delay(RECHECK_MILLIS)
            }
        }
        val wallpaper by tone.collectAsState()
        return if (on) surface(wallpaper, base) else null
    }

    private suspend fun refresh() {
        _tone.value = withContext(Dispatchers.IO) {
            if (!DesktopPlatform.isWindows || !transparencyAllowed()) null else readTone()
        }
    }

    private fun transparencyAllowed(): Boolean {
        val highContrast = runCatching {
            Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on") as? Boolean
        }.getOrNull() ?: false
        if (highContrast) return false
        return runCatching {
            Advapi32Util.registryGetIntValue(
                WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "EnableTransparency",
            ) != 0
        }.getOrDefault(true)
    }

    private fun readTone(): Color? {
        val wallpaper = wallpaperPath()?.let(::File)?.takeIf(File::isFile)
            ?: System.getenv("APPDATA")?.let {
                File(it, "Microsoft\\Windows\\Themes\\TranscodedWallpaper")
            }?.takeIf(File::isFile)
            ?: return null
        if (wallpaper.length() !in 1L..MAX_IMAGE_BYTES) return null
        val signature = wallpaper.absolutePath + ":" + wallpaper.lastModified() + ":" + wallpaper.length()
        synchronized(cacheLock) {
            if (signature == cachedSignature) return cachedTone
        }
        val tone = sample(wallpaper)
        synchronized(cacheLock) {
            cachedSignature = signature
            cachedTone = tone
        }
        return tone
    }

    private fun wallpaperPath(): String? {
        val native = user32 ?: return null
        val output = Memory(WALLPAPER_BUFFER_CHARS * 2L).apply { clear() }
        val ok = runCatching {
            native.SystemParametersInfoW(SPI_GETDESKWALLPAPER, WALLPAPER_BUFFER_CHARS, output, 0)
        }.getOrDefault(false)
        if (!ok) return null
        return runCatching { output.getWideString(0L).trim() }.getOrNull()?.takeIf(String::isNotEmpty)
    }

    private fun sample(file: File): Color? = runCatching {
        ImageIO.createImageInputStream(file)?.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width > 32_768 || height > 32_768) return@use null
                val step = maxOf(1, maxOf(width, height) / 96)
                val parameters = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                val preview = reader.read(0, parameters)
                val xStep = maxOf(1, preview.width / 48)
                val yStep = maxOf(1, preview.height / 48)
                var red = 0L
                var green = 0L
                var blue = 0L
                var pixels = 0L
                for (y in 0 until preview.height step yStep) {
                    for (x in 0 until preview.width step xStep) {
                        val rgb = preview.getRGB(x, y)
                        if ((rgb ushr 24) < 128) continue
                        red += (rgb ushr 16) and 0xFF
                        green += (rgb ushr 8) and 0xFF
                        blue += rgb and 0xFF
                        pixels++
                    }
                }
                if (pixels == 0L) null else Color(
                    (red.toFloat() / pixels / 255f).coerceIn(0f, 1f),
                    (green.toFloat() / pixels / 255f).coerceIn(0f, 1f),
                    (blue.toFloat() / pixels / 255f).coerceIn(0f, 1f),
                    1f,
                )
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()
}
