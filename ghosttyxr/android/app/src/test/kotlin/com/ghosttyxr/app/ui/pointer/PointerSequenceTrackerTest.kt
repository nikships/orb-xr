package com.ghosttyxr.app.ui.pointer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerSequenceTrackerTest {
    @Test
    fun contiguousSequenceReportsNoGap() {
        val tracker = PointerSequenceTracker()
        tracker.reset(baseline = 1)

        assertFalse(tracker.accept(2))
        assertFalse(tracker.accept(3))
        assertFalse(tracker.accept(4))
    }

    @Test
    fun missingEventReportsGap() {
        val tracker = PointerSequenceTracker()
        tracker.reset(baseline = 1)

        assertFalse(tracker.accept(2))
        // 3 was lost (e.g. a button-up dropped by the relay buffer).
        assertTrue(tracker.accept(4))
        // Stream resynchronizes after the gap is reported.
        assertFalse(tracker.accept(5))
    }

    @Test
    fun resetStartsANewSession() {
        val tracker = PointerSequenceTracker()
        tracker.reset(baseline = 1)
        assertFalse(tracker.accept(2))

        // New handoff restarts numbering; no false gap across sessions.
        tracker.reset(baseline = 1)
        assertFalse(tracker.accept(2))
    }

    @Test
    fun zeroSequenceDisablesTracking() {
        val tracker = PointerSequenceTracker()
        tracker.reset()

        // Legacy peers stamp no sequence; nothing should ever report a gap.
        assertFalse(tracker.accept(0))
        assertFalse(tracker.accept(0))
    }

    @Test
    fun firstEventAfterColdResetSetsBaselineWithoutGap() {
        val tracker = PointerSequenceTracker()
        tracker.reset()

        assertFalse(tracker.accept(7))
        assertFalse(tracker.accept(8))
        assertTrue(tracker.accept(10))
    }
}
