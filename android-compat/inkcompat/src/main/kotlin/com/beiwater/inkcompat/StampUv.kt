package com.beiwater.inkcompat

import kotlin.math.abs
import kotlin.math.floor

/**
 * 印章纹理（StampingTexture）的 UV 与动画帧计算，复刻 `calculateStampingTextureUv`
 * （sksl_vertex_shader_helper_functions.h）。
 *
 * 纹理被当作 `rows x columns` 的网格，帧按行优先排列；`frames` 可小于格子总数。
 */
object StampUv {
    private fun fract(x: Float): Float = x - floor(x)

    /** RESTART 模式：`floor(fract(progress) * frames)`，依次走完所有帧后回到第一帧。 */
    fun restartFrameIndex(progress: Float, frames: Int): Int {
        requireFrames(frames)
        return floor(fract(progress) * frames).toInt().coerceIn(0, frames - 1)
    }

    /** REVERSE 模式：往返播放，换向时首尾帧不重复：`floor(|2*fract(p+0.5)-1| * (frames-1) + 0.5)`。 */
    fun reverseFrameIndex(progress: Float, frames: Int): Int {
        requireFrames(frames)
        val zigzag = abs(2f * fract(progress + 0.5f) - 1f)
        return floor(zigzag * (frames - 1) + 0.5f).toInt().coerceIn(0, frames - 1)
    }

    /** 当前帧序号。[progress] 已含粒子偏移（`动画进度 + animationOffset`）。 */
    fun frameIndex(progress: Float, frames: Int, reverse: Boolean): Int =
        if (reverse) reverseFrameIndex(progress, frames) else restartFrameIndex(progress, frames)

    /**
     * 把表面 UV 映射到第 [frame] 帧所在的网格：`((u + col) / cols, (v + row) / rows)`，
     * 其中 `row = frame / cols`、`col = frame % cols`。结果写入 [out] 的 [at]、[at]+1。
     *
     * 行列用整数运算，等价于着色器里 `floor(fract(frame/cols)*cols)` 的本意；
     * 后者在 float32 下对个别 (frame, cols) 组合可能差 1，我们不复刻这个舍入误差。
     */
    fun stampUv(
        u: Float, v: Float, frame: Int, rows: Int, cols: Int,
        out: FloatArray, at: Int = 0,
    ) {
        requireGrid(rows, cols)
        out[at] = (u + frame % cols) / cols
        out[at + 1] = (v + frame / cols) / rows
    }

    /**
     * 单顶点完整计算：`progress = textureAnimationProgress + animationOffset` 先选帧，再映射 UV。
     * frames/rows/cols 都为 1 时恒等于 (u, v)。
     */
    fun stampUv(
        u: Float, v: Float, animationOffset: Float, textureAnimationProgress: Float,
        frames: Int, rows: Int, cols: Int, reverse: Boolean,
        out: FloatArray, at: Int = 0,
    ) {
        val frame = frameIndex(textureAnimationProgress + animationOffset, frames, reverse)
        stampUv(u, v, frame, rows, cols, out, at)
    }

    /**
     * 为整个解码结果生成 `drawVertices` 的纹理坐标（每顶点 2 个 float，写入 [out]）。
     * [scaleX]/[scaleY] 用于把 [0,1] 的 UV 乘成纹素坐标（Paint 的 BitmapShader 没有 1/w、1/h 局部矩阵时使用）。
     * 解码结果没有表面 UV（布局不含 UV）时抛 [IllegalArgumentException]。
     */
    fun fillTexCoords(
        vertices: DecodedVertices, textureAnimationProgress: Float,
        frames: Int, rows: Int, cols: Int, reverse: Boolean,
        out: FloatArray, scaleX: Float = 1f, scaleY: Float = 1f,
    ) {
        val u = requireNotNull(vertices.u) { "顶点缺少表面 UV" }
        val v = requireNotNull(vertices.v) { "顶点缺少表面 UV" }
        val anim = vertices.animationOffset
        require(out.size >= vertices.count * 2) { "输出数组太短" }
        for (i in 0 until vertices.count) {
            stampUv(u[i], v[i], anim?.get(i) ?: 0f, textureAnimationProgress, frames, rows, cols, reverse, out, i * 2)
            out[i * 2] *= scaleX
            out[i * 2 + 1] *= scaleY
        }
    }

    private fun requireFrames(frames: Int) = require(frames >= 1) { "动画帧数必须 >= 1" }

    private fun requireGrid(rows: Int, cols: Int) = require(rows >= 1 && cols >= 1) { "行列数必须 >= 1" }
}
