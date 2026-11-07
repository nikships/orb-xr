package com.ghosttyxr.app.terminal.input

import kotlin.math.abs
import kotlin.math.sign

/** Converts a held controller thumbstick into bounded, frame-rate-independent scroll lines. */
class AnalogScrollAccumulator(
    private val deadZone: Float = 0.2f,
    private val linesPerSecond: Float = 20f,
) {
    private var lastEventTimeMs: Long? = null
    private var pendingLines = 0f
    private var lastDirection = 0f

    fun isActive(axis: Float): Boolean = axis.isFinite() && abs(axis) > deadZone

    fun consume(axis: Float, eventTimeMs: Long): Int {
        if (!isActive(axis)) {
            reset()
            return 0
        }

        val direction = sign(axis)
        if (direction != lastDirection) {
            pendingLines = 0f
            lastEventTimeMs = null
            lastDirection = direction
        }

        val previousTime = lastEventTimeMs
        lastEventTimeMs = eventTimeMs
        if (previousTime == null) return direction.toInt()

        val elapsedMs = (eventTimeMs - previousTime).coerceIn(0L, MAX_EVENT_GAP_MS)
        val magnitude = ((abs(axis) - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        pendingLines += direction * magnitude * linesPerSecond * elapsedMs / 1_000f
        val lines = pendingLines.toInt()
        pendingLines -= lines
        return lines
    }

    private fun reset() {
        lastEventTimeMs = null
        pendingLines = 0f
        lastDirection = 0f
    }

    private companion object {
        const val MAX_EVENT_GAP_MS = 100L
    }
}

/** Select whichever conventional Android vertical joystick axis is actually moving. */
internal fun dominantVerticalControllerAxis(y: Float, ry: Float, rz: Float): Float =
    listOf(y, ry, rz)
        .filter(Float::isFinite)
        .maxByOrNull { abs(it) }
        ?: 0f
