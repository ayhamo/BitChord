package com.music.bitchord

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fork: keys upstream added to `values/strings.xml` without translating them yet (upstream 141372f6,
 * v1.9.1: Automix overview and the Diwali greeting, since translated or removed; v1.10: Automix artwork transitions, Show Best Part, the Replay year story). [LocaleStringsTest] failed on them in every
 * language, 17 failures on every run, so a new real failure didn't stand out. Translating them here
 * would mean editing upstream's translation files and a conflict at each merge; they are skipped
 * in that test instead, only these keys.
 *
 * [nothing listed is translated yet] fails once upstream has translated one of them everywhere, so
 * the list is cut down rather than left hiding a key that no longer needs it.
 */
object ForkLocaleGaps {
    val upstreamUntranslated = setOf(
        // v1.10 (2026-11-24): Automix artwork transitions, Show Best Part, the Replay year story
        "automix_art_transition",
        "automix_art_transition_subtitle",
        "automix_mini_art_transition",
        "automix_mini_art_transition_subtitle",
        "replay_year_albums",
        "replay_year_albums_word",
        "replay_year_all_day",
        "replay_year_all_day_detail",
        "replay_year_artist_start",
        "replay_year_artists_word",
        "replay_year_building",
        "replay_year_busiest_minutes",
        "replay_year_busiest_song",
        "replay_year_busiest_start",
        "replay_year_clock_start",
        "replay_year_daytime",
        "replay_year_daytime_detail",
        "replay_year_discovery_start",
        "replay_year_early_bird",
        "replay_year_early_bird_detail",
        "replay_year_genres_start",
        "replay_year_golden_hour",
        "replay_year_golden_hour_detail",
        "replay_year_minutes_days_end",
        "replay_year_minutes_days_start",
        "replay_year_minutes_days_value",
        "replay_year_minutes_hours_value",
        "replay_year_minutes_start",
        "replay_year_minutes_together",
        "replay_year_minutes_word",
        "replay_year_months",
        "replay_year_mute",
        "replay_year_night_owl",
        "replay_year_night_owl_detail",
        "replay_year_open",
        "replay_year_opener_detail",
        "replay_year_opener_end",
        "replay_year_opener_start",
        "replay_year_played_times",
        "replay_year_song_start",
        "replay_year_songs_word",
        "replay_year_summary_artists",
        "replay_year_summary_minutes",
        "replay_year_summary_songs",
        "replay_year_summary_title",
        "replay_year_top_artists",
        "replay_year_top_songs",
        "replay_year_unmute",
        "show_best_part",
        "show_best_part_subtitle",
    )
}

class ForkLocaleGapsTest {

    @Test
    fun `nothing listed is translated yet`() {
        val res = listOf(File("app/src/main/res"), File("src/main/res")).first { it.exists() }
        val locales = res.listFiles().orEmpty().filter { it.name.startsWith("values-") && File(it, "strings.xml").exists() }
        val translatedEverywhere = ForkLocaleGaps.upstreamUntranslated.filter { key ->
            locales.isNotEmpty() && locales.all { dir ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml")).getElementsByTagName("string")
                (0 until nodes.length).any { (nodes.item(it) as Element).getAttribute("name") == key }
            }
        }
        assertTrue("translated upstream now, take them out of ForkLocaleGaps: $translatedEverywhere", translatedEverywhere.isEmpty())
    }
}
