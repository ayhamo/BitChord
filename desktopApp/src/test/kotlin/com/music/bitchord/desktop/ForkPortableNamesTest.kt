package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** How Java's Windows Preferences store writes names and values, read back for the portable import. */
class ForkPortableNamesTest {

    @Test
    fun `upper case letters, slashes and backslashes come back`() {
        assertEquals("navidromeUrl", ForkPortable.javaName("navidrome/Url"))
        assertEquals("a/b", ForkPortable.javaName("a\\b"))
        assertEquals("a\\b", ForkPortable.javaName("a//b"))
        assertEquals("com.music.bitchord.desktop", ForkPortable.javaName("com.music.bitchord.desktop"))
    }

    @Test
    fun `a value's escaped characters come back`() {
        assertEquals("Hello World", ForkPortable.javaValue("/Hello /World"))
        assertEquals("é and 中", ForkPortable.javaValue("/u00e9 and /u4e2d"))
        assertEquals("""{"a":"b/c"}""", ForkPortable.javaValue("""{"a":"b\c"}"""))
        assertEquals("tab\there", ForkPortable.javaValue("tab/u0009here"))
    }

    @Test
    fun `a slash before a lower case letter stays`() {
        assertEquals("/x", ForkPortable.javaValue("/x"))
        assertEquals("/u12", ForkPortable.javaValue("/u12"))
    }
}
