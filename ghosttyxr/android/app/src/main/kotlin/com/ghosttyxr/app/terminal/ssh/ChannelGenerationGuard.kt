package com.ghosttyxr.app.terminal.ssh

/**
 * Serializes ownership handoff of a single long-lived [AutoCloseable] (an
 * [EstablishedShell]/[EstablishedTunnel]) — and, optionally, one secondary
 * resource built on top of it — between a background reconnect loop and a
 * caller-thread stop/restart racing it.
 *
 * [VerifiedHostAccess.connectShell]/[VerifiedHostAccess.connectTunnel] are
 * blocking calls that don't observe coroutine cancellation until they
 * return, so a stop/restart that lands while one is in flight can otherwise
 * end up with nobody agreeing on which channel is "current" — leaking the
 * one nobody closes, or letting a stale reconnect loop keep pumping a
 * channel a newer one doesn't know about. Every consumer follows this
 * protocol:
 *
 *  1. [newGeneration] before launching the loop for a fresh connect cycle —
 *     capture the returned token and thread it through the loop.
 *  2. [claim] immediately after a blocking connect call returns, passing the
 *     same token. If it returns `false`, a stop/restart already invalidated
 *     this attempt — close the channel immediately instead of using it.
 *  2a. [attachSecondary] — only needed when the loop builds a second
 *      resource on top of the just-claimed channel (e.g.
 *      [com.ghosttyxr.app.terminal.windowmirror.WindowMirrorRelayController]'s
 *      media socket on top of its tunnel) before publishing it anywhere
 *      else. See its doc for why this step exists at all.
 *  3. [release]/[releaseBoth] when the loop is done with the channel for any
 *     other reason (pump exited, error, retry) — closes nothing itself,
 *     just hands back the channel/secondary (or null if already taken) for
 *     the caller to close.
 *  4. [invalidate]/[invalidateBoth] from stop/restart — atomically bumps the
 *     generation (so any in-flight [claim]/[attachSecondary] for the old
 *     generation fails) and hands back whatever channel/secondary is
 *     currently held, for the caller to close.
 *
 * Every method serializes through one lock, so a caller-thread [invalidate]
 * can never run concurrently with the loop thread's [claim]/[attachSecondary]/
 * [release] — exactly one side ends up owning (and closing) any given
 * channel or secondary resource; nobody double-closes or leaks it.
 *
 * [com.ghosttyxr.app.terminal.session.TerminalSessionEngine] needs none of
 * this: its shell reconnect runs entirely on its own single-thread
 * dispatcher, so there's no second thread that could race a stop/restart
 * against an in-flight connect in the first place — the dispatcher itself
 * is the serialization. This class exists only for consumers whose
 * reconnect loop and stop/restart caller run on different threads.
 */
internal class ChannelGenerationGuard<T : AutoCloseable> {
    private val lock = Any()
    private var generation = 0L
    private var heldGeneration = 0L
    private var held: T? = null
    private var heldSecondary: AutoCloseable? = null

    /** Start a fresh connect cycle: bump and return the new generation token. */
    fun newGeneration(): Long = synchronized(lock) { ++generation }

    /**
     * Take ownership of [channel] for [myGeneration]. Returns `false` (and
     * claims nothing) if [myGeneration] is no longer current — an
     * [invalidate] landed while the caller's connect call was in flight.
     */
    fun claim(myGeneration: Long, channel: T): Boolean = synchronized(lock) {
        if (generation != myGeneration) return@synchronized false
        held = channel
        heldGeneration = myGeneration
        true
    }

    /**
     * Attach [secondary] — a resource built on top of the channel already
     * claimed for [myGeneration] — so a later [releaseBoth]/[invalidateBoth]
     * tears it down together with the channel. If [myGeneration] is still
     * current, [publish] runs before the lock is released (e.g. to assign a
     * field the rest of the class reads); returns `true`. Otherwise
     * ([myGeneration] was already claimed by nobody or invalidated), returns
     * `false` without running [publish] or storing [secondary] — the caller
     * must close it directly; nobody else will ever see it.
     *
     * Running [publish] under the same lock [invalidate]/[invalidateBoth]
     * synchronize on closes exactly the race this method exists for: a
     * caller-thread invalidate can never observe [publish] having run
     * without [secondary] already being registered for teardown, and can
     * never tear down [secondary] before [publish] makes it visible.
     */
    fun attachSecondary(myGeneration: Long, secondary: AutoCloseable, publish: () -> Unit): Boolean =
        synchronized(lock) {
            if (held == null || heldGeneration != myGeneration) return@synchronized false
            heldSecondary = secondary
            publish()
            true
        }

    /**
     * Release the channel held for [myGeneration], if it's still the one on
     * file. Returns null (a no-op for the caller) if [myGeneration] was
     * already superseded — [invalidate] already took it and closed it.
     */
    fun release(myGeneration: Long): T? = releaseBoth(myGeneration).first

    /** Like [release], but also hands back any [attachSecondary] resource. */
    fun releaseBoth(myGeneration: Long): Pair<T?, AutoCloseable?> = synchronized(lock) {
        if (held == null || heldGeneration != myGeneration) return@synchronized null to null
        val secondary = heldSecondary
        heldSecondary = null
        (held.also { held = null }) to secondary
    }

    /**
     * Stop/restart entry point: bump the generation (invalidating any
     * in-flight [claim]/[attachSecondary]) and hand back whatever channel is
     * currently held so the caller can close it. Returns null if nothing
     * was held.
     */
    fun invalidate(): T? = invalidateBoth().first

    /** Like [invalidate], but also hands back any [attachSecondary] resource. */
    fun invalidateBoth(): Pair<T?, AutoCloseable?> = synchronized(lock) {
        generation++
        val secondary = heldSecondary
        heldSecondary = null
        (held.also { held = null }) to secondary
    }
}
