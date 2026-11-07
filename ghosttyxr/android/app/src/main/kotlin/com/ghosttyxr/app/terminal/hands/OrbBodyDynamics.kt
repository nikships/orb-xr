package com.ghosttyxr.app.terminal.hands

import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One frame of orb "physics": where the body actually is relative to where the palm says it should
 * be, plus the secondary motion that falls out of moving it.
 *
 * @param offset activity-space displacement from the palm target (the orb trails your hand).
 * @param lean extra body rotation, tipping the orb into the direction of travel.
 * @param stretchAxis unit activity-space axis to stretch along; meaningless when [stretch] is 0.
 * @param stretch 0 = perfect sphere. Positive values stretch along [stretchAxis] and squash
 *   perpendicular to it, conserving volume.
 * @param energy 0..1 excitement from hand speed, driving spin momentum and shader flare.
 */
data class OrbBody(
    val offset: Vector3,
    val lean: Quaternion,
    val stretchAxis: Vector3,
    val stretch: Float,
    val energy: Float,
)

/**
 * Spring-and-inertia layer between the tracked palm and the rendered orb.
 *
 * The pet used to be welded to the `PALM` joint: the entity pose was assigned the palm pose every
 * tracked frame, so the orb teleported with the hand and read as UI stuck to a skeleton rather than
 * a thing resting above it. Real objects lag, overshoot a little, deform under acceleration, and
 * keep spinning after you stop. That is what this produces.
 *
 * Integration is semi-implicit Euler on a critically damped spring, substepped so a dropped frame
 * can't blow it up, and the trailing distance is hard-clamped so a tracking jump (hand re-acquired
 * across the room) snaps rather than launching the orb on a long flight.
 *
 * Pure math, no Android — the whole feel is unit-testable on the host JVM.
 */
class OrbBodyDynamics(
    /** Spring frequency. Higher = tighter to the hand, lower = floatier. */
    private val stiffness: Float = DEFAULT_STIFFNESS,
    private val maxLagM: Float = DEFAULT_MAX_LAG_M,
    private val leanDegreesPerMeterPerSecond: Float = DEFAULT_LEAN_DEG_PER_MPS,
    private val maxLeanDegrees: Float = DEFAULT_MAX_LEAN_DEG,
    private val stretchPerMeterPerSecond: Float = DEFAULT_STRETCH_PER_MPS,
    private val maxStretch: Float = DEFAULT_MAX_STRETCH,
    private val fullEnergySpeed: Float = DEFAULT_FULL_ENERGY_SPEED,
) {
    private var position: Vector3? = null
    private var velocity = Vector3.Zero
    private var previousTarget: Vector3? = null
    private var trackedVelocity = Vector3.Zero
    private var energy = 0f

    /** Forget all motion so the next [update] materialises the orb at rest on the palm. */
    fun reset() {
        position = null
        velocity = Vector3.Zero
        previousTarget = null
        trackedVelocity = Vector3.Zero
        energy = 0f
    }

    /**
     * Advance by [deltaSeconds] towards [target] (the palm-derived pose position in activity space).
     */
    fun update(target: Vector3, deltaSeconds: Float): OrbBody {
        if (!target.isFinite()) return currentBody()
        val delta = if (deltaSeconds.isFinite()) deltaSeconds.coerceIn(0f, MAX_DELTA_S) else 0f
        val current = position ?: run {
            position = target
            previousTarget = target
            return currentBody()
        }

        val previous = previousTarget ?: target
        previousTarget = target
        if (delta > 0f) {
            val instantaneous = (target - previous) / delta
            // Asymmetric smoothing: snap up on a flick, coast down afterwards, so the orb keeps
            // looking excited for a beat after the hand stops instead of going inert on the frame.
            val response = if (instantaneous.length > trackedVelocity.length) {
                VELOCITY_ATTACK_S
            } else {
                VELOCITY_DECAY_S
            }
            trackedVelocity += (instantaneous - trackedVelocity) * smoothingFactor(delta, response)
        }

        integrate(current, previous, target, delta)
        clampLag(target)

        val speed = trackedVelocity.length
        val targetEnergy = min(speed / fullEnergySpeed, 1f)
        val energyResponse = if (targetEnergy > energy) ENERGY_ATTACK_S else ENERGY_DECAY_S
        energy += (targetEnergy - energy) * smoothingFactor(delta, energyResponse)

        return currentBody()
    }

    private fun integrate(start: Vector3, from: Vector3, to: Vector3, delta: Float) {
        if (delta <= 0f) return
        val omega = sqrt(stiffness)
        val damping = 2f * omega
        val steps = min(ceil(delta / MAX_SUBSTEP_S).toInt().coerceAtLeast(1), MAX_SUBSTEPS)
        val step = delta / steps
        var pos = start
        var vel = velocity
        repeat(steps) { index ->
            // Sweep the target across the substeps instead of holding last frame's value. The hand
            // moved continuously during the frame, and pretending otherwise makes the settled
            // trailing distance depend on the frame rate.
            val goal = from + (to - from) * ((index + 1).toFloat() / steps)
            val acceleration = (goal - pos) * stiffness - vel * damping
            vel += acceleration * step
            pos += vel * step
        }
        position = pos
        velocity = vel
    }

    /**
     * Hard limit on how far the orb may trail. Tracking loss and re-acquisition can teleport the
     * palm metres away; without this the spring would sling the orb across the room.
     */
    private fun clampLag(target: Vector3) {
        val current = position ?: return
        val offset = current - target
        val distance = offset.length
        if (distance <= maxLagM || distance <= 0f) return
        position = target + offset * (maxLagM / distance)
        // Kill the inbound component too, otherwise the orb pins to the clamp and jitters.
        velocity = Vector3.Zero
    }

    private fun currentBody(): OrbBody {
        val current = position
        val target = previousTarget
        val offset = if (current != null && target != null) current - target else Vector3.Zero
        val speed = trackedVelocity.length
        if (speed < MIN_MOTION_SPEED) {
            return OrbBody(offset, Quaternion.Identity, Vector3.Up, 0f, energy)
        }
        val direction = trackedVelocity / speed
        val stretch = min(speed * stretchPerMeterPerSecond, maxStretch)
        return OrbBody(
            offset = offset,
            lean = leanInto(direction, speed),
            stretchAxis = direction,
            stretch = stretch,
            energy = energy,
        )
    }

    /** Tip the body into travel, the way a soap bubble's highlight swings when you carry it. */
    private fun leanInto(direction: Vector3, speed: Float): Quaternion {
        val axis = direction.cross(Vector3.Up)
        val axisLength = axis.length
        if (axisLength < MIN_AXIS_LENGTH) return Quaternion.Identity
        val degrees = min(speed * leanDegreesPerMeterPerSecond, maxLeanDegrees)
        return Quaternion.fromAxisAngle(axis / axisLength, degrees)
    }

    private companion object {
        const val DEFAULT_STIFFNESS = 150f
        const val DEFAULT_MAX_LAG_M = 0.05f
        const val DEFAULT_LEAN_DEG_PER_MPS = 22f
        const val DEFAULT_MAX_LEAN_DEG = 16f
        const val DEFAULT_STRETCH_PER_MPS = 0.14f
        const val DEFAULT_MAX_STRETCH = 0.2f
        const val DEFAULT_FULL_ENERGY_SPEED = 1.2f

        const val MAX_DELTA_S = 0.1f
        const val MAX_SUBSTEP_S = 1f / 120f
        const val MAX_SUBSTEPS = 16
        const val VELOCITY_ATTACK_S = 0.035f
        const val VELOCITY_DECAY_S = 0.16f
        const val ENERGY_ATTACK_S = 0.05f
        const val ENERGY_DECAY_S = 0.45f
        const val MIN_MOTION_SPEED = 1e-3f
        const val MIN_AXIS_LENGTH = 1e-4f

        fun smoothingFactor(delta: Float, response: Float): Float =
            if (delta <= 0f) 0f else 1f - exp(-delta / response)
    }
}

private const val MAX_COORDINATE = 1e4f

/** Rejects NaN, infinities, and the absurd coordinates a lost-tracking frame can hand us. */
private fun Vector3.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && z.isFinite() &&
        abs(x) < MAX_COORDINATE && abs(y) < MAX_COORDINATE && abs(z) < MAX_COORDINATE
