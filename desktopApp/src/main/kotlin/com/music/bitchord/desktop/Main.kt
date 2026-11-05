package com.music.bitchord.desktop

import androidx.compose.runtime.CompositionLocalProvider
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.innertube.potoken.PoTokenGenerator
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.spotify.SpotifyLibrary
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity // fork
import androidx.compose.ui.unit.DpSize // fork
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.logo
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource

fun main() {
    ForkStartup.mainEntered() // fork: start of the "startup: first frame" timing
    ForkPortable.absolutePaths() // fork: a portable folder named outside the ANSI code page gets relative paths (BitChord.c)
    ForkSingleInstance.claimOrExit() // fork: a second start shows the running window and exits
    ForkPortable.install() // fork: the portable folder's own settings store, before anything reads one
    // Before any WebView exists, so the sign-in window can start over with an empty jar.
    DesktopWebCookies.install()
    DesktopWindowVisibility.install()
    // The player is the phone's, from the shared UI module; this is what it reads underneath.
    PlayerPlatform.install(DesktopPlayerHost)
    com.music.bitchord.ui.AppUi.install(DesktopAppUiHost)
    // The data layer both apps share logs through here, and keeps translated
    // lyrics beside the rest of the desktop's cache.
    DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    LyricsTranslation.cacheDir = DesktopMediaCache.directory.toFile()
    // The Spotify library both apps share, on the tokens the web player mints in JavaFX.
    SpotifyLibrary.auth = SpotifyLibrary.Auth {
        DesktopSpotifyToken.accessToken()?.let { SpotifyLibrary.Tokens(it, DesktopSpotifyToken.clientToken()) }
    }
    // Desktop follows its own language picker (zh-Hant / zh-Hans preserved).
    com.music.bitchord.data.innertube.Innertube.appLanguage = { DesktopStrings.resolvedTag() }
    // YouTube playback is the phone's StreamResolver over InnerTubeX, with BotGuard PoTokens
    // minted in JavaFX's WebView where the phone uses Android's.
    TrackLog.echo = { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    StreamResolver.maxKbps = {
        DesktopStreamClient.ceiling.get() ?: DesktopSourceRegistry.ceiling(null).maxKbps
    }
    ForkYtDlp.applyPrewarm() // fork: no InnerTubeX prewarm (WebKit, QuickJS) while the owner's yt-dlp answers first
    InnerTubeXResolver.init(
        filesDir = DesktopMediaCache.directory.toFile(),
        store = DesktopInnerTubeXStore,
        poTokenProvider = PoTokenGenerator(
            createMinter = { DesktopPoTokenWebView.getNewPoTokenGenerator() },
            available = { DesktopPoTokenWebView.available },
        ).asTokenProvider(),
    )
    // The public "apps open right now" count; see Presence.
    DesktopPresence.install()
    ForkDebugBridge.install() // fork: test door, off unless asked for
    ForkNavidrome.install() // fork
    ForkPlaylistSync.install() // fork: "Keep playlists updated"
    ForkPaxSenixKey.install() // fork: the PaxSenix API key, before any lyrics are asked for
    // fork: the Apple Music token backup's store (ForkAppleTokenBackup); "token" in the key keeps it out of backups
    com.music.bitchord.data.canvas.ForkAppleTokenBackup.store = object : com.music.bitchord.data.canvas.ForkAppleTokenBackup.Store {
        override fun read(): String? = DesktopPersistence().string("fork_apple_token_backup").ifEmpty { null }
        override fun write(line: String?) = DesktopPersistence().saveString("fork_apple_token_backup", line.orEmpty())
    }
    ForkCopyLog.install() // fork: after TrackLog.echo and DebugLog.sink above (ForkCopyLog.kt)
    desktopMain()
}

private fun desktopMain() = application {
    // Closing puts the window away rather than ending the process, while there is a tray icon to
    // bring it back from — see [DesktopWindowVisibility].
    val visible by DesktopWindowVisibility.visible.collectAsState()
    val state = ForkWindowState.rememberState(DpSize(1_220.dp, 780.dp), MIN_WINDOW_WIDTH_DP.toInt(), MIN_WINDOW_HEIGHT_DP.toInt()) // fork: was rememberWindowState(width = 1_220.dp, height = 780.dp); the last size and place
    LaunchedEffect(visible) {
        if (visible && state.isMinimized) {
            state.isMinimized = false
        }
    }
    LaunchedEffect(visible, state.isMinimized) { ForkMemoryTrim.whileAway(!visible || state.isMinimized) } // fork: covers dropped while away (Revived a6b5311c)
    // Hoisted so the player can fill the screen and the caption buttons can maximize — see
    // [DesktopWindowMode]. Fullscreen on Windows is a native borderless window that covers the
    // taskbar, so it needs no placement change here; only ordinary maximize does.
    val placement by DesktopWindowMode.placement.collectAsState()
    LaunchedEffect(placement) { state.placement = placement }
    // ...and back, for the times the window is moved between placements by something that is not
    // us; see [DesktopWindowMode.adopt].
    LaunchedEffect(state) {
        snapshotFlow { state.placement }.collect(DesktopWindowMode::adopt)
    }
    val maximized by DesktopWindowMode.maximized.collectAsState()
    LaunchedEffect(maximized) {
        if (DesktopPlatform.isMac) {
            DesktopMacFrame.updateCornerRadius(maximized)
        }
    }
    Window(
        onCloseRequest = { if (DesktopWindowVisibility.onCloseRequest()) { ForkSingleInstance.release(); exitApplication() } }, // fork: release() — a start made while quitting no longer hands over to this window
        visible = visible,
        title = "BitChord",
        icon = painterResource(Res.drawable.logo),
        state = state,
        // No system title bar on Windows, where the application draws its own instead — see
        // [DesktopWindowChrome].
        undecorated = DesktopPlatform.drawsOwnWindowFrame,
        // For Mica and Acrylic behind the sidebar and top bar: only a transparent window lets
        // DWM's material show through the chrome. Fixed at creation, so it is on wherever the
        // material is possible and the chosen one can be switched live — see [DesktopWindowBackdrop].
        transparent = DesktopWindowBackdrop.available,
        resizable = true,
    ) {
        val composeWindow = window
        val openingSize = remember { state.size }
        LaunchedEffect(composeWindow) {
            DesktopWindowVisibility.attachWindow(composeWindow)
            ForkDebugBridge.attach(composeWindow) // fork
            ForkWindowState.track(composeWindow) // fork: remembers the size, place and maximized state
            // AWT's default is white, and it is what shows for the frame or two a moved or resized
            // window takes to repaint: a white band along its edges.
            // Not over a transparent window, whose clear background is what the material shows
            // through; DWM moves that window, so there is no band to hide.
            if (!DesktopWindowBackdrop.available) {
                composeWindow.background = java.awt.Color.BLACK
                composeWindow.contentPane.background = java.awt.Color.BLACK
            } else {
                composeWindow.background = java.awt.Color(0, 0, 0, 0)
                composeWindow.contentPane.background = java.awt.Color(0, 0, 0, 0)
                (composeWindow.contentPane as? javax.swing.JComponent)?.isOpaque = false
                composeWindow.rootPane.isOpaque = false
            }
            // AWT measures this in device pixels while Compose's window state is in dp. Keeping
            // the scale in the conversion makes the usable minimum consistent on every display.
            val transform = if (DesktopPlatform.isWindows) java.awt.geom.AffineTransform() else composeWindow.graphicsConfiguration.defaultTransform // fork: Windows' AWT is in logical units already (Desktop Revived 17ecaedb); scaled, a 150% screen opened the window 1.5x too big and a remembered size grew each start
            composeWindow.minimumSize = Dimension(
                (MIN_WINDOW_WIDTH_DP * transform.scaleX).roundToInt(),
                (MIN_WINDOW_HEIGHT_DP * transform.scaleY).roundToInt(),
            )
            // Setting AWT's minimum during the first composition can resize the native peer to
            // that minimum without updating Compose's state. Compose would then keep laying out a
            // 1220dp surface inside a 900dp window, cropping the right edge and putting its resize
            // boundary off-screen until a maximize/restore cycle synchronised the two. Re-assert
            // the requested opening size on the native peer so both layers start in agreement.
            composeWindow.size = Dimension(
                (openingSize.width.value * transform.scaleX).roundToInt(),
                (openingSize.height.value * transform.scaleY).roundToInt(),
            )
            if (DesktopPlatform.isWindows && DesktopWindowsFrame.install("BitChord")) {
                DesktopWindowBackdrop.apply()
            } else if (DesktopPlatform.isMac && DesktopMacFrame.install(composeWindow)) {
                DesktopWindowBackdrop.apply()
            }
            if (ForkWindowState.startMaximized && !DesktopWindowsFrame.toggleMaximize()) DesktopWindowMode.toggleMaximized() // fork: maximized again, as it was left
        }
        val actions = remember {
            DesktopWindowActions(
                minimize = {
                    if (!DesktopWindowsFrame.minimize()) state.isMinimized = true
                },
                toggleMaximize = {
                    // fork: in full screen this (a double click on the player's caption) leaves full
                    // screen. The native toggle went first and never reached
                    // DesktopWindowMode.toggleMaximized, so it maximized the borderless popup itself:
                    // still flagged full screen, taskbar band unpainted.
                    if (DesktopWindowMode.fullScreen.value || DesktopWindowsFrame.fullscreenActive) {
                        DesktopWindowMode.exit()
                    } else if (!DesktopWindowsFrame.toggleMaximize()) {
                        // An undecorated AWT window otherwise maximizes to the monitor bounds on
                        // Windows when the native frame bridge is unavailable. Give AWT the
                        // monitor's usable work area before Compose switches the placement.
                        if (DesktopPlatform.isWindows && !DesktopWindowMode.maximized.value) {
                            val screen = composeWindow.graphicsConfiguration.bounds
                            val insets = Toolkit.getDefaultToolkit().getScreenInsets(
                                composeWindow.graphicsConfiguration,
                            )
                            composeWindow.maximizedBounds = Rectangle(
                                screen.x + insets.left,
                                screen.y + insets.top,
                                screen.width - insets.left - insets.right,
                                screen.height - insets.top - insets.bottom,
                            )
                        }
                        DesktopWindowMode.toggleMaximized()
                    }
                },
                // The same door the system's close button went through, so the tray keeps the
                // process alive exactly as it did before.
                close = { if (DesktopWindowVisibility.onCloseRequest()) { ForkSingleInstance.release(); exitApplication() } }, // fork: release(), as above
            )
        }
        CompositionLocalProvider(
            LocalDesktopWindowActions provides actions,
            LocalDesktopWindowScope provides this,
            LocalDensity provides ForkUiScale.density(), // fork: the UI scale setting (Desktop Revived 8782585b)
        ) {
            ForkFirstFrameProbe() // fork: logs the first frame, and lets deferred start-up work go (ForkStartup)
            BitChordDesktopApp()
        }
    }
}

private const val MIN_WINDOW_WIDTH_DP = 900.0
private const val MIN_WINDOW_HEIGHT_DP = 600.0
