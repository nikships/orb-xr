package com.ghosttyxr.app.ui.pointer

/**
 * Detects gaps in the Symbiosis pointer event stream.
 *
 * The Mac stamps every pointer-session event with a monotonically increasing
 * sequence number (the handoff is 1). Events can be lost to network failures or
 * to buffer overflow in the relay's bounded event flow; when that happens the
 * lost event may have been a button transition, which would otherwise strand an
 * in-flight press as a phantom drag. Callers use [accept]'s gap signal to cancel
 * any held press and resynchronize.
 *
 * Pure Kotlin so gap semantics are covered by host-JVM unit tests.
 */
class PointerSequenceTracker {
    private var lastSequence = 0L

    /**
     * Record [seq] and report whether it reveals a gap since the previous event.
     * A [seq] of 0 or below means the sender does not stamp sequences (legacy
     * peer); tracking is disabled and no gap is ever reported.
     */
    fun accept(seq: Long): Boolean {
        if (seq <= 0L) return false
        val gap = lastSequence != 0L && seq != lastSequence + 1L
        lastSequence = seq
        return gap
    }

    /** Start a new pointer session at [baseline] (the handoff's own seq). */
    fun reset(baseline: Long = 0L) {
        lastSequence = baseline
    }
}
