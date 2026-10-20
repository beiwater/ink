package com.beiwater.inkcompat

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 解包一个分量用的线性变换：`值 = scale * 原始整数 + offset`，对应 `MeshAttributeUnpackingParams.components[i]`。 */
class AxisParams(val offset: Float, val scale: Float)

/** 一个 XY12 属性的解包参数（x、y 各一组），对应着色器里的 `{xOffset, xScale, yOffset, yScale}`。 */
class AttrUnpack(val x: AxisParams, val y: AxisParams)

/**
 * 解码后的顶点，按列存放，下标即顶点序号。可选列在布局没有该属性时为 null。
 *
 * - [hcl]、[side]、[forward] 为每顶点 3 个连续 float：hcl 是 (色相, 色度, 明度)，范围约 [-1, 1]；
 *   side/forward 是 (dx, dy, label)，仅在解码时要求 `withDerivatives` 才会填充。
 * - [opacityShift] 范围 [-1, 1]。
 */
class DecodedVertices(
    val count: Int,
    val x: FloatArray,
    val y: FloatArray,
    val opacityShift: FloatArray,
    val hcl: FloatArray?,
    val u: FloatArray?,
    val v: FloatArray?,
    val animationOffset: FloatArray?,
    val side: FloatArray?,
    val forward: FloatArray?,
)

/**
 * 按 sksl_vertex_shader_helper_functions.h 的解包公式在 CPU 上解码顶点缓冲。纯 JDK，不依赖 native。
 */
object VertexDecoder {
    /** HCL 每分量 10 位，量化后 `raw/511 - 1`（C++ 编码参数 offset=-1、scale=2/1022）。 */
    private const val HCL_DIVISOR = 511f
    private const val UV_DIVISOR = 4095f

    /**
     * 解码 packed 顶点缓冲（`Mesh.getRawVertexBuffer()`）。
     *
     * 只用绝对位置读取单个字节，所以缓冲字节序和当前 position 都不影响结果；
     * 第 i 个顶点从 `baseOffset + i * layout.stride` 开始。
     *
     * @param position 位置属性的解包参数（`vertexAttributeUnpackingParams[layout.positionParamIndex]`）
     * @param side 侧向导数的解包参数；`withDerivatives` 为 true 时必填
     * @param forward 前向导数的解包参数；`withDerivatives` 为 true 时必填
     */
    fun decodePacked(
        buffer: ByteBuffer,
        count: Int,
        layout: PackedLayout,
        position: AttrUnpack,
        side: AttrUnpack? = null,
        forward: AttrUnpack? = null,
        withDerivatives: Boolean = false,
        baseOffset: Int = buffer.position(),
    ): DecodedVertices {
        require(count >= 0) { "顶点数不能为负" }
        require(!withDerivatives || (side != null && forward != null)) { "解码导数需要 side/forward 的解包参数" }
        require(baseOffset >= 0 && buffer.limit().toLong() - baseOffset >= count.toLong() * layout.stride) {
            "顶点缓冲长度不足"
        }

        val x = FloatArray(count)
        val y = FloatArray(count)
        val opacity = FloatArray(count)
        val hcl = if (layout.hasHcl) FloatArray(count * 3) else null
        val u = if (layout.hasSurfaceUv) FloatArray(count) else null
        val v = if (layout.hasSurfaceUv) FloatArray(count) else null
        val anim = if (layout.hasSurfaceUv) FloatArray(count) else null
        val sideOut = if (withDerivatives) FloatArray(count * 3) else null
        val forwardOut = if (withDerivatives) FloatArray(count * 3) else null

        for (i in 0 until count) {
            val o = baseOffset + i * layout.stride
            val p = o + layout.positionOffset
            val b0 = buffer.get(p).toInt() and 0xFF
            val b1 = buffer.get(p + 1).toInt() and 0xFF
            val b2 = buffer.get(p + 2).toInt() and 0xFF
            x[i] = position.x.scale * xy12X(b0, b1) + position.x.offset
            y[i] = position.y.scale * xy12Y(b1, b2) + position.y.offset
            opacity[i] = unpackOpacityShift(buffer.get(o + layout.opacityOffset).toInt() and 0xFF)

            if (hcl != null) {
                val h = o + layout.hclOffset
                unpackHcl(
                    buffer.get(h).toInt() and 0xFF,
                    buffer.get(h + 1).toInt() and 0xFF,
                    buffer.get(h + 2).toInt() and 0xFF,
                    buffer.get(h + 3).toInt() and 0xFF,
                    hcl, i * 3,
                )
            }
            if (u != null && v != null && anim != null) {
                val s = o + layout.surfaceUvOffset
                val c0 = buffer.get(s).toInt() and 0xFF
                val c1 = buffer.get(s + 1).toInt() and 0xFF
                val c2 = buffer.get(s + 2).toInt() and 0xFF
                u[i] = xy12X(c0, c1) / UV_DIVISOR
                v[i] = xy12Y(c1, c2) / UV_DIVISOR
                anim[i] = unpackAnimationOffset(buffer.get(o + layout.animationOffset).toInt() and 0xFF)
            }
            if (sideOut != null && forwardOut != null && side != null && forward != null) {
                decodeDerivative(buffer, o + layout.sideOffset, side, sideOut, i * 3)
                decodeDerivative(buffer, o + layout.forwardOffset, forward, forwardOut, i * 3)
            }
        }
        return DecodedVertices(count, x, y, opacity, hcl, u, v, anim, sideOut, forwardOut)
    }

    /**
     * 解码 unpacked 顶点缓冲（`InProgressStroke` 的 FullMeshFormat，步长 [UnpackedLayout.STRIDE]）。
     * float 按 [order] 读取，默认原生字节序——官方返回的只读缓冲是 BIG_ENDIAN，直接读 float 会错，
     * 这里在副本上设置字节序，不改动传入缓冲的状态。
     */
    fun decodeUnpacked(
        buffer: ByteBuffer,
        count: Int,
        withDerivatives: Boolean = false,
        order: ByteOrder = ByteOrder.nativeOrder(),
        baseOffset: Int = buffer.position(),
    ): DecodedVertices {
        require(count >= 0) { "顶点数不能为负" }
        require(baseOffset >= 0 && buffer.limit().toLong() - baseOffset >= count.toLong() * UnpackedLayout.STRIDE) {
            "顶点缓冲长度不足"
        }
        val buf = buffer.duplicate().order(order)

        val x = FloatArray(count)
        val y = FloatArray(count)
        val opacity = FloatArray(count)
        val hcl = FloatArray(count * 3)
        val u = FloatArray(count)
        val v = FloatArray(count)
        val anim = FloatArray(count)
        val sideOut = if (withDerivatives) FloatArray(count * 3) else null
        val forwardOut = if (withDerivatives) FloatArray(count * 3) else null

        for (i in 0 until count) {
            val o = baseOffset + i * UnpackedLayout.STRIDE
            x[i] = buf.getFloat(o + UnpackedLayout.POSITION)
            y[i] = buf.getFloat(o + UnpackedLayout.POSITION + 4)
            opacity[i] = buf.getFloat(o + UnpackedLayout.OPACITY)
            for (k in 0 until 3) hcl[i * 3 + k] = buf.getFloat(o + UnpackedLayout.HCL + 4 * k)
            u[i] = buf.getFloat(o + UnpackedLayout.SURFACE_UV)
            v[i] = buf.getFloat(o + UnpackedLayout.SURFACE_UV + 4)
            anim[i] = buf.getFloat(o + UnpackedLayout.ANIMATION)
            if (sideOut != null && forwardOut != null) {
                sideOut[i * 3] = buf.getFloat(o + UnpackedLayout.SIDE)
                sideOut[i * 3 + 1] = buf.getFloat(o + UnpackedLayout.SIDE + 4)
                sideOut[i * 3 + 2] = buf.getFloat(o + UnpackedLayout.SIDE_LABEL)
                forwardOut[i * 3] = buf.getFloat(o + UnpackedLayout.FORWARD)
                forwardOut[i * 3 + 1] = buf.getFloat(o + UnpackedLayout.FORWARD + 4)
                forwardOut[i * 3 + 2] = buf.getFloat(o + UnpackedLayout.FORWARD_LABEL)
            }
        }
        return DecodedVertices(count, x, y, opacity, hcl, u, v, anim, sideOut, forwardOut)
    }

    private fun decodeDerivative(buffer: ByteBuffer, at: Int, p: AttrUnpack, out: FloatArray, outIndex: Int) {
        val b0 = buffer.get(at).toInt() and 0xFF
        val b1 = buffer.get(at + 1).toInt() and 0xFF
        val b2 = buffer.get(at + 2).toInt() and 0xFF
        out[outIndex] = p.x.scale * xy12X(b0, b1) + p.x.offset
        out[outIndex + 1] = p.y.scale * xy12Y(b1, b2) + p.y.offset
        out[outIndex + 2] = unpackLabel(buffer.get(at + 3).toInt() and 0xFF)
    }

    /** XY12 的 x：`(b0 << 4) | (b1 >> 4)`，0..4095。 */
    fun xy12X(b0: Int, b1: Int): Int = (b0 shl 4) or (b1 ushr 4)

    /** XY12 的 y：`((b1 & 15) << 8) | b2`，0..4095。 */
    fun xy12Y(b1: Int, b2: Int): Int = ((b1 and 0x0F) shl 8) or b2

    /** 不透明度偏移：`b / 127 - 1`，范围 [-1, 1]。 */
    fun unpackOpacityShift(b: Int): Float = b / 127f - 1f

    /** 顶点 label：`b - 128`，范围 [-128, 127]（有效值 -127..127）。 */
    fun unpackLabel(b: Int): Float = b - 128f

    /** 粒子动画偏移：`b / 255`，范围 [0, 1]。 */
    fun unpackAnimationOffset(b: Int): Float = b / 255f

    /**
     * HCL XYZ10 的三个 10 位原始整数（0..1023），写入 [out] 的 [at] 起 3 项。
     * 位布局：`x = b0<<2 | b1>>6`，`y = (b1&63)<<4 | b2>>4`，`z = (b2&15)<<6 | b3>>2`（b3 低 2 位恒为 0）。
     */
    fun unpackHclRaw(b0: Int, b1: Int, b2: Int, b3: Int, out: IntArray, at: Int = 0) {
        out[at] = (b0 shl 2) or (b1 ushr 6)
        out[at + 1] = ((b1 and 0x3F) shl 4) or (b2 ushr 4)
        out[at + 2] = ((b2 and 0x0F) shl 6) or (b3 ushr 2)
    }

    /** HCL XYZ10 解包为 `raw/511 - 1`；着色器里 z 用 `b3/4`（不取整），与此一致因为 b3 低 2 位为 0。 */
    private fun unpackHcl(b0: Int, b1: Int, b2: Int, b3: Int, out: FloatArray, at: Int) {
        out[at] = ((b0 shl 2) or (b1 ushr 6)) / HCL_DIVISOR - 1f
        out[at + 1] = (((b1 and 0x3F) shl 4) or (b2 ushr 4)) / HCL_DIVISOR - 1f
        out[at + 2] = (((b2 and 0x0F) shl 6) + b3 / 4f) / HCL_DIVISOR - 1f
    }
}
