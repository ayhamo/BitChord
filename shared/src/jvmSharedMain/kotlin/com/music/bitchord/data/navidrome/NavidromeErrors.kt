package com.music.bitchord.data.navidrome

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Plain words for why connecting to a Navidrome server failed (fork-only, phone and PC).
 *
 * The login test used to show the exception's own text: "Failed to connect to /192.168.1.5:4533",
 * "Unable to parse TLS packet header", "CLEARTEXT communication … not permitted by network security
 * policy" (the first outside user's report, 2026-11-07). Each says what broke inside the app, not
 * what to change in the address. The server's own refusals ("Wrong username or password") are
 * already readable and pass through as they are.
 */
object NavidromeErrors {

    /** [error] as one sentence for someone typing [url] (as typed, or normalized). */
    fun describe(error: Throwable, url: String): String {
        // Causes and suppressed ones too: after a failed TLS handshake OkHttp tries again and throws
        // the last attempt's error, keeping the handshake's only as suppressed.
        val chain = buildList {
            fun walk(t: Throwable, depth: Int) {
                if (depth > 6 || size > 24 || any { it === t }) return
                add(t)
                t.cause?.let { walk(it, depth + 1) }
                t.suppressed.forEach { walk(it, depth + 1) }
            }
            walk(error, 0)
        }
        val address = NavidromeSettings.normalizeUrl(url)
        val https = address.startsWith("https://", ignoreCase = true)
        val host = runCatching { java.net.URI(address).host }.getOrNull().orEmpty()
        val text = chain.mapNotNull { it.message }.joinToString(" ")
        return when {
            address.isEmpty() || chain.any { it is IllegalArgumentException && it.message == "Not a server address" } -> // a JSON parse error is one too
                "That isn't a server address. Try something like http://192.168.1.5:4533"
            text.contains("CLEARTEXT", ignoreCase = true) ->
                "This version of the app can't use http. Update BitChord, or use the server's https:// address"
            chain.any { it is UnknownHostException } ->
                "Can't find a server called $host. Check the address, or use the server's IP"
            chain.any { it is SSLException } ->
                if (https) {
                    "Couldn't make a secure (https) connection. If your server has no certificate, start the address with http://"
                } else {
                    "The secure connection failed. Check the address"
                }
            chain.any { it is ConnectException || it is NoRouteToHostException } ->
                "Nothing answered at that address. Check the address and port (Navidrome's is 4533), and that the server is running" +
                    if (NavidromeSettings.isPrivateIpv4(host) && host.startsWith("100.")) ", and that Tailscale is on" else ""
            chain.any { it is SocketTimeoutException } ->
                "The server didn't answer in time. Check the address and port, and that you're on its network"
            text.startsWith("HTTP 404") ->
                "No Navidrome at that address. Check the path: if it lives under one, add it (…/navidrome)"
            text.startsWith("HTTP 401") || text.startsWith("HTTP 403") ->
                "The server turned the login away (${text.take(8)})"
            Regex("""^HTTP 5\d\d""").containsMatchIn(text) ->
                "The server had an error (${text.take(8)}). Try again in a moment"
            text.startsWith("Not a Subsonic server") || chain.any { it.javaClass.name.startsWith("kotlinx.serialization.") } ->
                "Something answered at that address, but not Navidrome. Check the address and port"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Couldn't connect"
        }
    }

    /** [block], with a failure re-thrown as an [IOException] whose message is [describe]'s. */
    internal inline fun <T> explained(url: String, block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IOException(describe(e, url), e))
        }
}
