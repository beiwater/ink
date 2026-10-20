package com.beiwater.inkcompat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackedLayoutTest {
    @Test
    fun baseLayoutSixAttributes() {
        val l = PackedLayout.infer(6, 12)!!
        assertFalse(l.hasHcl)
        assertFalse(l.hasSurfaceUv)
        assertEquals(0, l.positionOffset)
        assertEquals(3, l.opacityOffset)
        assertEquals(-1, l.hclOffset)
        assertEquals(4, l.sideOffset)
        assertEquals(7, l.sideLabelOffset)
        assertEquals(8, l.forwardOffset)
        assertEquals(11, l.forwardLabelOffset)
        assertEquals(-1, l.surfaceUvOffset)
        assertEquals(-1, l.animationOffset)
        assertEquals(listOf(0, 2, 4), listOf(l.positionParamIndex, l.sideParamIndex, l.forwardParamIndex))
    }

    @Test
    fun hclLayoutSevenAttributes() {
        val l = PackedLayout.infer(7, 16)!!
        assertTrue(l.hasHcl)
        assertFalse(l.hasSurfaceUv)
        assertEquals(4, l.hclOffset)
        assertEquals(8, l.sideOffset)
        assertEquals(12, l.forwardOffset)
        assertEquals(15, l.forwardLabelOffset)
        assertEquals(listOf(0, 3, 5), listOf(l.positionParamIndex, l.sideParamIndex, l.forwardParamIndex))
    }

    @Test
    fun uvLayoutEightAttributes() {
        val l = PackedLayout.infer(8, 16)!!
        assertFalse(l.hasHcl)
        assertTrue(l.hasSurfaceUv)
        assertEquals(4, l.sideOffset)
        assertEquals(8, l.forwardOffset)
        assertEquals(12, l.surfaceUvOffset)
        assertEquals(15, l.animationOffset)
        assertEquals(listOf(0, 2, 4), listOf(l.positionParamIndex, l.sideParamIndex, l.forwardParamIndex))
    }

    @Test
    fun fullLayoutNineAttributes() {
        val l = PackedLayout.infer(9, 20)!!
        assertTrue(l.hasHcl)
        assertTrue(l.hasSurfaceUv)
        assertEquals(4, l.hclOffset)
        assertEquals(8, l.sideOffset)
        assertEquals(12, l.forwardOffset)
        assertEquals(16, l.surfaceUvOffset)
        assertEquals(19, l.animationOffset)
        // 最后一个属性（1 字节）应正好落在步长的末尾
        assertEquals(l.stride, l.animationOffset + 1)
        assertEquals(listOf(0, 3, 5), listOf(l.positionParamIndex, l.sideParamIndex, l.forwardParamIndex))
    }

    @Test
    fun attributesTileTheStrideWithoutOverlap() {
        for ((count, stride) in listOf(6 to 12, 7 to 16, 8 to 16, 9 to 20)) {
            val l = PackedLayout.infer(count, stride)!!
            val spans = mutableListOf(
                l.positionOffset to 3, l.opacityOffset to 1, l.sideOffset to 3, l.sideLabelOffset to 1,
                l.forwardOffset to 3, l.forwardLabelOffset to 1,
            )
            if (l.hasHcl) spans += l.hclOffset to 4
            if (l.hasSurfaceUv) { spans += l.surfaceUvOffset to 3; spans += l.animationOffset to 1 }
            val covered = BooleanArray(stride)
            for ((off, len) in spans) for (b in off until off + len) {
                assertFalse("字节 $b 重复占用 (count=$count)", covered[b])
                covered[b] = true
            }
            assertTrue("存在未被占用的字节 (count=$count)", covered.all { it })
        }
    }

    @Test
    fun unsupportedCombinationsReturnNull() {
        // 属性数不在 6..9
        assertNull(PackedLayout.infer(5, 12))
        assertNull(PackedLayout.infer(10, 24))
        assertNull(PackedLayout.infer(0, 0))
        // 属性数与步长不匹配
        assertNull(PackedLayout.infer(6, 16))
        assertNull(PackedLayout.infer(7, 12))
        assertNull(PackedLayout.infer(7, 20))
        assertNull(PackedLayout.infer(8, 20))
        assertNull(PackedLayout.infer(9, 16))
        // 规格最初写的 {16,20,20,24} 有误（packed 宽度之和是 12/16/16/20）：按旧值 (9,24) 应被拒绝
        assertNull(PackedLayout.infer(9, 24))
        // unpacked 的 9 个属性、步长 60 不是 packed
        assertNull(PackedLayout.infer(9, UnpackedLayout.STRIDE))
        assertNotNull(PackedLayout.infer(9, 20))
    }

    @Test
    fun unpackedLayoutFitsSixtyBytes() {
        assertEquals(60, UnpackedLayout.STRIDE)
        // 最后一个 float 正好在步长末尾
        assertEquals(UnpackedLayout.STRIDE, UnpackedLayout.ANIMATION + 4)
        assertEquals(UnpackedLayout.HCL + 12, UnpackedLayout.SIDE)
        assertEquals(UnpackedLayout.SIDE + 8, UnpackedLayout.SIDE_LABEL)
        assertEquals(UnpackedLayout.FORWARD_LABEL + 4, UnpackedLayout.SURFACE_UV)
    }
}
