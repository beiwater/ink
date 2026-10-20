package com.beiwater.inkcompat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawArgsTest {
    private fun geometry(n: Int, indices: ShortArray = shortArrayOf(0, 1, 2)) = MeshGeometry(
        n, FloatArray(n * 2), indices,
        DecodedVertices(n, FloatArray(0), FloatArray(0), FloatArray(n), null, FloatArray(n), FloatArray(n), null, null, null),
    )

    @Test
    fun vertexCountArgumentIsFloatCountNotVertexCount() {
        assertEquals(8, geometry(4).vertexFloatCount)
        assertEquals(6, geometry(3).vertexFloatCount)
    }

    @Test
    fun consistentArraysPass() {
        val g = geometry(4)
        assertTrue(DrawArgs.isConsistent(g, FloatArray(8), IntArray(4)))
    }

    @Test
    fun mismatchedArraysAreRejected() {
        val g = geometry(4)
        assertFalse(DrawArgs.isConsistent(g, FloatArray(6), IntArray(4)))
        assertFalse(DrawArgs.isConsistent(g, FloatArray(8), IntArray(8)))
        assertFalse(DrawArgs.isConsistent(geometry(4, shortArrayOf(0, 1)), FloatArray(8), IntArray(4)))
    }

    @Test
    fun colorsAndTexsFromEntryMatchDrawContract() {
        val e = MeshEntry(geometry(4, shortArrayOf(0, 1, 2, 2, 1, 3)))
        val texs = e.texCoords(TexKey(1, 1, 16, 16))
        val colors = e.colors(ColorKey(-1, emptyList()))
        assertTrue(DrawArgs.isConsistent(e.geometry, texs, colors))
    }
}
