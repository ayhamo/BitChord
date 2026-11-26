package com.music.bitchord.desktop

import java.net.CookieHandler
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

    /**
     * Reached from the page, so public, and held below because the page only keeps it weakly.
     * [location] is the engine's address at the moment of the call: a page that is not the web
     * player (a redirect to a login, consent or ad host the engine followed) can call the bridge
     * too, since it is a window member, and must not be able to hand in a token of its choosing.
     */
    class Bridge(private val answer: CompletableFuture<Minted?>, private val location: () -> String? = { null }) {
        fun onTokenPayload(payload: String?) {
            if (payload.isNullOrBlank() || answer.isDone) return
            if (!ForkSpotifyPage.isPlayer(location())) return
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
        var engine: WebEngine? = null
        val bridge = Bridge(answer) { engine?.location }
        // WebKit holds a page's folder until it lets go, so the last one is cleared here rather than after use.
        val temp = java.io.File(System.getProperty("java.io.tmpdir"))
        temp.listFiles { file -> file.name.startsWith("bitchord-spotify-") }?.forEach { it.deleteRecursively() }
        val folder = Files.createTempDirectory("bitchord-spotify-")
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
                            // Only on the web player itself: on any other page the engine was
                            // sent to, the bridge would be offered to that page's scripts.
                            if (ForkSpotifyPage.isPlayer(page.location)) runCatching {
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
            engine = null
            bridge.hashCode() // still reachable until here
            // The listener's sp_dc went into the jar every WebView shares, and the page added its
            // own spotify.com cookies beside it; left there, the sign-in session outlived the mint
            // in memory and rode along with any later WebView that reached a spotify.com host.
            // Only spotify.com's are dropped: a reset would also sign the YouTube pages out.
            runCatching { ForkSpotifyPage.dropCookies(DesktopWebCookies) }
            // The page's storage (its cache, local storage) is the session's too. WebKit may still
            // hold files in it for a moment on Windows: what can't go now goes at exit, and the
            // next mint clears any left over (above).
            if (!folder.toFile().deleteRecursively()) ForkSpotifyPage.deleteAtExit(folder.toFile())
        }
        DesktopTrackLog.log("canvas: Spotify's page ${if (minted != null) "gave a token" else "gave no token"}")
        return minted
    }

    private val HOOK_SCRIPT = """
        (function () {
          if (window.__bitchordTokenHook) return;
          window.__bitchordTokenHook = true;
          var report = function (body) { try { window.$BRIDGE.onTokenPayload(body); } catch (e) {} };
          var isToken = function (u) {
            try { var x = new URL(String(u), location.href); return x.origin === 'https://open.spotify.com' && x.pathname === '/api/token'; }
            catch (e) { return false; }
          };
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

/**
 * Fork: what [ForkSpotifyToken]'s hidden page may trust, pure so it is tested
 * (`ForkSpotifyPageTest`): which address is the web player, and dropping the spotify.com cookies.
 */
internal object ForkSpotifyPage {

    private const val PLAYER_HOST = "open.spotify.com"

    /**
     * Whether [location] is the web player: https, the host exactly open.spotify.com, the default
     * port and no user info. Only then is the bridge installed and a token payload taken.
     */
    fun isPlayer(location: String?): Boolean {
        if (location.isNullOrBlank()) return false
        return runCatching {
            val uri = URI(location)
            uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.host.equals(PLAYER_HOST, ignoreCase = true)
        }.getOrDefault(false)
    }

    /** The hosts the web player and its sign-in set cookies on; a `.spotify.com` cookie shows on all. */
    private val SPOTIFY_URIS = listOf("https://open.spotify.com/", "https://accounts.spotify.com/", "https://www.spotify.com/")
        .map(::URI)

    /**
     * Expires every cookie [jar] sends to the Spotify hosts, and only those. The app's jar is
     * WebKit's own `CookieManager` (see [DesktopWebCookies]), which has no public way to list or
     * delete a cookie, so this goes through the one interface every [CookieHandler] has: read the
     * names it would send to each host, then answer each with an already-expired `Set-Cookie`, as a
     * server signing out would, once for the `.spotify.com` domain and once host-only (a cookie is
     * replaced only by one with its name, domain and path). Cookies on a path other than `/` would
     * survive; the player sets none.
     */
    fun dropCookies(jar: CookieHandler) {
        for (uri in SPOTIFY_URIS) {
            val names = jar.get(uri, emptyMap())["Cookie"].orEmpty()
                .flatMap { it.split(';') }
                .map { it.substringBefore('=').trim() }
                .filter { it.isNotEmpty() && !it.startsWith('$') }
                .toSet()
            if (names.isEmpty()) continue
            val expired = names.flatMap { name ->
                listOf(
                    "$name=; Domain=.spotify.com; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure",
                    "$name=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure",
                )
            }
            jar.put(uri, mapOf("Set-Cookie" to expired))
        }
    }

    private val leftovers = java.util.Collections.synchronizedSet(LinkedHashSet<java.io.File>())

    /** [folder] deleted as the app exits; one hook for all of them. */
    fun deleteAtExit(folder: java.io.File) {
        val first = synchronized(leftovers) { leftovers.isEmpty().also { leftovers += folder } }
        if (first) Runtime.getRuntime().addShutdownHook(Thread { synchronized(leftovers) { leftovers.toList() }.forEach { it.deleteRecursively() } })
    }
}
