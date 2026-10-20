package com.beiwater.inkcompat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshGeometryBuilderTest {
    private val pos = AttrUnpack(AxisParams(-50f, 0.05f), AxisParams(-20f, 0.04f))
    private val side = AttrUnpack(AxisParams(-2f, 0.001f), AxisParams(-2f, 0.001f))
    private val fwd = AttrUnpack(AxisParams(-1f, 0.0005f), AxisParams(-1f, 0.0005f))

    /** 对应 Mesh.vertexAttributeUnpackingParams：下标 0 位置，其余按需占位。 */
    private fun params(hasHcl: Boolean): List<List<AxisParams>> {
        val p = listOf(pos.x, pos.y)
        val s = listOf(side.x, side.y)
        val f = listOf(fwd.x, fwd.y)
        val empty = emptyList<AxisParams>()
        // 属性序：position, opacity, [hcl], side, sideLabel, forward, forwardLabel, ...
        return if (hasHcl) listOf(p, empty, empty, s, empty, f, empty, empty, empty)
        else listOf(p, empty, s, empty, f, empty, empty, empty)
    }

    private fun quad(uv: Boolean = true) = listOf(
        TestEncoder.Vertex(1f, 2f, 0f, u = if (uv) 0f else 0f, v = 0f),
        TestEncoder.Vertex(10f, 2f, 0.5f, u = 1f, v = 0f),
        TestEncoder.Vertex(1f, 12f, -0.5f, u = 0f, v = 1f),
        TestEncoder.Vertex(10f, 12f, 1f, u = 1f, v = 1f),
    )

    private fun shorts(vararg v: Int): ShortBuffer =
        ByteBuffer.allocate(v.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().also { b ->
            v.forEach { b.put(it.toShort()) }
            b.flip()
        }

    @Test
    fun packedStampMeshDecodesPositionsIndicesAndUv() {
        val layout = PackedLayout.infer(8, 16)!!
        val vb = TestEncoder.packVertices(quad(), layout, pos, side, fwd)
        val g = MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2, 2, 1, 3), 4, 8, 16, params(false))!!
        assertEquals(4, g.vertexCount)
        assertEquals(8, g.vertexFloatCount)
        assertEquals(8, g.positions.size)
        assertEquals(1f, g.positions[0], 0.06f)
        assertEquals(12f, g.positions[5], 0.05f)
        assertArrayEquals(shortArrayOf(0, 1, 2, 2, 1, 3), g.indices)
        assertTrue(g.hasSurfaceUv)
        assertEquals(1f, g.shading.u!![1], 1e-3f)
        assertEquals(1f, g.shading.v!![3], 1e-3f)
        assertNull(g.shading.hcl)
    }

    @Test
    fun packedWithZeroHclDropsHclColumn() {
        val layout = PackedLayout.infer(9, 20)!!
        val vs = quad().map { TestEncoder.Vertex(it.x, it.y, it.opacityShift, hcl = floatArrayOf(0f, 0f, 0f), u = it.u, v = it.v) }
        val vb = TestEncoder.packVertices(vs, layout, pos, side, fwd)
        val g = MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 9, 20, params(true))!!
        assertNull(g.shading.hcl)
    }

    @Test
    fun packedWithNonZeroHclKeepsHclColumn() {
        val layout = PackedLayout.infer(9, 20)!!
        val vs = quad().map { TestEncoder.Vertex(it.x, it.y, it.opacityShift, hcl = floatArrayOf(0.2f, 0f, 0f), u = it.u, v = it.v) }
        val vb = TestEncoder.packVertices(vs, layout, pos, side, fwd)
        val g = MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 9, 20, params(true))!!
        assertNotNull(g.shading.hcl)
        assertEquals(12, g.shading.hcl!!.size)
    }

    @Test
    fun meshWithoutUvLayoutHasNoSurfaceUv() {
        val layout = PackedLayout.infer(6, 12)!!
        val vb = TestEncoder.packVertices(quad(), layout, pos, side, fwd)
        val g = MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 6, 12, params(false))!!
        assertFalse(g.hasSurfaceUv)
    }

    @Test
    fun unrecognisedLayoutReturnsNull() {
        val vb = ByteBuffer.allocate(4 * 32)
        assertNull(MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 8, 32, params(false)))
        assertNull(MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 8, 20, params(false)))
        assertNull(MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 5, 12, params(false)))
    }

    @Test
    fun missingOrShortUnpackParamsReturnNull() {
        val layout = PackedLayout.infer(8, 16)!!
        val vb = TestEncoder.packVertices(quad(), layout, pos, side, fwd)
        assertNull(MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 8, 16, emptyList()))
        assertNull(MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 8, 16, listOf(listOf(pos.x))))
    }

    @Test
    fun invalidIndicesReturnNull() {
        val layout = PackedLayout.infer(8, 16)!!
        val vb = TestEncoder.packVertices(quad(), layout, pos, side, fwd)
        // 越界
        assertNull(MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 4), 4, 8, 16, params(false)))
        // 无符号：0xFFFF 不是 -1
        assertNull(MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 0xFFFF), 4, 8, 16, params(false)))
        // 个数不是 3 的倍数
        assertNull(MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1), 4, 8, 16, params(false)))
    }

    @Test
    fun tooShortVertexBufferReturnsNull() {
        val layout = PackedLayout.infer(8, 16)!!
        val vb = TestEncoder.packVertices(quad().take(3), layout, pos, side, fwd)
        assertNull(MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 8, 16, params(false)))
    }

    @Test
    fun degenerateMeshesAreEmptyNotFailures() {
        val layout = PackedLayout.infer(8, 16)!!
        val vb = TestEncoder.packVertices(quad().take(2), layout, pos, side, fwd)
        assertSame(MeshGeometry.EMPTY, MeshGeometryBuilder.fromStrokeMesh(vb, shorts(), 2, 8, 16, params(false)))
        val vb4 = TestEncoder.packVertices(quad(), layout, pos, side, fwd)
        assertSame(MeshGeometry.EMPTY, MeshGeometryBuilder.fromStrokeMesh(vb4, shorts(), 4, 8, 16, params(false)))
    }

    @Test
    fun readIndicesKeepsBufferPosition() {
        val buf = shorts(0, 1, 2, 2, 1, 3)
        val out = MeshGeometryBuilder.readIndices(buf, 4)!!
        assertEquals(6, out.size)
        assertEquals(0, buf.position())
        assertEquals(6, buf.remaining())
    }

    @Test
    fun unpackedMeshHonoursNativeOrderOnBigEndianReadOnlyBuffer() {
        val vb = TestEncoder.unpackedVertices(quad(), ByteOrder.nativeOrder())
        val g = MeshGeometryBuilder.fromUnpacked(vb, shorts(0, 1, 2, 2, 1, 3), 4)!!
        assertEquals(4, g.vertexCount)
        assertEquals(10f, g.positions[2], 0f)
        assertEquals(12f, g.positions[7], 0f)
        assertEquals(0.5f, g.shading.opacityShift[1], 0f)
        assertTrue(g.hasSurfaceUv)
        assertNull(g.shading.hcl)
    }

    @Test
    fun unpackedBufferTooShortReturnsNull() {
        val vb = TestEncoder.unpackedVertices(quad().take(3), ByteOrder.nativeOrder())
        assertNull(MeshGeometryBuilder.fromUnpacked(vb, shorts(0, 1, 2), 4))
    }

    @Test
    fun strokeMeshWithUnpackedStrideFallsBackToUnpacked() {
        val vb = TestEncoder.unpackedVertices(quad(), ByteOrder.nativeOrder())
        val g = MeshGeometryBuilder.fromStrokeMesh(vb, shorts(0, 1, 2), 4, 9, 60, emptyList())!!
        assertEquals(10f, g.positions[2], 0f)
    }
}
