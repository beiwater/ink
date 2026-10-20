package com.beiwater.inkcompat

import kotlin.math.PI
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorMathTest {
    private fun oklabOfArgb(argb: Int): FloatArray {
        val out = FloatArray(3)
        ColorMath.srgbToOklab(
            ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, out,
        )
        return out
    }

    private fun rgb8(linear: FloatArray) = intArrayOf(
        ColorMath.encodeChannel8(linear[0]), ColorMath.encodeChannel8(linear[1]), ColorMath.encodeChannel8(linear[2]),
    )

    // ---- 来自 C++ 单测的基准值（ink/color/color_test.cc: AsOklab / FromOklab），容差同为 1e-6 ----

    @Test
    fun oklabMatchesCppReferenceValues() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), oklabOfArgb(0xFF000000.toInt()), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), oklabOfArgb(0xFFFFFFFF.toInt()), 1e-6f)
        assertArrayEquals(floatArrayOf(0.627955f, 0.224863f, 0.125846f), oklabOfArgb(0xFFFF0000.toInt()), 1e-6f)
        // Color::GoogleBlue() = 0x4285f4
        assertArrayEquals(floatArrayOf(0.630386f, -0.031398f, -0.177268f), oklabOfArgb(0xFF4285F4.toInt()), 1e-6f)
    }

    @Test
    fun oklabToLinearMatchesCppReferenceValues() {
        val lin = FloatArray(3)
        ColorMath.oklabToLinearSrgb(0f, 0f, 0f, lin)
        assertArrayEquals(intArrayOf(0, 0, 0), rgb8(lin))
        ColorMath.oklabToLinearSrgb(1f, 0f, 0f, lin)
        assertArrayEquals(intArrayOf(255, 255, 255), rgb8(lin))
        ColorMath.oklabToLinearSrgb(0.627955f, 0.224863f, 0.125846f, lin)
        assertArrayEquals(intArrayOf(255, 0, 0), rgb8(lin))
        ColorMath.oklabToLinearSrgb(0.630386f, -0.031398f, -0.177268f, lin)
        assertArrayEquals(intArrayOf(0x42, 0x85, 0xF4), rgb8(lin))
    }

    @Test
    fun oklabRoundTripOverSrgbGrid() {
        val lab = FloatArray(3)
        val lin = FloatArray(3)
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            ColorMath.srgbToOklab(r / 255f, g / 255f, b / 255f, lab)
            ColorMath.oklabToLinearSrgb(lab[0], lab[1], lab[2], lin)
            assertArrayEquals("rgb=($r,$g,$b)", intArrayOf(r, g, b), rgb8(lin))
        }
    }

    // ---- sRGB 传输函数 ----

    @Test
    fun srgbTransferFunction() {
        assertEquals(0f, ColorMath.srgbDecode(0f), 0f)
        assertEquals(1f, ColorMath.srgbDecode(1f), 1e-7f)
        assertEquals(1f, ColorMath.linearToSrgbEncoded(1f), 1e-7f)
        // 线性段：0.04045 以下为 x/12.92
        assertEquals(0.02f / 12.92f, ColorMath.srgbDecode(0.02f), 1e-9f)
        // ((0.5 + 0.055) / 1.055) ^ 2.4，公认的 sRGB 0.5 -> 线性 0.2140411
        assertEquals(0.2140411f, ColorMath.srgbDecode(0.5f), 1e-6f)
        for (i in 0..100) {
            val x = i / 100f
            assertEquals(x, ColorMath.linearToSrgbEncoded(ColorMath.srgbDecode(x)), 1e-5f)
        }
        // 负值走线性段，不产生 NaN；写入 8 位时钳制
        assertEquals(0, ColorMath.encodeChannel8(-0.5f))
        assertEquals(255, ColorMath.encodeChannel8(3f))
        assertEquals(0, ColorMath.encodeChannel8(Float.NaN))
    }

    // ---- 不透明度偏移（公式 saturate((shift + 1) * base)） ----

    @Test
    fun opacityShift() {
        assertEquals(0f, ColorMath.applyOpacityShift(-1f, 0.7f), 0f)
        assertEquals(0.7f, ColorMath.applyOpacityShift(0f, 0.7f), 1e-7f)
        assertEquals(0.6f, ColorMath.applyOpacityShift(1f, 0.3f), 1e-7f)
        assertEquals(1f, ColorMath.applyOpacityShift(1f, 0.8f), 0f)
        assertEquals(0.35f, ColorMath.applyOpacityShift(-0.5f, 0.7f), 1e-7f)
    }

    // ---- HCL 偏移（公式推导：旋转 hue*2π，色度乘 chroma+1，明度加 lightness） ----

    private fun hcl(h: Float, c: Float, l: Float, shift: Float, lab: FloatArray): FloatArray {
        val out = FloatArray(4)
        ColorMath.applyHclAndOpacityShift(h, c, l, shift, lab, out)
        return out
    }

    @Test
    fun hclIdentity() {
        val lab = floatArrayOf(0.6f, 0.1f, -0.2f, 0.8f)
        assertArrayEquals(lab, hcl(0f, 0f, 0f, 0f, lab), 1e-7f)
    }

    @Test
    fun hclHueRotation() {
        val lab = floatArrayOf(0.6f, 0.1f, -0.2f, 1f)
        // 90 度：(a, b) -> (-b, a)
        assertArrayEquals(floatArrayOf(0.6f, 0.2f, 0.1f, 1f), hcl(0.25f, 0f, 0f, 0f, lab), 1e-6f)
        // 180 度：取反
        assertArrayEquals(floatArrayOf(0.6f, -0.1f, 0.2f, 1f), hcl(0.5f, 0f, 0f, 0f, lab), 1e-6f)
        // 整圈：回到原值
        assertArrayEquals(lab, hcl(1f, 0f, 0f, 0f, lab), 1e-6f)
    }

    @Test
    fun hclChromaAndLightness() {
        val lab = floatArrayOf(0.6f, 0.1f, -0.2f, 1f)
        assertArrayEquals(floatArrayOf(0.6f, 0f, 0f, 1f), hcl(0f, -1f, 0f, 0f, lab), 1e-7f)
        assertArrayEquals(floatArrayOf(0.6f, 0.2f, -0.4f, 1f), hcl(0f, 1f, 0f, 0f, lab), 1e-7f)
        assertArrayEquals(floatArrayOf(0.7f, 0.1f, -0.2f, 1f), hcl(0f, 0f, 0.1f, 0f, lab), 1e-7f)
        // 组合：先旋转 90 度再乘 1.5
        assertArrayEquals(floatArrayOf(0.5f, 0.3f, 0.15f, 1f), hcl(0.25f, 0.5f, -0.1f, 0f, lab), 1e-6f)
    }

    @Test
    fun hclOpacityShiftAppliesToAlpha() {
        val lab = floatArrayOf(0.6f, 0.1f, -0.2f, 0.5f)
        assertEquals(0f, hcl(0f, 0f, 0f, -1f, lab)[3], 0f)
        assertEquals(1f, hcl(0f, 0f, 0f, 1f, lab)[3], 0f)
        assertEquals(0.75f, hcl(0f, 0f, 0f, 0.5f, lab)[3], 1e-7f)
    }

    // ---- 颜色函数 ----

    @Test
    fun opacityMultiplierFunction() {
        val c = BrushColor.fromArgb(0x80336699.toInt(), listOf(InkColorFunction.OpacityMultiplier(0.5f)))
        assertEquals(128f / 255f * 0.5f, c.alpha, 1e-7f)
        assertEquals(0x336699, c.rgb24)
        val twice = BrushColor.fromArgb(
            0xFF336699.toInt(),
            listOf(InkColorFunction.OpacityMultiplier(0.5f), InkColorFunction.OpacityMultiplier(0.5f)),
        )
        assertEquals(0.25f, twice.alpha, 1e-7f)
        assertEquals(1f, BrushColor.fromArgb(0xFF336699.toInt(), listOf(InkColorFunction.OpacityMultiplier(3f))).alpha, 0f)
    }

    @Test
    fun noFunctionsKeepsRgbExactly() {
        for (argb in intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF4285F4.toInt(), 0xFF123456.toInt())) {
            assertEquals(argb and 0xFFFFFF, BrushColor.fromArgb(argb).rgb24)
        }
    }

    @Test
    fun lightnessChromaAndReplaceFunctions() {
        // 白色明度 -1 -> L=0 -> 黑
        val black = BrushColor.fromArgb(0xFFFFFFFF.toInt(), listOf(InkColorFunction.LightnessOffset(-1f)))
        assertEquals(0f, black.oklabL, 1e-6f)
        assertEquals(0, black.rgb24)
        // 红色色度乘 0 -> 灰（RGB 三通道相等），明度保持 0.627955
        val grey = BrushColor.fromArgb(0xFFFF0000.toInt(), listOf(InkColorFunction.ChromaMultiplier(0f)))
        assertEquals(0.627955f, grey.oklabL, 1e-6f)
        assertEquals(0f, grey.oklabA, 1e-6f)
        assertEquals((grey.rgb24 shr 16) and 0xFF, grey.rgb24 and 0xFF)
        // 色相偏移整圈 = 恒等
        assertEquals(0xFF4285F4.toInt() and 0xFFFFFF, BrushColor.fromArgb(0xFF4285F4.toInt(), listOf(InkColorFunction.HueOffset((2 * PI).toFloat()))).rgb24)
        // ReplaceColor 忽略原色，但之后的 OpacityMultiplier 作用在新色上
        val replaced = BrushColor.fromArgb(
            0xFF000000.toInt(),
            listOf(InkColorFunction.ReplaceColor(0x80FF0000.toInt()), InkColorFunction.OpacityMultiplier(0.5f)),
        )
        assertEquals(0xFF0000, replaced.rgb24)
        assertEquals(128f / 255f * 0.5f, replaced.alpha, 1e-7f)
    }

    // ---- 顶点颜色 ----

    @Test
    fun vertexColorWithoutHclOnlyChangesAlpha() {
        val brush = BrushColor.fromArgb(0xCC336699.toInt())
        assertEquals(0xCC336699.toInt(), ColorMath.vertexColorArgb(brush, 0f, null, 0))
        assertEquals(0x00336699, ColorMath.vertexColorArgb(brush, -1f, null, 0))
        // alpha = 0xCC/255 = 0.8，shift=1 时 2*0.8 钳制到 1
        assertEquals(0xFF336699.toInt(), ColorMath.vertexColorArgb(brush, 1f, null, 0))
    }

    @Test
    fun vertexColorWithZeroHclEqualsFastPath() {
        val zero = floatArrayOf(0f, 0f, 0f)
        for (argb in intArrayOf(0xFF336699.toInt(), 0x80FF0000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF4285F4.toInt())) {
            val brush = BrushColor.fromArgb(argb)
            for (shift in floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
                assertEquals(
                    "argb=${Integer.toHexString(argb)} shift=$shift",
                    ColorMath.vertexColorArgb(brush, shift, null, 0),
                    ColorMath.vertexColorArgb(brush, shift, zero, 0),
                )
            }
        }
    }

    @Test
    fun vertexHclPathAgreesWithColorFunctions() {
        // 顶点 HCL 偏移与同参数的 ColorFunction 链在 Oklab 里做的是同一件事
        val base = 0xFF4285F4.toInt()
        val viaHcl = ColorMath.vertexColorArgb(
            BrushColor.fromArgb(base), 0f, floatArrayOf(0.25f, 0.5f, -0.1f), 0,
        )
        val viaFunctions = BrushColor.fromArgb(
            base,
            listOf(
                InkColorFunction.HueOffset((PI / 2).toFloat()),
                InkColorFunction.ChromaMultiplier(1.5f),
                InkColorFunction.LightnessOffset(-0.1f),
            ),
        )
        assertEquals(viaFunctions.rgb24, viaHcl and 0xFFFFFF)
        assertEquals(0xFF, viaHcl ushr 24)
    }

    @Test
    fun vertexHclIndexSelectsTriplet() {
        val brush = BrushColor.fromArgb(0xFFFF8040.toInt())
        val hcl = floatArrayOf(0f, 0f, 0f, 0.5f, 0f, 0f)
        assertEquals(
            ColorMath.vertexColorArgb(brush, 0f, floatArrayOf(0.5f, 0f, 0f), 0),
            ColorMath.vertexColorArgb(brush, 0f, hcl, 1),
        )
    }

    @Test
    fun fillVertexColorsMatchesPerVertex() {
        val brush = BrushColor.fromArgb(0xFF336699.toInt())
        val v = DecodedVertices(
            count = 3, x = FloatArray(3), y = FloatArray(3),
            opacityShift = floatArrayOf(-1f, 0f, 0.5f),
            hcl = floatArrayOf(0f, 0f, 0f, 0.25f, 0f, 0f, 0f, 0.5f, 0.1f),
            u = null, v = null, animationOffset = null, side = null, forward = null,
        )
        val out = IntArray(3)
        ColorMath.fillVertexColors(brush, v, out)
        for (i in 0 until 3) {
            assertEquals(ColorMath.vertexColorArgb(brush, v.opacityShift[i], v.hcl, i), out[i])
        }
        assertEquals(0, out[0] ushr 24)
    }
}
