package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The three copies the owner's PC was offered for 'Talking Body' on 2026-11-19, none stating a bitrate. */
class ForkLosslessRankTest {
    private val nullBeat = DesktopStreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16)
    private val nebula = DesktopStreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 24)
    private val tidotido = DesktopStreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24)

    @Test fun deeperBeatsShallower() = assertTrue(ForkLosslessRank.prefersCandidate(nebula, nullBeat))

    @Test fun fasterRateBreaksADepthTie() = assertTrue(ForkLosslessRank.prefersCandidate(tidotido, nebula))

    @Test fun aWorseCopyDoesNotReplaceTheHeldOne() = assertFalse(ForkLosslessRank.prefersCandidate(nullBeat, tidotido))

    @Test fun anIdenticalCopyKeepsTheHeldOne() = assertFalse(ForkLosslessRank.prefersCandidate(nebula, nebula))

    @Test fun bitrateOnlyDecidesWhenTheFilesAgree() =
        assertTrue(ForkLosslessRank.prefersCandidate(nebula.copy(kbps = 2_000), nebula.copy(kbps = 1_400)))
}
