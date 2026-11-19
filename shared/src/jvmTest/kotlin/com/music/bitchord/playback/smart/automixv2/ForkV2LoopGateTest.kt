package com.music.bitchord.playback.smart.automixv2

import com.music.bitchord.playback.smart.AutomixStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The style-dependent loop gate both apps read: the extension's "Blend length" never refuses a loop
 * mix, so under Automix v2 a fixed length lets the pair through; BitChord's style keeps refusing it.
 */
class ForkV2LoopGateTest {

    @Test
    fun aFixedLengthRefusesTheLoopUnderBitChordsStyleOnly() {
        for (bars in listOf(8, 16)) {
            assertTrue(ForkV2LoopGate.fixedLengthRefuses(bars, AutomixStyle.BITCHORD))
            assertFalse(ForkV2LoopGate.fixedLengthRefuses(bars, AutomixStyle.V2))
        }
        assertFalse(ForkV2LoopGate.fixedLengthRefuses(0, AutomixStyle.BITCHORD))
        assertFalse(ForkV2LoopGate.fixedLengthRefuses(0, AutomixStyle.V2))
    }

    @Test
    fun theReasonReadsAsBefore() {
        assertEquals("a fixed 16-bar overlap is chosen", ForkV2LoopGate.fixedLengthReason(16))
    }
}
