package com.beiwater.inkcompat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatDecisionTest {
    private val stamp = StampSpec("pencil", frames = 1, rows = 1, columns = 1, reverse = false)

    private fun paint(
        overlap: OverlapKind = OverlapKind.ANY,
        first: TextureKind = TextureKind.STAMPING,
        layers: Int = if (first == TextureKind.NONE) 0 else 1,
        modulate: Boolean = true,
        stamp: StampSpec? = if (first == TextureKind.STAMPING) this.stamp else null,
        functions: List<InkColorFunction>? = listOf(InkColorFunction.OpacityMultiplier(0.5f)),
    ) = PaintFeatures(overlap, first, layers, modulate, stamp, functions)

    // ---- 单个 paint 的判定表 ----

    @Test
    fun pencilV5ShapedPaintNeedsCompat() {
        assertEquals(PaintRoute.COMPAT, CompatDecision.routeOf(paint()))
    }

    @Test
    fun stampWithAccumulateNeedsCompat() {
        assertEquals(PaintRoute.COMPAT, CompatDecision.routeOf(paint(overlap = OverlapKind.ACCUMULATE)))
    }

    @Test
    fun stampWithDiscardIsNeitherPathNorCompat() {
        // DISCARD 与印章不兼容：路径渲染器因映射非 TILING 拒绝，兼容渲染器也不接
        assertEquals(PaintRoute.NONE, CompatDecision.routeOf(paint(overlap = OverlapKind.DISCARD)))
    }

    @Test
    fun plainDiscardAndAnyPaintsStayOfficial() {
        for (overlap in listOf(OverlapKind.ANY, OverlapKind.DISCARD)) {
            assertEquals(PaintRoute.OFFICIAL, CompatDecision.routeOf(paint(overlap, TextureKind.NONE, functions = emptyList())))
            assertEquals(PaintRoute.OFFICIAL, CompatDecision.routeOf(paint(overlap, TextureKind.TILING)))
        }
    }

    @Test
    fun tilingWithAccumulateIsNone() {
        // 路径渲染器不收 ACCUMULATE，兼容渲染器只收印章：都画不了，交给官方去打日志
        assertEquals(PaintRoute.NONE, CompatDecision.routeOf(paint(OverlapKind.ACCUMULATE, TextureKind.TILING)))
        assertEquals(PaintRoute.NONE, CompatDecision.routeOf(paint(OverlapKind.ACCUMULATE, TextureKind.NONE)))
    }

    @Test
    fun unsupportedFeaturesFallBackToOfficial() {
        assertEquals(PaintRoute.NONE, CompatDecision.routeOf(paint(modulate = false)))
        assertEquals(PaintRoute.NONE, CompatDecision.routeOf(paint(layers = 2)))
        assertEquals(PaintRoute.NONE, CompatDecision.routeOf(paint(stamp = stamp.copy(frames = 4))))
        assertEquals(PaintRoute.NONE, CompatDecision.routeOf(paint(functions = null)))
        assertEquals(PaintRoute.NONE, CompatDecision.routeOf(paint(overlap = OverlapKind.UNKNOWN)))
        assertEquals(PaintRoute.NONE, CompatDecision.routeOf(paint(first = TextureKind.UNKNOWN, stamp = null)))
    }

    @Test
    fun stampGridWithSingleFrameIsAccepted() {
        assertTrue(CompatDecision.compatAccepts(paint(stamp = stamp.copy(rows = 2, columns = 2))))
    }

    // ---- coat / 笔画 ----

    @Test
    fun coatPicksFirstPreferenceSomeoneCanDraw() {
        val unusable = paint(overlap = OverlapKind.DISCARD) // NONE
        val compat = paint()
        val official = paint(OverlapKind.ANY, TextureKind.NONE)
        assertEquals(CoatRoute(PaintRoute.COMPAT, 1), CompatDecision.routeCoat(listOf(unusable, compat, official)))
        assertEquals(CoatRoute(PaintRoute.OFFICIAL, 0), CompatDecision.routeCoat(listOf(official, compat)))
        assertEquals(CoatRoute(PaintRoute.NONE, -1), CompatDecision.routeCoat(listOf(unusable)))
        assertEquals(CoatRoute(PaintRoute.NONE, -1), CompatDecision.routeCoat(emptyList()))
    }

    @Test
    fun needsCompatIsFalseOnApi34AndAbove() {
        val coats = listOf(listOf(paint()))
        assertTrue(CompatDecision.needsCompat(33, coats))
        assertTrue(CompatDecision.needsCompat(26, coats))
        assertFalse(CompatDecision.needsCompat(34, coats))
        assertFalse(CompatDecision.needsCompat(36, coats))
    }

    @Test
    fun strokeWithoutStampNeverNeedsCompat() {
        val marker = listOf(listOf(paint(OverlapKind.DISCARD, TextureKind.NONE, functions = listOf(InkColorFunction.OpacityMultiplier(0.4f)))))
        assertFalse(CompatDecision.needsCompat(33, marker))
        assertFalse(CompatDecision.needsCompat(33, emptyList()))
    }

    @Test
    fun mixedStrokeIsPartiallyCompat() {
        val routes = CompatDecision.routeStroke(listOf(listOf(paint(OverlapKind.ANY, TextureKind.NONE)), listOf(paint())))
        assertTrue(CompatDecision.hasCompatCoat(routes))
        assertFalse(CompatDecision.allCompat(routes))
        assertTrue(CompatDecision.needsCompat(33, listOf(listOf(paint(OverlapKind.ANY, TextureKind.NONE)), listOf(paint()))))
    }

    @Test
    fun allCompatRequiresAtLeastOneCoat() {
        assertFalse(CompatDecision.allCompat(emptyList()))
        assertTrue(CompatDecision.allCompat(CompatDecision.routeStroke(listOf(listOf(paint()), listOf(paint())))))
    }
}
