package com.beiwater.inkcompat

import java.util.Random
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StampUvTest {
    private fun uv(u: Float, v: Float, frame: Int, rows: Int, cols: Int): Pair<Float, Float> {
        val out = FloatArray(2)
        StampUv.stampUv(u, v, frame, rows, cols, out)
        return out[0] to out[1]
    }

    @Test
    fun singleFrameIsIdentity() {
        val rnd = Random(3)
        val out = FloatArray(2)
        repeat(200) {
            val u = rnd.nextFloat()
            val v = rnd.nextFloat()
            for (reverse in listOf(false, true)) {
                StampUv.stampUv(u, v, rnd.nextFloat(), rnd.nextFloat() * 2, 1, 1, 1, reverse, out)
                assertEquals(u, out[0], 0f)
                assertEquals(v, out[1], 0f)
            }
        }
    }

    @Test
    fun restartSequence() {
        // 4 帧：p = 0, .25, .5, .75 -> 0, 1, 2, 3；p 接近 1 仍是最后一帧，p=1 回到 0
        val expected = listOf(0, 1, 2, 3)
        for ((i, p) in listOf(0f, 0.25f, 0.5f, 0.75f).withIndex()) {
            assertEquals(expected[i], StampUv.restartFrameIndex(p, 4))
        }
        assertEquals(3, StampUv.restartFrameIndex(0.99f, 4))
        assertEquals(0, StampUv.restartFrameIndex(1f, 4))
        // 进度可超过 1（[0,2) 的相位 + 粒子偏移），按小数部分取
        assertEquals(1, StampUv.restartFrameIndex(1.25f, 4))
        assertEquals(2, StampUv.restartFrameIndex(2.5f, 4))
    }

    @Test
    fun reverseSequenceFourFrames() {
        // 规格第 5 节与公式 floor(|2*fract(p+0.5)-1|*(frames-1)+0.5) 逐点推出：0, 2, 3, 2
        val expected = listOf(0, 2, 3, 2)
        for ((i, p) in listOf(0f, 0.25f, 0.5f, 0.75f).withIndex()) {
            assertEquals("p=$p", expected[i], StampUv.reverseFrameIndex(p, 4))
        }
        assertEquals(0, StampUv.reverseFrameIndex(1f, 4))
    }

    @Test
    fun reverseSequenceFiveFramesIsTriangleWave() {
        // 逐点手算：p = k/8 -> 0, 1, 2, 3, 4, 3, 2, 1（首尾帧换向时不重复）
        val expected = listOf(0, 1, 2, 3, 4, 3, 2, 1)
        for (k in 0 until 8) {
            assertEquals("k=$k", expected[k], StampUv.reverseFrameIndex(k / 8f, 5))
        }
    }

    @Test
    fun frameIndexSelectsModeAndStaysInRange() {
        val rnd = Random(5)
        repeat(1000) {
            val frames = 1 + rnd.nextInt(16)
            val p = rnd.nextFloat() * 3
            val a = StampUv.frameIndex(p, frames, reverse = false)
            val b = StampUv.frameIndex(p, frames, reverse = true)
            assertEquals(StampUv.restartFrameIndex(p, frames), a)
            assertEquals(StampUv.reverseFrameIndex(p, frames), b)
            assertTrue("a=$a b=$b frames=$frames", a in 0 until frames && b in 0 until frames)
        }
    }

    @Test
    fun gridMappingRowMajor() {
        // 6 帧、2 行 3 列：帧 4 在第 1 行第 1 列
        assertEquals(0.5f to 0.75f, uv(0.5f, 0.5f, 4, 2, 3))
        // 帧 0：(u/cols, v/rows)
        val (u0, v0) = uv(0.3f, 0.6f, 0, 2, 3)
        assertEquals(0.1f, u0, 1e-7f)
        assertEquals(0.3f, v0, 1e-7f)
        // 末格：u=1,v=1 映射到纹理右上角 (1, 1)
        assertEquals(1f to 1f, uv(1f, 1f, 5, 2, 3))
        // 单行 4 列取帧 2：u=1,v=0.5 -> ((1+2)/4, 0.5)
        assertEquals(0.75f to 0.5f, uv(1f, 0.5f, 2, 1, 4))
        // 单列 4 行取帧 2：(1, (0.5+2)/4)
        assertEquals(1f to 0.625f, uv(1f, 0.5f, 2, 4, 1))
    }

    @Test
    fun gridMappingMatchesIntegerRowCol() {
        for (rows in 1..6) for (cols in 1..6) for (f in 0 until rows * cols) {
            val (u, v) = uv(0f, 0f, f, rows, cols)
            assertEquals("rows=$rows cols=$cols f=$f", (f % cols).toFloat() / cols, u, 1e-7f)
            assertEquals("rows=$rows cols=$cols f=$f", (f / cols).toFloat() / rows, v, 1e-7f)
        }
    }

    @Test
    fun animationOffsetShiftsFrame() {
        val out = FloatArray(2)
        // 4 帧 RESTART，进度 0 + 粒子偏移 0.5 -> 第 2 帧（单行 4 列）
        StampUv.stampUv(0f, 0f, 0.5f, 0f, 4, 1, 4, false, out)
        assertEquals(2f / 4f, out[0], 1e-7f)
        assertEquals(0f, out[1], 0f)
    }

    @Test
    fun fillTexCoordsPerVertexWithScale() {
        val v = DecodedVertices(
            count = 2, x = FloatArray(2), y = FloatArray(2), opacityShift = FloatArray(2),
            hcl = null, u = floatArrayOf(0f, 1f), v = floatArrayOf(0f, 1f),
            animationOffset = floatArrayOf(0f, 0.5f), side = null, forward = null,
        )
        val out = FloatArray(4)
        StampUv.fillTexCoords(v, 0f, 1, 1, 1, false, out, scaleX = 64f, scaleY = 32f)
        assertEquals(listOf(0f, 0f, 64f, 32f), out.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun fillTexCoordsWithoutUvIsRejected() {
        val v = DecodedVertices(1, FloatArray(1), FloatArray(1), FloatArray(1), null, null, null, null, null, null)
        StampUv.fillTexCoords(v, 0f, 1, 1, 1, false, FloatArray(2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroFramesIsRejected() {
        StampUv.frameIndex(0f, 0, false)
    }

    /**
     * 着色器用 `floor(fract(frame/cols)*cols)` 取列；按 float32 逐位仿真（未在 GPU 上验证）会在个别组合上差 1
     * （如 2x3 网格的帧 5：仿真得列 1，正确是 2）。本实现用整数除法/取余给出本意结果，不复刻该舍入误差。
     * v5 铅笔 frames=1 不受影响。
     */
    @Test
    fun shaderFloatColumnFormulaCanDeviateInFloat32Emulation() {
        val frac = 5f / 3f
        val shaderCol = floor((frac - floor(frac)) * 3f).toInt()
        assertEquals(1, shaderCol)
        assertEquals(2f / 3f, uv(0f, 0f, 5, 2, 3).first, 1e-7f)
    }
}
