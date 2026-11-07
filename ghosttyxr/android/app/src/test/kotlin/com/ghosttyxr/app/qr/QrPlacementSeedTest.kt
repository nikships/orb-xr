package com.ghosttyxr.app.qr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class QrPlacementSeedTest {

    @After
    fun tearDown() {
        QrPlacementAnchor.clear()
    }

    @Test
    fun `valid pose builds a seed`() {
        val seed = QrPlacementSeed.fromPose(
            xMeters = 0.5f,
            yMeters = 1.2f,
            zMeters = -1.0f,
            qrWidthMeters = 0.2f,
            qrHeightMeters = 0.2f,
        )

        assertNotNull(seed)
        assertEquals(0.5f, seed!!.xMeters)
        assertEquals(1.2f, seed.yMeters)
        assertEquals(-1.0f, seed.zMeters)
    }

    @Test
    fun `non-finite pose is rejected`() {
        assertNull(
            QrPlacementSeed.fromPose(Float.NaN, 1.2f, -1.0f, 0.2f, 0.2f),
        )
        assertNull(
            QrPlacementSeed.fromPose(0.5f, Float.POSITIVE_INFINITY, -1.0f, 0.2f, 0.2f),
        )
    }

    @Test
    fun `degenerate extents are rejected`() {
        assertNull(QrPlacementSeed.fromPose(0.5f, 1.2f, -1.0f, 0f, 0.2f))
        assertNull(QrPlacementSeed.fromPose(0.5f, 1.2f, -1.0f, -0.2f, 0.2f))
        assertNull(QrPlacementSeed.fromPose(0.5f, 1.2f, -1.0f, 0.2f, Float.NaN))
    }

    @Test
    fun `primary pane offset sits beside the QR, not on top of it`() {
        val seed = QrPlacementSeed(
            xMeters = 0.5f,
            yMeters = 1.2f,
            zMeters = -1.0f,
            qrWidthMeters = 0.2f,
            qrHeightMeters = 0.2f,
        )

        val offset = seed.primaryPaneOffset()

        // Half the QR width (0.1) plus the default 0.35 lateral margin.
        assertEquals(0.5f + 0.1f + 0.35f, offset.x, 1e-6f)
        assertEquals(1.2f, offset.y, 1e-6f)
        assertEquals(-1.0f, offset.z, 1e-6f)
    }

    @Test
    fun `anchor take consumes the seed`() {
        val seed = QrPlacementSeed(0.5f, 1.2f, -1.0f, 0.2f, 0.2f)
        QrPlacementAnchor.seed(seed)

        assertSame(seed, QrPlacementAnchor.take())
        assertNull(QrPlacementAnchor.take())
    }

    @Test
    fun `anchor clear drops a pending seed`() {
        QrPlacementAnchor.seed(QrPlacementSeed(0.5f, 1.2f, -1.0f, 0.2f, 0.2f))

        QrPlacementAnchor.clear()

        assertNull(QrPlacementAnchor.take())
    }
}
