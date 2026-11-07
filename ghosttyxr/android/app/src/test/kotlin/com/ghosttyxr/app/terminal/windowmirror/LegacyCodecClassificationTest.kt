package com.ghosttyxr.app.terminal.windowmirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCodecClassificationTest {
    @Test
    fun acceptsEstablishedHardwareCodecNames() {
        assertTrue(isLegacyHardwareCodec("OMX.qcom.video.decoder.hevc"))
        assertTrue(isLegacyHardwareCodec("c2.qti.hevc.decoder"))
        assertTrue(isLegacyHardwareCodec("arc.video"))
    }

    @Test
    fun rejectsKnownOrUnclassifiedSoftwareCodecNames() {
        assertFalse(isLegacyHardwareCodec("OMX.google.hevc.decoder"))
        assertFalse(isLegacyHardwareCodec("OMX.SEC.hevc.sw.dec"))
        assertFalse(isLegacyHardwareCodec("OMX.qcom.video.decoder.hevcswvdec"))
        assertFalse(isLegacyHardwareCodec("c2.android.hevc.decoder"))
        assertFalse(isLegacyHardwareCodec("vendor.software.hevc.decoder"))
        assertFalse(isLegacyHardwareCodec("arc.audio"))
    }
}
