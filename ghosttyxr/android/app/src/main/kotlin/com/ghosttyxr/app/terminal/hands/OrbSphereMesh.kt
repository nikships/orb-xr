package com.ghosttyxr.app.terminal.hands

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class OrbSphereMesh(
    val vertices: FloatArray,
    val indices: ShortArray,
) {
    val vertexCount: Int
        get() = vertices.size / FLOATS_PER_VERTEX

    companion object {
        const val FLOATS_PER_VERTEX = 6

        fun create(latitudeSegments: Int, longitudeSegments: Int): OrbSphereMesh {
            require(latitudeSegments >= 2)
            require(longitudeSegments >= 3)

            val vertexCount = (latitudeSegments + 1) * (longitudeSegments + 1)
            require(vertexCount <= MAX_UNSIGNED_SHORT_VERTICES)

            val vertices = FloatArray(vertexCount * FLOATS_PER_VERTEX)
            var offset = 0
            for (latitude in 0..latitudeSegments) {
                val latitudeAngle = PI * latitude / latitudeSegments
                val y = cos(latitudeAngle).toFloat()
                val radius = sin(latitudeAngle).toFloat()
                for (longitude in 0..longitudeSegments) {
                    val longitudeAngle = 2.0 * PI * longitude / longitudeSegments
                    val x = (radius * cos(longitudeAngle)).toFloat()
                    val z = (radius * sin(longitudeAngle)).toFloat()
                    vertices[offset++] = x
                    vertices[offset++] = y
                    vertices[offset++] = z
                    vertices[offset++] = x
                    vertices[offset++] = y
                    vertices[offset++] = z
                }
            }

            val indices = ShortArray(latitudeSegments * longitudeSegments * INDICES_PER_QUAD)
            var indexOffset = 0
            val rowWidth = longitudeSegments + 1
            for (latitude in 0 until latitudeSegments) {
                for (longitude in 0 until longitudeSegments) {
                    val topLeft = latitude * rowWidth + longitude
                    val bottomLeft = topLeft + rowWidth
                    indices[indexOffset++] = topLeft.toShort()
                    indices[indexOffset++] = bottomLeft.toShort()
                    indices[indexOffset++] = (topLeft + 1).toShort()
                    indices[indexOffset++] = (topLeft + 1).toShort()
                    indices[indexOffset++] = bottomLeft.toShort()
                    indices[indexOffset++] = (bottomLeft + 1).toShort()
                }
            }
            return OrbSphereMesh(vertices, indices)
        }

        private const val INDICES_PER_QUAD = 6
        private const val MAX_UNSIGNED_SHORT_VERTICES = 1 shl 16
    }
}
