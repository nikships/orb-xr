package com.ghosttyxr.app.terminal.ssh

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [ChannelGenerationGuard]: the claim/release/
 * invalidate contract that lets [com.ghosttyxr.app.terminal.hostkeyboard.HostKeyboardRelayController]
 * and [com.ghosttyxr.app.terminal.windowmirror.WindowMirrorRelayController]
 * hand off channel ownership between their reconnect loop and a
 * stop/restart caller without leaking or double-closing the channel, plus
 * the [ChannelGenerationGuard.attachSecondary] extension
 * [com.ghosttyxr.app.terminal.windowmirror.WindowMirrorRelayController] uses
 * to keep its media socket from ever being left unowned by a stop/restart
 * that lands between claiming the tunnel and building the session.
 */
class ChannelGenerationGuardTest {

    private class CountingCloseable : AutoCloseable {
        var closeCalls = 0
            private set
        override fun close() {
            closeCalls++
        }
    }

    @Test
    fun `claim succeeds for the current generation`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val gen = guard.newGeneration()
        assertTrue(guard.claim(gen, CountingCloseable()))
    }

    @Test
    fun `claim fails for a generation superseded by a newer one`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val staleGen = guard.newGeneration()
        guard.newGeneration()
        assertFalse(guard.claim(staleGen, CountingCloseable()))
    }

    @Test
    fun `claim fails for a generation invalidated while the connect was in flight`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val gen = guard.newGeneration()
        guard.invalidate()
        assertFalse(guard.claim(gen, CountingCloseable()))
    }

    @Test
    fun `release returns the held channel for the current generation and clears it`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val channel = CountingCloseable()
        val gen = guard.newGeneration()
        guard.claim(gen, channel)

        assertSame(channel, guard.release(gen))
        assertNull("a second release of the same generation must be a no-op", guard.release(gen))
    }

    @Test
    fun `release returns null when the generation was superseded before release ran`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val channel = CountingCloseable()
        val gen = guard.newGeneration()
        guard.claim(gen, channel)
        guard.invalidate() // steals the channel first

        assertNull(guard.release(gen))
    }

    @Test
    fun `invalidate returns and clears whatever is currently held`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val channel = CountingCloseable()
        val gen = guard.newGeneration()
        guard.claim(gen, channel)

        assertSame(channel, guard.invalidate())
        assertNull("nothing left to hand back the second time", guard.invalidate())
    }

    @Test
    fun `invalidate returns null when nothing was ever claimed`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        assertNull(guard.invalidate())
    }

    @Test
    fun `unclaimed channel from a stale generation is never handed back by release or invalidate`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val gen = guard.newGeneration()
        guard.invalidate() // supersede before the caller even attempts claim
        val discarded = CountingCloseable()

        assertFalse(guard.claim(gen, discarded))
        assertNull(guard.release(gen))
        // The caller is responsible for closing `discarded` itself on a
        // failed claim — the guard never takes ownership of it.
        assertEquals(0, discarded.closeCalls)
    }

    @Test
    fun `stress claim, release and invalidate across two threads without losing or double-closing a channel`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val iterations = 2_000
        val closeCounts = ConcurrentHashMap<CountingCloseable, AtomicInteger>()

        val producer = thread {
            repeat(iterations) {
                val gen = guard.newGeneration()
                val channel = CountingCloseable()
                closeCounts[channel] = AtomicInteger(0)
                if (guard.claim(gen, channel)) {
                    val released = guard.release(gen)
                    released?.let { closeCounts.getValue(it).incrementAndGet() }
                } else {
                    closeCounts.getValue(channel).incrementAndGet()
                }
            }
        }
        val invalidator = thread {
            repeat(iterations) {
                guard.invalidate()?.let { closeCounts.getValue(it).incrementAndGet() }
            }
        }

        producer.join(10_000)
        invalidator.join(10_000)
        // Drain whatever the last producer iteration claimed but neither
        // side had released/invalidated by the time both threads finished.
        guard.invalidate()?.let { closeCounts.getValue(it).incrementAndGet() }

        assertEquals(iterations, closeCounts.size)
        closeCounts.forEach { (_, count) ->
            assertEquals("every channel must be closed exactly once, never zero or twice", 1, count.get())
        }
    }

    @Test
    fun `attachSecondary succeeds and runs publish when the generation is still current`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val gen = guard.newGeneration()
        guard.claim(gen, CountingCloseable())
        val secondary = CountingCloseable()
        var published = false

        val attached = guard.attachSecondary(gen, secondary) { published = true }

        assertTrue(attached)
        assertTrue("publish must run when attachSecondary succeeds", published)
        assertEquals(0, secondary.closeCalls)
    }

    @Test
    fun `attachSecondary fails and skips publish when invalidate landed after claim`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val gen = guard.newGeneration()
        guard.claim(gen, CountingCloseable())
        guard.invalidate() // stop/restart beats the loop to attaching the secondary

        val secondary = CountingCloseable()
        var published = false
        val attached = guard.attachSecondary(gen, secondary) { published = true }

        assertFalse(attached)
        assertFalse("publish must not run when attachSecondary fails", published)
        // The guard never took ownership — the caller is responsible for
        // closing `secondary` itself on a failed attach.
        assertEquals(0, secondary.closeCalls)
    }

    @Test
    fun `attachSecondary fails when the channel was never claimed for this generation`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val gen = guard.newGeneration()
        // No claim() call at all for `gen`.
        assertFalse(guard.attachSecondary(gen, CountingCloseable()) { })
    }

    @Test
    fun `releaseBoth hands back both the channel and the attached secondary and clears them`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val channel = CountingCloseable()
        val secondary = CountingCloseable()
        val gen = guard.newGeneration()
        guard.claim(gen, channel)
        guard.attachSecondary(gen, secondary) { }

        val (releasedChannel, releasedSecondary) = guard.releaseBoth(gen)
        assertSame(channel, releasedChannel)
        assertSame(secondary, releasedSecondary)

        val second = guard.releaseBoth(gen)
        assertNull("a second releaseBoth of the same generation must be a no-op", second.first)
        assertNull(second.second)
    }

    @Test
    fun `invalidateBoth tears down a secondary attached before it ran`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val channel = CountingCloseable()
        val secondary = CountingCloseable()
        val gen = guard.newGeneration()
        guard.claim(gen, channel)
        guard.attachSecondary(gen, secondary) { }

        val (invalidatedChannel, invalidatedSecondary) = guard.invalidateBoth()
        assertSame(channel, invalidatedChannel)
        assertSame(secondary, invalidatedSecondary)
    }

    @Test
    fun `invalidateBoth returns a null secondary when none was ever attached`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val gen = guard.newGeneration()
        guard.claim(gen, CountingCloseable())

        val (channel, secondary) = guard.invalidateBoth()
        assertTrue(channel is CountingCloseable)
        assertNull(secondary)
    }

    @Test
    fun `race - invalidate landing between claim and attachSecondary leaves the secondary owned by the caller, never leaked or silently torn down twice`() {
        // Reproduces the WindowMirrorRelayController timing gap: the tunnel
        // is claimed, then (before the media socket built on top of it is
        // attached/published) a stop/restart invalidates. attachSecondary
        // must see the superseded generation and hand the secondary back to
        // whoever built it, rather than silently discarding or leaking it.
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val tunnel = CountingCloseable()
        val gen = guard.newGeneration()
        assertTrue(guard.claim(gen, tunnel))

        // Stop/restart wins the race before the session is ever attached.
        val (invalidatedTunnel, invalidatedSecondaryBeforeAttach) = guard.invalidateBoth()
        assertSame(tunnel, invalidatedTunnel)
        assertNull("nothing was attached yet", invalidatedSecondaryBeforeAttach)

        // The loop thread, unaware the tunnel is already gone, finishes
        // building its session and now tries to attach it.
        val session = CountingCloseable()
        var published = false
        val attached = guard.attachSecondary(gen, session) { published = true }

        assertFalse("a superseded generation must never accept a late attach", attached)
        assertFalse(published)
        // The guard took no ownership of `session` — closing it is the
        // caller's job (WindowMirrorRelayController.connectAndPump does
        // this immediately on a failed attach), so it must still be open
        // here, and it must never be closed a second time by the guard.
        assertEquals(0, session.closeCalls)
    }

    @Test
    fun `race - invalidate landing after attachSecondary tears the secondary down exactly once alongside the channel`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val tunnel = CountingCloseable()
        val gen = guard.newGeneration()
        guard.claim(gen, tunnel)

        val session = CountingCloseable()
        var published = false
        assertTrue(guard.attachSecondary(gen, session) { published = true })
        assertTrue(published)

        // Stop/restart lands after the session was already published.
        val (invalidatedTunnel, invalidatedSecondary) = guard.invalidateBoth()
        assertSame(tunnel, invalidatedTunnel)
        assertSame("the published session must come back for the caller to close", session, invalidatedSecondary)

        // A second invalidate (e.g. a stray call) must not resurface it.
        val (secondTunnel, secondSecondary) = guard.invalidateBoth()
        assertNull(secondTunnel)
        assertNull(secondSecondary)
    }

    @Test
    fun `stress attachSecondary racing invalidate across two threads never leaks or double-hands-back a secondary`() {
        val guard = ChannelGenerationGuard<CountingCloseable>()
        val iterations = 2_000
        // Keyed by `AutoCloseable` (not `CountingCloseable`) because the
        // guard hands secondaries back as `AutoCloseable?`.
        val handBackCounts = ConcurrentHashMap<AutoCloseable, AtomicInteger>()

        val producer = thread {
            repeat(iterations) {
                val gen = guard.newGeneration()
                val tunnel = CountingCloseable()
                val secondary = CountingCloseable()
                handBackCounts[secondary] = AtomicInteger(0)
                if (guard.claim(gen, tunnel)) {
                    if (guard.attachSecondary(gen, secondary) { }) {
                        val (_, released) = guard.releaseBoth(gen)
                        released?.let { handBackCounts.getValue(it).incrementAndGet() }
                    } else {
                        // Superseded between claim and attach — the caller
                        // owns `secondary` outright.
                        handBackCounts.getValue(secondary).incrementAndGet()
                    }
                } else {
                    // Superseded before claim — `secondary` was never
                    // built into anything the guard could have seen; count
                    // it as owned by the caller too, for symmetry with the
                    // real controller (which never builds the session at
                    // all in this case).
                    handBackCounts.getValue(secondary).incrementAndGet()
                }
            }
        }
        val invalidator = thread {
            repeat(iterations) {
                val (_, secondary) = guard.invalidateBoth()
                secondary?.let { handBackCounts.getValue(it).incrementAndGet() }
            }
        }

        producer.join(10_000)
        invalidator.join(10_000)
        val (_, trailingSecondary) = guard.invalidateBoth()
        trailingSecondary?.let { handBackCounts.getValue(it).incrementAndGet() }

        assertEquals(iterations, handBackCounts.size)
        handBackCounts.forEach { (_, count) ->
            assertEquals(
                "every secondary must be handed back to exactly one owner, never zero or twice",
                1,
                count.get(),
            )
        }
    }
}
