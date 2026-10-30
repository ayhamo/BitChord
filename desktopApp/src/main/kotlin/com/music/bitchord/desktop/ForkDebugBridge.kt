package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.swing.SwingUtilities
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Fork: a test door into the running app, so it can be driven and checked without
 * touching the real mouse or keyboard. Off unless started with
 * `-Dbitchord.debugBridge=<port>`, and only ever on the loopback address.
 *
 * Input is posted to the app's own window as AWT events, so the pointer on the
 * screen stays where it is and the window needn't be in front. Coordinates are
 * pixels of a window screenshot (`docker/pc-shot.ps1`), scale already taken out.
 *
 * - `GET /state`: what is playing and the queue, as JSON
 * - `POST /click?x=&y=`, `POST /scroll?x=&y=&notches=`
 * - `POST /type?text=`, `POST /key?code=` (a `KeyEvent` VK_ code)
 */
internal object ForkDebugBridge {

    private const val QUEUE_LIMIT = 200

    @Volatile var engine: DesktopPlaybackEngine? = null
    @Volatile var queue: () -> DesktopQueue = { DesktopQueue() }
    @Volatile private var window: Window? = null

    fun attach(window: Window) {
        this.window = window
    }

    fun install() {
        val port = System.getProperty("bitchord.debugBridge")?.toIntOrNull() ?: return
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        server.createContext("/state") { respond(it) { state() } }
        server.createContext("/click") { respond(it) { click(it) } }
        server.createContext("/scroll") { respond(it) { scroll(it) } }
        server.createContext("/type") { respond(it) { type(it) } }
        server.createContext("/key") { respond(it) { key(it.query("code").toInt()) } }
        server.start()
        DesktopTrackLog.log("debug bridge on 127.0.0.1:$port")
    }

    private fun state(): String = buildJsonObject {
        val playback = engine?.state?.value
        put("title", playback?.song?.title)
        put("artist", playback?.song?.artist)
        put("id", playback?.song?.videoId)
        put("playing", playback?.isPlaying)
        put("loading", playback?.isLoading)
        put("positionMs", playback?.positionMs)
        val current = queue()
        put("queueIndex", current.index)
        put("queueSize", current.songs.size)
        putJsonArray("queue") {
            current.songs.take(QUEUE_LIMIT).forEach { song ->
                addJsonObject {
                    put("id", song.videoId)
                    put("title", song.title)
                    put("artist", song.artist)
                }
            }
        }
    }.toString()

    private fun click(exchange: HttpExchange): String {
        val (target, x, y) = at(exchange)
        val ids = listOf(MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)
        ids.forEach { id ->
            val button = if (id == MouseEvent.MOUSE_MOVED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1
            val mask = if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0
            post(MouseEvent(target, id, System.currentTimeMillis(), mask, x, y, if (button == MouseEvent.NOBUTTON) 0 else 1, false, button))
        }
        return "clicked ${target.javaClass.simpleName} $x,$y"
    }

    private fun scroll(exchange: HttpExchange): String {
        val (target, x, y) = at(exchange)
        val notches = exchange.query("notches").toInt()
        post(MouseWheelEvent(target, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, notches))
        return "scrolled $notches"
    }

    private fun type(exchange: HttpExchange): String {
        val text = exchange.query("text")
        val target = focusTarget()
        text.forEach { c -> post(KeyEvent(target, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, c)) }
        return "typed ${text.length}"
    }

    private fun key(code: Int): String {
        val target = focusTarget()
        listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED).forEach { id ->
            post(KeyEvent(target, id, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED))
        }
        return "key $code"
    }

    /** The component under a screenshot pixel, and the point in that component's own coordinates. */
    private fun at(exchange: HttpExchange): Triple<Component, Int, Int> {
        val shown = window ?: error("no window yet")
        val scale = shown.graphicsConfiguration.defaultTransform
        val x = (exchange.query("x").toDouble() / scale.scaleX).toInt()
        val y = (exchange.query("y").toDouble() / scale.scaleY).toInt()
        val target = SwingUtilities.getDeepestComponentAt(shown, x, y) ?: shown
        val point = SwingUtilities.convertPoint(shown, x, y, target)
        return Triple(target, point.x, point.y)
    }

    private fun focusTarget(): Component {
        val shown = window ?: error("no window yet")
        return shown.focusOwner ?: shown
    }

    private fun post(event: java.awt.AWTEvent) {
        EventQueue.invokeLater { Toolkit.getDefaultToolkit().systemEventQueue.postEvent(event) }
    }

    private fun HttpExchange.query(name: String): String =
        requestURI.query.orEmpty().split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
            ?: error("missing $name")

    private fun respond(exchange: HttpExchange, answer: () -> String) {
        val (code, body) = runCatching { 200 to answer() }.getOrElse { 400 to (it.message ?: "failed") }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
