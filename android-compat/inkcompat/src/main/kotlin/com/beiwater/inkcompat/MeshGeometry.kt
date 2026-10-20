package com.beiwater.inkcompat

import java.nio.ByteBuffer
import java.nio.ShortBuffer

/**
 * 一个 Mesh（或湿笔的一个分区）解码后的几何，已整理成 `drawVertices` 能直接用的数组。
 *
 * @property positions 每顶点 (x, y)，长度 `2 * vertexCount`，笔画坐标系
 * @property indices 三角形索引，无符号 16 位（存成 Short，长度是 3 的倍数）
 * @property shading 只保留着色要用的列（不透明度、HCL、表面 UV）；x/y 列为空数组，位置看 [positions]
 */
class MeshGeometry(
    val vertexCount: Int,
    val positions: FloatArray,
    val indices: ShortArray,
    val shading: DecodedVertices,
) {
    val hasSurfaceUv: Boolean get() = shading.u != null && shading.v != null

    /** `Canvas.drawVertices` 的 `vertexCount` 参数：float 个数，不是顶点数。 */
    val vertexFloatCount: Int get() = vertexCount * 2

    companion object {
        /** 顶点不足一个三角形或没有索引：合法的“无需绘制”。 */
        val EMPTY = MeshGeometry(
            0, FloatArray(0), ShortArray(0),
            DecodedVertices(0, FloatArray(0), FloatArray(0), FloatArray(0), null, null, null, null, null, null),
        )
    }
}

/** 把官方原始缓冲解码为 [MeshGeometry]。纯 JDK，输入全是普通数据，失败一律返回 null（调用方回退官方）。 */
object MeshGeometryBuilder {
    private val NO_FLOATS = FloatArray(0)

    /**
     * 完成笔画（`Stroke.shape`）的一个 Mesh。按 [attributeCount] 与 [vertexStride] 推断 packed 布局；
     * 步长恰为 [UnpackedLayout.STRIDE] 时按 unpacked 处理（容错，当前版本的 Stroke 网格不会是这种）。
     *
     * @param unpackParams `Mesh.vertexAttributeUnpackingParams`，每个属性一组 (offset, scale) 分量
     */
    fun fromStrokeMesh(
        vertexBuffer: ByteBuffer,
        indexBuffer: ShortBuffer,
        vertexCount: Int,
        attributeCount: Int,
        vertexStride: Int,
        unpackParams: List<List<AxisParams>>,
    ): MeshGeometry? {
        if (vertexCount < 3) return MeshGeometry.EMPTY
        val layout = PackedLayout.infer(attributeCount, vertexStride)
        if (layout == null) {
            return if (vertexStride == UnpackedLayout.STRIDE) fromUnpacked(vertexBuffer, indexBuffer, vertexCount) else null
        }
        val pos = unpackAt(unpackParams, layout.positionParamIndex) ?: return null
        val indices = readIndices(indexBuffer, vertexCount) ?: return null
        if (indices.isEmpty()) return MeshGeometry.EMPTY
        if (vertexBuffer.limit().toLong() - vertexBuffer.position() < vertexCount.toLong() * layout.stride) return null
        val d = VertexDecoder.decodePacked(vertexBuffer, vertexCount, layout, pos)
        return assemble(d, indices)
    }

    /** 湿笔（`InProgressStroke`）的一个分区：unpacked float 顶点，字节序按原生。 */
    fun fromUnpacked(vertexBuffer: ByteBuffer, indexBuffer: ShortBuffer, vertexCount: Int): MeshGeometry? {
        if (vertexCount < 3) return MeshGeometry.EMPTY
        val indices = readIndices(indexBuffer, vertexCount) ?: return null
        if (indices.isEmpty()) return MeshGeometry.EMPTY
        if (vertexBuffer.limit().toLong() - vertexBuffer.position() < vertexCount.toLong() * UnpackedLayout.STRIDE) return null
        val d = VertexDecoder.decodeUnpacked(vertexBuffer, vertexCount)
        return assemble(d, indices)
    }

    /**
     * 读索引并校验：个数是 3 的倍数、每个值（按无符号 16 位）小于 [vertexCount]。
     * 不合法返回 null；个数为 0 返回空数组。不改动传入缓冲的位置。
     */
    fun readIndices(buffer: ShortBuffer, vertexCount: Int): ShortArray? {
        val dup = buffer.duplicate()
        val n = dup.remaining()
        if (n % 3 != 0) return null
        val out = ShortArray(n)
        dup.get(out)
        for (s in out) if ((s.toInt() and 0xFFFF) >= vertexCount) return null
        return out
    }

    private fun unpackAt(params: List<List<AxisParams>>, index: Int): AttrUnpack? {
        val comps = params.getOrNull(index) ?: return null
        if (comps.size < 2) return null
        return AttrUnpack(comps[0], comps[1])
    }

    private fun assemble(d: DecodedVertices, indices: ShortArray): MeshGeometry {
        val n = d.count
        val positions = FloatArray(n * 2)
        for (i in 0 until n) {
            positions[i * 2] = d.x[i]
            positions[i * 2 + 1] = d.y[i]
        }
        // HCL 全为 0（无色相/色度/明度偏移）时丢掉，颜色走只改 alpha 的快速路径
        val hcl = d.hcl?.takeIf { arr -> arr.any { it != 0f } }
        // 动画偏移列不保留：只支持 frames == 1，帧号恒为 0
        val shading = DecodedVertices(n, NO_FLOATS, NO_FLOATS, d.opacityShift, hcl, d.u, d.v, null, null, null)
        return MeshGeometry(n, positions, indices, shading)
    }
}
