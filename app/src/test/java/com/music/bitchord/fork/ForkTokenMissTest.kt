package com.music.bitchord.fork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Audit 2026-11-26 2a #1: a failed Spotify token mint holds back that sign-in and those settings only. */
class ForkTokenMissTest {

    @Test
    fun aNewSignInOrAChangedSettingIsAskedAtOnce() {
        val miss = ForkTokenMiss(waitMs = 600_000)
        val old = ForkTokenMiss.keyOf("cookie-a", generation = 3)
        assertNull(miss.retryAt(old, now = 0))
        assertEquals(600_000L, miss.missed(old, now = 0))
        assertEquals(600_000L, miss.retryAt(old, now = 1_000)) // the same sign-in waits
        assertNull(miss.retryAt(ForkTokenMiss.keyOf("cookie-b", 3), now = 1_000)) // signed in again
        assertNull(miss.retryAt(ForkTokenMiss.keyOf("cookie-a", 4), now = 1_000)) // a canvas setting changed (§13 rule 10)
        assertNull(miss.retryAt(old, now = 600_000)) // and the wait ends
    }
}
