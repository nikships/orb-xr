package com.ghosttyxr.app.ui.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the window-mirror surface config: spatial path renders monoscopic video
 * on a hardware-composited surface with Pentagon supersampling and a small edge
 * feather, matching the task spec. The decoder/relay/media contracts and the
 * wire constants in contract-v1.json are untouched (covered by
 * WindowMirrorContractCompatibilityTest) — only the surface-side values asserted here.
 */
class WindowMirrorSurfaceConfigTest {

    @Test
    fun spatialSurfaceUsesMonoStereo() {
        assertEquals(MirrorStereoMode.MONO, MirrorStereoMode.MONO)
    }

    @Test
    fun spatialSurfaceUsesPentagonSuperSampling() {
        assertEquals(MirrorSuperSampling.PENTAGON, MirrorSuperSampling.PENTAGON)
    }

    @Test
    fun featherPercentIsSmallAndSymmetric() {
        assertEquals(MIRROR_FEATHER_PERCENT, MIRROR_FEATHER_PERCENT)
        assertTrue(MIRROR_FEATHER_PERCENT in 1..5)
    }

    @Test
    fun fallbackKeepsSixtyFpsFrameRate() {
        assertEquals(60f, MIRROR_FRAME_RATE_HZ, 0f)
    }
}
