package com.music.bitchord.data.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.w3c.dom.Element

/** Fork: the lyrics rulebook's logic beyond its shared cases (lyrics.md §2, §5.1, §6, §9, §11). */
class ForkLyricsRulesTest {

    private fun line(at: Long, text: String = "x", until: Long? = null) = LyricLine(at, text, sungUntilMs = until)

    @Test
    fun aLineLightsUp660MsAhead() {
        val lines = listOf(line(1_000), line(5_000), line(9_000))
        assertEquals(listOf(0), ForkLyricsRules.activeRows(lines, 4_339))
        assertEquals(listOf(1), ForkLyricsRules.activeRows(lines, 4_340))
    }

    @Test
    fun aLineStartingUnderAnotherJoinsItOnTheShorterLead() {
        val lines = listOf(line(1_000, until = 6_000), line(3_000, until = 8_000))
        // max(250, 660 - 250) = 410 ms for line-synced lyrics.
        assertEquals(listOf(0), ForkLyricsRules.activeRows(lines, 2_589))
        assertEquals(listOf(0, 1), ForkLyricsRules.activeRows(lines, 2_590))
    }

    @Test
    fun aGapIsActive190MsAheadUntilItsEnd() {
        val lines = listOf(LyricLine(0, "", sungUntilMs = 8_200), line(9_000))
        assertEquals(listOf(0), ForkLyricsRules.activeRows(lines, 8_009))
        assertEquals(listOf(1), ForkLyricsRules.activeRows(lines, 8_340))
    }

    @Test
    fun withNothingActiveTheLastStartedLineStays() {
        val lines = listOf(line(1_000, until = 2_000), line(5_000))
        assertEquals(listOf(0), ForkLyricsRules.activeRows(lines, 3_000))
    }

    @Test
    fun specGapsReplaceAnLrcMarker() {
        val lines = listOf(line(1_000, "a"), LyricLine(3_000, ""), line(12_000, "b"))
        val out = ForkLyricsRules.withSpecGaps(lines)
        assertEquals(listOf("a", "", "b"), out.map { it.text })
        assertEquals(3_310L, out[1].timeMs)
        assertEquals(11_200L, out[1].sungUntilMs)
    }

    @Test
    fun anIntroOf7SecondsIsAGap() {
        val out = ForkLyricsRules.withSpecGaps(listOf(line(9_000, "x")))
        assertEquals(listOf(0L to 8_200L, 9_000L to null), out.map { it.timeMs to it.sungUntilMs })
        // A short intro has none, and a plain sheet is left as it is.
        assertEquals(1, ForkLyricsRules.withSpecGaps(listOf(line(6_999, "x"))).size)
        val plain = listOf(line(0, "a"), line(0, "b"))
        assertEquals(plain, ForkLyricsRules.withSpecGaps(plain))
    }

    @Test
    fun aParagraphInheritsTheDivsSinger() {
        val xml = """<tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div ttm:agent="v2"><p begin="1s">x</p></div></body></tt>"""
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val p = doc.getElementsByTagName("p").item(0) as Element
        assertEquals("v2", ForkLyricsRules.inheritedAgent(p))
        val bare = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse("<tt><body><div><p>x</p></div></body></tt>".byteInputStream())
        assertNull(ForkLyricsRules.inheritedAgent(bare.getElementsByTagName("p").item(0) as Element))
    }

    @Test
    fun romanLanguagesComeFromTheScript() {
        assertEquals(ForkLyricsRules.RomanLanguage.JAPANESE, ForkLyricsRules.romanLanguage("こんにちは"))
        assertEquals(ForkLyricsRules.RomanLanguage.KOREAN, ForkLyricsRules.romanLanguage("안녕"))
        assertEquals(ForkLyricsRules.RomanLanguage.CHINESE, ForkLyricsRules.romanLanguage("你好"))
        assertEquals(ForkLyricsRules.RomanLanguage.JAPANESE, ForkLyricsRules.romanLanguage("漢字", "漢字\nです"))
        assertEquals(ForkLyricsRules.RomanLanguage.RUSSIAN, ForkLyricsRules.romanLanguage("привет"))
        assertEquals(ForkLyricsRules.RomanLanguage.UKRAINIAN, ForkLyricsRules.romanLanguage("привіт"))
        assertEquals(ForkLyricsRules.RomanLanguage.ARABIC, ForkLyricsRules.romanLanguage("مرحبا"))
        assertNull(ForkLyricsRules.romanLanguage("hello"))
        assertNull(ForkLyricsRules.romanLanguage("Γεια"))
    }

    @Test
    fun aSwitchedOffLanguageKeepsItsOriginal() {
        val originals = listOf(line(0, "안녕"), line(1_000, "привет"), line(2_000, "Γεια"))
        val romanized = listOf(line(0, "annyeong"), line(1_000, "privet"), line(2_000, "geia"))
        val enabled = ForkLyricsRules.RomanLanguage.entries.toSet() - ForkLyricsRules.RomanLanguage.KOREAN
        assertEquals(listOf("안녕", "privet", "geia"), ForkLyricsRules.romanizedFor(originals, romanized, enabled).map { it.text })
        val all = ForkLyricsRules.RomanLanguage.entries.toSet()
        assertEquals(romanized, ForkLyricsRules.romanizedFor(originals, romanized, all))
    }

    @Test
    fun captionTracksAreTriedOwnLanguageFirstAsrLast() {
        val player = Json.parseToJsonElement(
            """{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
              {"baseUrl":"u1","languageCode":"en","kind":"asr"},
              {"baseUrl":"u2","languageCode":"de"},
              {"baseUrl":"u3","languageCode":"en"}]}}}""",
        ) as JsonObject
        assertEquals(listOf("u3", "u2", "u1"), ForkYouTubeCaptions.trackUrls(player, "en"))
        assertEquals(emptyList<String>(),ForkYouTubeCaptions.trackUrls(JsonObject(emptyMap()), "en"))
    }

    @Test
    fun json3IsAskedForAndParsed() {
        val www = "https://www.youtube.com/api/timedtext?v=1&fmt=json3"
        assertEquals(www, ForkYouTubeCaptions.json3Url("https://www.youtube.com/api/timedtext?v=1&fmt=srv3"))
        assertEquals(www, ForkYouTubeCaptions.json3Url("https://music.youtube.com/api/timedtext?v=1"))
        assertEquals(www, ForkYouTubeCaptions.json3Url("/api/timedtext?v=1"))
        val lines = ForkYouTubeCaptions.parseJson3(
            """{"events":[{"tStartMs":1000,"dDurationMs":2000,"segs":[{"utf8":"♪ hello ♪ there\n♫"}]},{"tStartMs":500,"segs":[{"utf8":"\n"}]}]}""",
        )
        assertEquals(listOf(LyricLine(1_000, "hello there", sungUntilMs = 3_000)), lines)
        assertNull(ForkYouTubeCaptions.parseJson3("{}"))
    }

    // ---- spec 0836435 ----

    @Test
    fun drawnTwoRefreshIntervalsAheadWhilePlaying() {
        assertEquals(33L, ForkLyricsRules.displayLeadMs(1000.0 / 60.0, 1.0, playing = true))
        assertEquals(17L, ForkLyricsRules.displayLeadMs(1000.0 / 120.0, 1.0, playing = true))
        assertEquals(8L, ForkLyricsRules.displayLeadMs(1000.0 / 240.0, 1.0, playing = true))
        assertEquals(40L, ForkLyricsRules.displayLeadMs(1000.0 / 24.0, 1.0, playing = true))
        assertEquals(50L, ForkLyricsRules.displayLeadMs(1000.0 / 60.0, 1.5, playing = true))
        assertEquals(0L, ForkLyricsRules.displayLeadMs(1000.0 / 60.0, 1.0, playing = false))
        assertEquals(33L, ForkLyricsRules.displayLeadMs(Double.NaN, 1.0, playing = true))
    }

    @Test
    fun aLineLightsWithItsFirstWordWhenThatIsBeforeItsTag() {
        val words = listOf(LyricWord(4_000, 4_500, "a"), LyricWord(4_500, 5_000, "b"))
        val lines = listOf(line(1_000, until = 3_000), LyricLine(5_000, "a b", words = words))
        assertEquals(4_000L, ForkLyricsRules.timelineStart(lines[1]))
        assertEquals(listOf(1), ForkLyricsRules.activeRows(lines, 3_340))
    }

    @Test
    fun aLineDueByItsFirstWordIsFoundAndEveryRuleReadsTheSameStart() {
        // A negative word is not a timed word: the line starts at its stamp, not before the song.
        assertEquals(1_000L, ForkLyricsRules.timelineStart(LyricLine(1_000, "a b", words = listOf(LyricWord(-500, 100, "a"), LyricWord(1_000, 1_400, "b")))))
        // The second line's first word (9.5 s) comes before the first line's stamp (10 s): it is
        // due at 9.0 s + 660 ms, and the walk no longer stops at the first line.
        val early = listOf(LyricLine(10_000, "x"), LyricLine(10_200, "a b", words = listOf(LyricWord(9_500, 9_900, "a"), LyricWord(9_900, 10_400, "b"))))
        assertEquals(listOf(1), ForkLyricsRules.activeRows(early, 9_000))
        // A line ending a hair after the next line's first word is cut there, as activeRows hands over there.
        val trim = listOf(line(1_000, until = 4_100), LyricLine(5_000, "a b", words = listOf(LyricWord(4_000, 4_500, "a"), LyricWord(4_500, 5_000, "b"))))
        assertEquals(4_000L, ForkLyricsRules.lineEnd(trim, 0))
    }
}
