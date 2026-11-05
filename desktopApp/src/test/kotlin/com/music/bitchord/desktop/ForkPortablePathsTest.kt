package com.music.bitchord.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForkPortablePathsTest {

    @Test
    fun `a relative path from the launcher is made absolute against the working folder`() {
        // BitChord.exe's "{app}" fallback: ".\data" and so on.
        val expected = Path.of("").toAbsolutePath().resolve("data").resolve("tmp").normalize().toString()
        assertEquals(expected, ForkPortable.absolutized(Path.of(".", "data", "tmp").toString()))
    }

    @Test
    fun `absolute and missing values are left alone`() {
        assertNull(ForkPortable.absolutized(Path.of("").toAbsolutePath().resolve("data").toString()))
        assertNull(ForkPortable.absolutized(null))
        assertNull(ForkPortable.absolutized(""))
    }
}
