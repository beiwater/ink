package com.beiwater.inkcompat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 测试用编码器：照抄 C++ `ink/geometry/internal/mesh_packing.cc` 的打包位布局与
 * `stroke_vertex.cc` 的编码参数，用来和 [VertexDecoder] 对拍。
 */
object TestEncoder {
    /** 与 C++ `PackQuantizedFloat2PackedInThreeUnsignedBytes_XY12` 相同。 */
    fun packXy12(x12: Int, y12: Int, out: ByteArray, at: Int) {
        out[at] = (x12 shr 4).toByte()
        out[at + 1] = (((x12 and 0xF) shl 4) + (y12 shr 8)).toByte()
        out[at + 2] = (y12 and 0xFF).toByte()
    }

    /** 与 C++ `PackQuantizedFloat3PackedInFourUnsignedBytes_XYZ10` 相同。 */
    fun packXyz10(x: Int, y: Int, z: Int, out: ByteArray, at: Int) {
        out[at] = (x shr 2).toByte()
        out[at + 1] = (((x and 0x03) shl 6) + (y shr 4)).toByte()
        out[at + 2] = (((y and 0x0F) shl 4) + (z shr 6)).toByte()
        out[at + 3] = ((z and 0x3F) shl 2).toByte()
    }

    fun quantize(value: Float, offset: Float, scale: Float, maxRaw: Int): Int =
        ((value - offset) / scale).roundToInt().coerceIn(0, maxRaw)

    /** 一个顶点的逻辑值（解码后的目标值）。 */
    class Vertex(
        val x: Float, val y: Float, val opacityShift: Float,
        val hcl: FloatArray? = null,
        val sideDx: Float = 0f, val sideDy: Float = 0f, val sideLabel: Int = 0,
        val fwdDx: Float = 0f, val fwdDy: Float = 0f, val fwdLabel: Int = 0,
        val u: Float = 0f, val v: Float = 0f, val anim: Float = 0f,
    )

    /** 按 [layout] 打包一组顶点；坐标与导数用给定的解包参数反向量化。 */
    fun packVertices(
        vertices: List<Vertex>, layout: PackedLayout,
        pos: AttrUnpack, side: AttrUnpack, fwd: AttrUnpack,
    ): ByteBuffer {
        val bytes = ByteArray(vertices.size * layout.stride)
        for ((i, vx) in vertices.withIndex()) {
            val o = i * layout.stride
            packXy12(
                quantize(vx.x, pos.x.offset, pos.x.scale, 4095),
                quantize(vx.y, pos.y.offset, pos.y.scale, 4095), bytes, o + layout.positionOffset,
            )
            bytes[o + layout.opacityOffset] = quantize(vx.opacityShift, -1f, 2f / 254f, 254).toByte()
            if (layout.hasHcl) {
                val h = vx.hcl!!
                packXyz10(
                    quantize(h[0], -1f, 2f / 1022f, 1022),
                    quantize(h[1], -1f, 2f / 1022f, 1022),
                    quantize(h[2], -1f, 2f / 1022f, 1022), bytes, o + layout.hclOffset,
                )
            }
            packXy12(
                quantize(vx.sideDx, side.x.offset, side.x.scale, 4095),
                quantize(vx.sideDy, side.y.offset, side.y.scale, 4095), bytes, o + layout.sideOffset,
            )
            bytes[o + layout.sideLabelOffset] = (vx.sideLabel + 128).toByte()
            packXy12(
                quantize(vx.fwdDx, fwd.x.offset, fwd.x.scale, 4095),
                quantize(vx.fwdDy, fwd.y.offset, fwd.y.scale, 4095), bytes, o + layout.forwardOffset,
            )
            bytes[o + layout.forwardLabelOffset] = (vx.fwdLabel + 128).toByte()
            if (layout.hasSurfaceUv) {
                packXy12(
                    quantize(vx.u, 0f, 1f / 4095f, 4095), quantize(vx.v, 0f, 1f / 4095f, 4095),
                    bytes, o + layout.surfaceUvOffset,
                )
                bytes[o + layout.animationOffset] = quantize(vx.anim, 0f, 1f / 255f, 255).toByte()
            }
        }
        // 模拟官方：只读视图，字节序为 Java 默认的 BIG_ENDIAN
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer()
    }

    /** 按 [UnpackedLayout] 写 unpacked 顶点，float 用 [order] 字节序。 */
    fun unpackedVertices(vertices: List<Vertex>, order: ByteOrder): ByteBuffer {
        val buf = ByteBuffer.allocate(vertices.size * UnpackedLayout.STRIDE).order(order)
        for ((i, vx) in vertices.withIndex()) {
            val o = i * UnpackedLayout.STRIDE
            buf.putFloat(o + UnpackedLayout.POSITION, vx.x)
            buf.putFloat(o + UnpackedLayout.POSITION + 4, vx.y)
            buf.putFloat(o + UnpackedLayout.OPACITY, vx.opacityShift)
            val h = vx.hcl ?: floatArrayOf(0f, 0f, 0f)
            for (k in 0 until 3) buf.putFloat(o + UnpackedLayout.HCL + 4 * k, h[k])
            buf.putFloat(o + UnpackedLayout.SIDE, vx.sideDx)
            buf.putFloat(o + UnpackedLayout.SIDE + 4, vx.sideDy)
            buf.putFloat(o + UnpackedLayout.SIDE_LABEL, vx.sideLabel.toFloat())
            buf.putFloat(o + UnpackedLayout.FORWARD, vx.fwdDx)
            buf.putFloat(o + UnpackedLayout.FORWARD + 4, vx.fwdDy)
            buf.putFloat(o + UnpackedLayout.FORWARD_LABEL, vx.fwdLabel.toFloat())
            buf.putFloat(o + UnpackedLayout.SURFACE_UV, vx.u)
            buf.putFloat(o + UnpackedLayout.SURFACE_UV + 4, vx.v)
            buf.putFloat(o + UnpackedLayout.ANIMATION, vx.anim)
        }
        // asReadOnlyBuffer 返回 BIG_ENDIAN 视图，与官方 getRawVertexBuffer 的状态一致
        return buf.asReadOnlyBuffer()
    }
}
