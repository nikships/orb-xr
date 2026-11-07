package com.ghosttyxr.app.terminal.session

/**
 * Routing decision for terminal-bell chimes drained from libghostty (`\a` / completion
 * chimes via [com.ghosttyxr.app.terminal.GhosttyBridge.nativeDrainBellCount]).
 *
 * A runaway program can emit dozens of bells in one 16ms snapshot tick; playing one chime
 * per drained bell would machine-gun the spatial audio pool. Coalesce each tick's drain
 * into at most [MAX_CHIMES_PER_TICK] chimes — enough to hear a burst as a burst, bounded
 * so a bell loop can't wedge audio. Pure so it can be unit-tested off-device.
 */
object BellChimePolicy {

    /** Max chimes emitted for a single snapshot tick's bell drain. */
    const val MAX_CHIMES_PER_TICK = 3

    /** Number of chimes to emit for [drainedCount] bells drained this tick. */
    fun chimesToEmit(drainedCount: Int): Int = drainedCount.coerceIn(0, MAX_CHIMES_PER_TICK)
}
