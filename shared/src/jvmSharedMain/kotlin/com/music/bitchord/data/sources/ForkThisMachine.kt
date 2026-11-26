package com.music.bitchord.data.sources

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

/**
 * Fork: whether a link's host is this machine, for every rule that refuses one (protocol.md §1 and
 * 8.1.5 rule 5: an addon may not point the player at a service on the device itself; the extension's
 * step-2 fix 6a1acb0 and the combined audit 2026-11-26 "local-host check gaps").
 *
 * The checks before this read the host as text and knew `localhost`, `127.x.x.x` in four parts and a
 * few IPv6 spellings, while the address a client connects to is what the platform makes of the text:
 * `127.0.0.2` (all of 127/8 is loopback), `127.1` and `2130706433` (the short and decimal IPv4 forms,
 * which the JVM's and libc's parsers accept), `0.x.x.x` (connects here on Linux and Android),
 * `[0:0:0:0:0:0:0:1]`, `[::]`, and the IPv4-mapped `[::ffff:127.0.0.1]` / `[::ffff:7f00:1]`. A literal
 * address also never reaches a client's `Dns` (OkHttp connects to an IP literal without asking it), so a
 * lookup guard can't stand in for this check.
 *
 * Literals are parsed here, never looked up: IPv4 by the inet_aton rules (one to four parts, each
 * decimal, octal with a leading 0 or hex with 0x, the last filling the remaining bytes), IPv6 by
 * [InetAddress.getByName], which parses a literal with a colon in it without any DNS.
 */
object ForkThisMachine {

    /** [host] (bracketed or not, any case, a trailing dot allowed) names this machine. */
    fun isThisMachine(host: String): Boolean {
        val h = host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (':' in h) return ipv6Here(h)
        val v4 = ipv4(h) ?: return false
        val first = (v4 ushr 24).toInt()
        return first == 127 || first == 0
    }

    /** The 32-bit address [text] spells by the inet_aton rules, or null when it isn't an IPv4 literal. */
    internal fun ipv4(text: String): Long? {
        val parts = text.split('.')
        if (parts.isEmpty() || parts.size > 4 || parts.any { it.isEmpty() }) return null
        val values = parts.map { part(it) ?: return null }
        val head = values.dropLast(1)
        if (head.any { it > 255 }) return null
        val lastBytes = 4 - head.size
        val last = values.last()
        if (last >= (1L shl (8 * lastBytes))) return null
        var address = 0L
        head.forEachIndexed { i, v -> address = address or (v shl (8 * (3 - i))) }
        return address or last
    }

    private fun part(p: String): Long? = when {
        p.startsWith("0x") -> p.drop(2).takeIf { it.isNotEmpty() && it.length <= 8 }?.toLongOrNull(16)
        p.length > 1 && p.startsWith("0") -> p.drop(1).takeIf { it.length <= 11 && it.all { c -> c in '0'..'7' } }?.toLongOrNull(8)
        else -> p.takeIf { it.length <= 10 && it.all(Char::isDigit) }?.toLongOrNull()
    }

    private fun ipv6Here(text: String): Boolean {
        val h = text.substringBefore('%') // a zone id says which interface, not which address
        // only a literal's characters, so getByName can't be handed a name to look up
        if (h.any { !(it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.') }) return false
        val address = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return true
        // an IPv4-mapped address comes back as the IPv4 one; ::a.b.c.d (IPv4-compatible) does not
        if (address is Inet4Address) return (address.address[0].toInt() and 0xff).let { it == 127 || it == 0 }
        val b = address.address
        val compatible = (0 until 12).all { b[it].toInt() == 0 }
        return compatible && (b[12].toInt() and 0xff).let { it == 127 || it == 0 }
    }
}
