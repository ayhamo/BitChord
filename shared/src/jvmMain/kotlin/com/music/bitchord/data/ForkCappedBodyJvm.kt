package com.music.bitchord.data

import java.io.InputStream
import java.net.http.HttpResponse
import java.nio.charset.Charset

/**
 * Fork: [ForkCappedBody] for `java.net.http` answers (the PC's own clients). Here rather than beside
 * the OkHttp form because `jvmSharedMain` also compiles for Android, which has no `java.net.http`.
 * Send with `BodyHandlers.ofInputStream()` and read through these, never `ofString()`.
 */
object ForkCappedHttp {

    /** The body, capped at [max] bytes; [java.io.IOException] past that or when empty. */
    fun bytes(response: HttpResponse<InputStream>, max: Int): ByteArray {
        val body = response.body()
        val declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        if (declared > max) {
            body.close()
            throw java.io.IOException("answer over ${max / ForkCappedBody.KB} KB")
        }
        return body.use { ForkCappedBody.read(it, max) }
    }

    /** [bytes] as text, in the `Content-Type` charset when one is named (UTF-8 otherwise). */
    fun text(response: HttpResponse<InputStream>, max: Int): String {
        val charset = response.headers().firstValue("Content-Type").orElse("")
            .split(';').map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim('"', ' ')
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
        return String(bytes(response, max), charset)
    }
}
