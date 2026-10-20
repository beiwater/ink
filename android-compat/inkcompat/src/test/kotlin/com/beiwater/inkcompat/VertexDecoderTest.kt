package com.beiwater.inkcompat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VertexDecoderTest {
    private val pos = AttrUnpack(AxisParams(-50f, 0.05f), AxisParams(-20f, 0.04f))
    private val side = AttrUnpack(AxisParams(-2f, 0.001f), AxisParams(-2f, 0.001f))
    private val fwd = AttrUnpack(AxisParams(-1f, 0.0005f), AxisParams(-1f, 0.0005f))

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ---- 来自 C++ 单测的向量（ink/geometry/internal/mesh_packing_test.cc） ----

    @Test
    fun xy12MatchesCppVector1() {
        // 字节 {0x22, 0xB3, 0xC3}，offset=0 scale=1 -> (555, 963)
        assertEquals(555, VertexDecoder.xy12X(0x22, 0xB3))
        assertEquals(963, VertexDecoder.xy12Y(0xB3, 0xC3))
    }

    @Test
    fun xy12MatchesCppVector2WithOffsetAndScale() {
        // 字节 {0x02, 0x33, 0xE8}，offset=-500 scale=4 -> (-360, 3500)
        val x = VertexDecoder.xy12X(0x02, 0x33)
        val y = VertexDecoder.xy12Y(0x33, 0xE8)
        assertEquals(35, x)
        assertEquals(1000, y)
        assertEquals(-360f, 4f * x - 500f, 0f)
        assertEquals(3500f, 4f * y - 500f, 0f)
    }

    @Test
    fun hclXyz10MatchesCppVectors() {
        val raw = IntArray(3)
        // {0x64, 0x51, 0x3D, 0xE0} -> (401, 275, 888)
        VertexDecoder.unpackHclRaw(0x64, 0x51, 0x3D, 0xE0, raw)
        assertArrayEquals(intArrayOf(401, 275, 888), raw)
        // {0x25, 0x9F, 0x4E, 0x10} -> 量化后的 (150, 500, 900)
        VertexDecoder.unpackHclRaw(0x25, 0x9F, 0x4E, 0x10, raw)
        assertArrayEquals(intArrayOf(150, 500, 900), raw)
    }

    // ---- 逐个公式（C++ 编码参数推出的期望值） ----

    @Test
    fun opacityShiftEndpointsAndZero() {
        // 编码参数 offset=-1、scale=2/254：字节 0 -> -1，127 -> 0，254 -> 1
        assertEquals(-1f, VertexDecoder.unpackOpacityShift(0), 0f)
        assertEquals(0f, VertexDecoder.unpackOpacityShift(127), 0f)
        assertEquals(1f, VertexDecoder.unpackOpacityShift(254), 0f)
    }

    @Test
    fun labelUsesMinus128() {
        // stroke_vertex.h: kInteriorLabel=0、kExteriorLeftLabel=-127、kExteriorRightLabel=127
        assertEquals(0f, VertexDecoder.unpackLabel(128), 0f)
        assertEquals(-127f, VertexDecoder.unpackLabel(1), 0f)
        assertEquals(127f, VertexDecoder.unpackLabel(255), 0f)
    }

    @Test
    fun hclValuesAreRawOver511MinusOne() {
        val buf = ByteArray(16)
        // 起点三项依次取 raw = 0、511、1022 -> -1、0、1
        TestEncoder.packXyz10(0, 511, 1022, buf, 4)
        val layout = PackedLayout.infer(7, 16)!!
        val d = VertexDecoder.decodePacked(ByteBuffer.wrap(buf), 1, layout, pos)
        assertArrayEquals(floatArrayOf(-1f, 0f, 1f), d.hcl!!, 1e-6f)
    }

    @Test
    fun animationOffsetIsByteOver255() {
        val layout = PackedLayout.infer(8, 16)!!
        val buf = ByteArray(16)
        buf[layout.animationOffset] = 255.toByte()
        val d = VertexDecoder.decodePacked(ByteBuffer.wrap(buf), 1, layout, pos)
        assertEquals(1f, d.animationOffset!![0], 0f)
    }

    @Test
    fun surfaceUvIsRawOver4095() {
        val layout = PackedLayout.infer(8, 16)!!
        val buf = ByteArray(16)
        TestEncoder.packXy12(4095, 2048, buf, layout.surfaceUvOffset)
        val d = VertexDecoder.decodePacked(ByteBuffer.wrap(buf), 1, layout, pos)
        assertEquals(1f, d.u!![0], 0f)
        assertEquals(2048f / 4095f, d.v!![0], 1e-7f)
    }

    // ---- 编码 -> 解码 往返 ----

    private fun randomVertices(rnd: Random, n: Int, layout: PackedLayout): List<TestEncoder.Vertex> =
        List(n) {
            TestEncoder.Vertex(
                x = -50f + rnd.nextFloat() * 200f,
                y = -20f + rnd.nextFloat() * 160f,
                opacityShift = -1f + rnd.nextInt(255) / 127f,
                hcl = if (layout.hasHcl) FloatArray(3) { -1f + rnd.nextInt(1023) / 511f } else null,
                sideDx = -2f + rnd.nextFloat() * 4f, sideDy = -2f + rnd.nextFloat() * 4f,
                sideLabel = rnd.nextInt(255) - 127,
                fwdDx = -1f + rnd.nextFloat() * 2f, fwdDy = -1f + rnd.nextFloat() * 2f,
                fwdLabel = rnd.nextInt(255) - 127,
                u = rnd.nextFloat(), v = rnd.nextFloat(), anim = rnd.nextInt(256) / 255f,
            )
        }

    @Test
    fun packedRoundTripForAllLayouts() {
        val rnd = Random(20240607L)
        for ((count, stride) in listOf(6 to 12, 7 to 16, 8 to 16, 9 to 20)) {
            val layout = PackedLayout.infer(count, stride)!!
            val src = randomVertices(rnd, 500, layout)
            val buf = TestEncoder.packVertices(src, layout, pos, side, fwd)
            val d = VertexDecoder.decodePacked(buf, src.size, layout, pos, side, fwd, withDerivatives = true)
            for ((i, s) in src.withIndex()) {
                val tag = "layout=$count 顶点 $i"
                // 量化误差不超过半个步长，再留一点 float 舍入余量
                assertEquals(tag, s.x, d.x[i], 0.05f / 2 + 1e-3f)
                assertEquals(tag, s.y, d.y[i], 0.04f / 2 + 1e-3f)
                assertEquals(tag, s.opacityShift, d.opacityShift[i], 1e-6f)
                assertEquals(tag, s.sideDx, d.side!![i * 3], 0.001f / 2 + 1e-5f)
                assertEquals(tag, s.sideDy, d.side!![i * 3 + 1], 0.001f / 2 + 1e-5f)
                assertEquals(tag, s.sideLabel.toFloat(), d.side!![i * 3 + 2], 0f)
                assertEquals(tag, s.fwdDx, d.forward!![i * 3], 0.0005f / 2 + 1e-5f)
                assertEquals(tag, s.fwdDy, d.forward!![i * 3 + 1], 0.0005f / 2 + 1e-5f)
                assertEquals(tag, s.fwdLabel.toFloat(), d.forward!![i * 3 + 2], 0f)
                if (layout.hasHcl) {
                    for (k in 0 until 3) assertEquals(tag, s.hcl!![k], d.hcl!![i * 3 + k], 1e-5f)
                } else {
                    assertNull(d.hcl)
                }
                if (layout.hasSurfaceUv) {
                    assertEquals(tag, s.u, d.u!![i], 1f / 4095f / 2 + 1e-6f)
                    assertEquals(tag, s.v, d.v!![i], 1f / 4095f / 2 + 1e-6f)
                    assertEquals(tag, s.anim, d.animationOffset!![i], 1e-6f)
                } else {
                    assertNull(d.u)
                    assertNull(d.animationOffset)
                }
            }
        }
    }

    @Test
    fun derivativesAreSkippedByDefault() {
        val layout = PackedLayout.infer(6, 12)!!
        val buf = ByteBuffer.wrap(ByteArray(12 * 3))
        val d = VertexDecoder.decodePacked(buf, 3, layout, pos)
        assertNull(d.side)
        assertNull(d.forward)
        assertEquals(3, d.count)
    }

    @Test
    fun decodeHonoursBaseOffsetAndPosition() {
        val layout = PackedLayout.infer(6, 12)!!
        val src = randomVertices(Random(1), 2, layout)
        val packed = TestEncoder.packVertices(src, layout, pos, side, fwd)
        val shifted = ByteBuffer.allocate(5 + 24)
        packed.rewind(); shifted.position(5); shifted.put(packed); shifted.position(5)
        val a = VertexDecoder.decodePacked(shifted, 2, layout, pos)
        val b = VertexDecoder.decodePacked(shifted, 2, layout, pos, baseOffset = 5)
        assertArrayEquals(a.x, b.x, 0f)
        assertEquals(src[1].x, a.x[1], 0.05f / 2 + 1e-3f)
        assertEquals("解码不应改变缓冲 position", 5, shifted.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortBufferIsRejected() {
        val layout = PackedLayout.infer(6, 12)!!
        VertexDecoder.decodePacked(ByteBuffer.wrap(ByteArray(23)), 2, layout, pos)
    }

    @Test(expected = IllegalArgumentException::class)
    fun derivativesWithoutParamsAreRejected() {
        val layout = PackedLayout.infer(6, 12)!!
        VertexDecoder.decodePacked(ByteBuffer.wrap(ByteArray(12)), 1, layout, pos, withDerivatives = true)
    }

    // ---- unpacked ----

    @Test
    fun unpackedRoundTripNativeOrderEvenWhenBufferIsBigEndian() {
        val src = randomVertices(Random(7), 50, PackedLayout.infer(9, 20)!!)
        val buf = TestEncoder.unpackedVertices(src, ByteOrder.nativeOrder())
        assertEquals("官方只读缓冲是 BIG_ENDIAN", ByteOrder.BIG_ENDIAN, buf.order())
        val d = VertexDecoder.decodeUnpacked(buf, src.size, withDerivatives = true)
        assertEquals("不改动传入缓冲的字节序", ByteOrder.BIG_ENDIAN, buf.order())
        for ((i, s) in src.withIndex()) {
            assertEquals(s.x, d.x[i], 0f)
            assertEquals(s.y, d.y[i], 0f)
            assertEquals(s.opacityShift, d.opacityShift[i], 0f)
            for (k in 0 until 3) assertEquals(s.hcl!![k], d.hcl!![i * 3 + k], 0f)
            assertEquals(s.sideDx, d.side!![i * 3], 0f)
            assertEquals(s.sideLabel.toFloat(), d.side!![i * 3 + 2], 0f)
            assertEquals(s.fwdDy, d.forward!![i * 3 + 1], 0f)
            assertEquals(s.fwdLabel.toFloat(), d.forward!![i * 3 + 2], 0f)
            assertEquals(s.u, d.u!![i], 0f)
            assertEquals(s.v, d.v!![i], 0f)
            assertEquals(s.anim, d.animationOffset!![i], 0f)
        }
    }

    @Test
    fun unpackedExplicitBigEndianOrder() {
        val src = randomVertices(Random(8), 3, PackedLayout.infer(9, 20)!!)
        val buf = TestEncoder.unpackedVertices(src, ByteOrder.BIG_ENDIAN)
        val d = VertexDecoder.decodeUnpacked(buf, 3, order = ByteOrder.BIG_ENDIAN)
        assertEquals(src[2].x, d.x[2], 0f)
    }

    @Test
    fun unpackedShortBufferIsRejected() {
        val err = runCatching { VertexDecoder.decodeUnpacked(ByteBuffer.allocate(119), 2) }.exceptionOrNull()
        assertSame(IllegalArgumentException::class.java, err?.javaClass)
        assertNotNull(VertexDecoder.decodeUnpacked(ByteBuffer.allocate(120), 2))
    }
}
