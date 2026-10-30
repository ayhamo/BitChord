package com.music.bitchord.desktop

import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.scene.web.WebEngine
import javafx.util.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import netscape.javascript.JSObject

/**
 * Fork: Spotify's token the way the phone's `SpotifyToken` gets it: the real web player in an
 * offscreen browser with the listener's cookie, reading the token the page mints for itself.
 *
 * [DesktopSpotifyToken] signs the request itself because upstream took the desktop to have no
 * browser to do this in, but the sign-in window's JavaFX WebKit is one. The phone's notes say what
 * signing gets: a token the other endpoints refuse. Here Spotify doesn't even give one any more
 * ("would not mint an access token"), which left "Made for you", the library and Canvas all empty.
 */
internal object ForkSpotifyToken {

    class Minted(val token: String, val expiresAtMs: Long?, val clientId: String?)

    private const val TIMEOUT_SECONDS = 25L
    private const val HOOK_EVERY_MS = 30.0
    private const val BRIDGE = "bitchordSpotify"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Reached from the page, so public, and held below because the page only keeps it weakly. */
    class Bridge(private val answer: CompletableFuture<Minted?>) {
        fun onTokenPayload(payload: String?) {
            if (payload.isNullOrBlank() || answer.isDone) return
            runCatching {
                val root = json.parseToJsonElement(payload).jsonObject
                val token = root["accessToken"]?.jsonPrimitive?.contentOrNull
                // The player also mints an anonymous one before the cookie counts.
                val anonymous = root["isAnonymous"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                if (token.isNullOrBlank() || anonymous) return
                answer.complete(
                    Minted(
                        token,
                        root["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                        root["clientId"]?.jsonPrimitive?.contentOrNull,
                    ),
                )
            }
        }
    }

    /** Blocks until the page gives a token, null when it doesn't in time. Not on the JavaFX thread. */
    fun mint(cookie: String): Minted? {
        if (!DesktopPoTokenWebView.available) return null
        val answer = CompletableFuture<Minted?>()
        val bridge = Bridge(answer)
        val folder = Files.createTempDirectory("bitchord-spotify-")
        var engine: WebEngine? = null
        var hooks: Timeline? = null
        Platform.runLater {
            runCatching {
                DesktopWebCookies.put(
                    URI("https://open.spotify.com/"),
                    mapOf("Set-Cookie" to listOf("sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")),
                )
                val page = WebEngine().also { engine = it }
                page.userDataDirectory = folder.toFile() // empty, so the page has no cached token and mints one
                page.userAgent = CANVAS_UA
                // WebEngine has no "before the page's scripts" moment. The hook is one line that
                // checks itself, and the player's token request comes well after its bundle loads.
                hooks = Timeline(
                    KeyFrame(
                        Duration.millis(HOOK_EVERY_MS),
                        {
                            runCatching {
                                (page.executeScript("window") as JSObject).setMember(BRIDGE, bridge)
                                page.executeScript(HOOK_SCRIPT)
                            }
                        },
                    ),
                ).apply {
                    cycleCount = Animation.INDEFINITE
                    play()
                }
                page.load("https://open.spotify.com/")
            }.onFailure { answer.complete(null) }
        }
        val minted = runCatching { answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrNull()
        Platform.runLater {
            hooks?.stop()
            engine?.load("about:blank")
            bridge.hashCode() // still reachable until here
            folder.toFile().deleteRecursively()
        }
        DesktopTrackLog.log("canvas: Spotify's page ${if (minted != null) "gave a token" else "gave no token"}")
        return minted
    }

    private val HOOK_SCRIPT = """
        (function () {
          if (window.__bitchordTokenHook) return;
          window.__bitchordTokenHook = true;
          var report = function (body) { try { window.$BRIDGE.onTokenPayload(body); } catch (e) {} };
          var isToken = function (u) { try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; } };
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function (input, init) {
              var url = (input && input.url) ? input.url : input;
              var result = origFetch.apply(this, arguments);
              if (isToken(url)) {
                try { result.then(function (res) { res.clone().text().then(report).catch(function () {}); }).catch(function () {}); } catch (e) {}
              }
              return result;
            };
          }
          var origOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) { this.__bitchordUrl = url; return origOpen.apply(this, arguments); };
          var origSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            try { xhr.addEventListener('load', function () { if (isToken(xhr.__bitchordUrl)) { try { report(xhr.responseText); } catch (e) {} } }); } catch (e) {}
            return origSend.apply(this, arguments);
          };
        })();
    """.trimIndent()
}
