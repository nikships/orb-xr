package com.ghosttyxr.app.terminal.hands

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbSphereMeshTest {

    @Test
    fun createSupportsHighDensityGxrMeshWithinUnsignedShortRange() {
        val mesh = OrbSphereMesh.create(latitudeSegments = 160, longitudeSegments = 256)

        assertEquals(41_377, mesh.vertexCount)
        assertEquals(245_760, mesh.indices.size)
        assertTrue(mesh.indices.all { (it.toInt() and 0xFFFF) < mesh.vertexCount })
    }

    @Test
    fun createBuildsIndexedSphereWithUnitNormals() {
        val latitudeSegments = 6
        val longitudeSegments = 8
        val mesh = OrbSphereMesh.create(latitudeSegments, longitudeSegments)

        assertEquals((latitudeSegments + 1) * (longitudeSegments + 1), mesh.vertexCount)
        assertEquals(latitudeSegments * longitudeSegments * 6, mesh.indices.size)
        mesh.indices.forEach { index ->
            assertTrue((index.toInt() and 0xFFFF) < mesh.vertexCount)
        }

        mesh.vertices.asList().chunked(OrbSphereMesh.FLOATS_PER_VERTEX).forEach { vertex ->
            assertEquals(vertex[0], vertex[3], 0f)
            assertEquals(vertex[1], vertex[4], 0f)
            assertEquals(vertex[2], vertex[5], 0f)
            val length = sqrt(vertex[3] * vertex[3] + vertex[4] * vertex[4] + vertex[5] * vertex[5])
            assertEquals(1f, length, 0.0001f)
        }
    }
}
