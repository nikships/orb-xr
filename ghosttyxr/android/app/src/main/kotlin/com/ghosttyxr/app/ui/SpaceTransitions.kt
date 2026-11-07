package com.ghosttyxr.app.ui

import androidx.xr.compose.platform.SpaceRequestResult

/**
 * Pure interpretation of a Home Space ↔ Full Space transition outcome.
 *
 * Before xr-compose 1.0.0-alpha16 the only way to change space was the fire-and-forget
 * `Scene.requestFullSpace()` / `requestHomeSpace()` pair, which returns `Unit` and reports nothing.
 * The cockpit therefore had to *assume* every request landed — including the one-shot Full Space
 * restore replayed after a session resume, which marked itself consumed whether or not the system
 * honored it. A denied or unsupported request silently dropped the user's saved immersive mode with
 * no retry and no feedback.
 *
 * alpha16 added suspending `ComponentActivity.requestFullSpace()` / `requestHomeSpace()` that await
 * the transition and return a [SpaceRequestResult]. This object turns that result into the two
 * decisions the cockpit actually needs, kept free of Compose and the SDK so both are host-JVM
 * unit-testable.
 *
 * Only [SpaceRequestResult.Success] counts as success. The sealed hierarchy is not exhaustively
 * matchable from outside the library (`Hidden` is not public), and treating any unrecognized future
 * subtype as "did not happen" is the safe default: the cockpit retries rather than silently
 * forgetting the user's preference.
 */
internal object SpaceTransitions {

    /** Whether [result] means the requested space is now actually active. */
    fun succeeded(result: SpaceRequestResult): Boolean = result is SpaceRequestResult.Success

    /**
     * Whether a pending one-shot Full Space restore should be marked consumed.
     *
     * Consumed on success (the restore did its job) and on [SpaceRequestResult.Unsupported] — a
     * device that cannot enter Full Space at all (optical see-through glasses, a phone) will never
     * honor a retry, so holding the flag forever would re-request on every recomposition. A
     * transient [SpaceRequestResult.Error] keeps the flag armed so the next ready frame retries.
     */
    fun shouldConsumeRestore(result: SpaceRequestResult): Boolean =
        succeeded(result) || result is SpaceRequestResult.Unsupported

    /** Whether [result] is worth logging as a failure (success is unremarkable). */
    fun isFailure(result: SpaceRequestResult): Boolean = !succeeded(result)
}
