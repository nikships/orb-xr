package com.ghosttyxr.app.qr

import com.ghosttyxr.app.terminal.spatial.PaneOffset

/**
 * 3D pose of the Mac's pairing QR code, captured from XR tracking at scan time and used to seed
 * the fresh cockpit's initial primary-pane placement next to the physical Mac display.
 *
 * Holds plain floats (meters, XR world space) rather than XR runtime math types so the
 * pose-to-offset mapping stays reachable from plain-JVM unit tests.
 */
data class QrPlacementSeed(
    val xMeters: Float,
    val yMeters: Float,
    val zMeters: Float,
    val qrWidthMeters: Float,
    val qrHeightMeters: Float,
) {
    /**
     * Offset for the primary pane: the QR sits on the Mac display, so the pane starts one
     * half-QR-width plus [lateralMarginMeters] to the right of it instead of on top of it.
     * Numeric space matches [PaneOffset] (XR world units, same as drag deltas).
     */
    fun primaryPaneOffset(lateralMarginMeters: Float = DEFAULT_LATERAL_MARGIN_METERS): PaneOffset =
        PaneOffset(
            x = xMeters + qrWidthMeters / 2f + lateralMarginMeters,
            y = yMeters,
            z = zMeters,
        )

    companion object {
        /** Lateral nudge past the display edge so the first pane lands beside the Mac, not over it. */
        const val DEFAULT_LATERAL_MARGIN_METERS = 0.35f

        /**
         * Builds a seed from an XR QR pose, or null when the pose/extents are unusable
         * (untracked, non-finite, or degenerate) — callers fall back to grid defaults.
         */
        fun fromPose(
            xMeters: Float,
            yMeters: Float,
            zMeters: Float,
            qrWidthMeters: Float,
            qrHeightMeters: Float,
        ): QrPlacementSeed? {
            if (!xMeters.isFinite() || !yMeters.isFinite() || !zMeters.isFinite()) return null
            if (!qrWidthMeters.isFinite() || !qrHeightMeters.isFinite()) return null
            if (qrWidthMeters <= 0f || qrHeightMeters <= 0f) return null
            return QrPlacementSeed(
                xMeters = xMeters,
                yMeters = yMeters,
                zMeters = zMeters,
                qrWidthMeters = qrWidthMeters,
                qrHeightMeters = qrHeightMeters,
            )
        }
    }
}

/**
 * One-shot holder for the latest QR placement seed. The XR scanner stashes the seed when a
 * pairing QR decodes; DefaultCockpitLifecycle consumes it via [take] on fresh connect (or
 * drops it via [clear] on resume, where the saved snapshot layout wins). Never persists — a
 * scan that doesn't lead to a fresh cockpit leaves nothing behind.
 */
object QrPlacementAnchor {
    @Volatile
    private var seed: QrPlacementSeed? = null

    fun seed(seed: QrPlacementSeed) {
        this.seed = seed
    }

    /** Returns and consumes the pending seed, or null when no scan seeded one. */
    fun take(): QrPlacementSeed? = seed.also { seed = null }

    fun clear() {
        seed = null
    }
}
