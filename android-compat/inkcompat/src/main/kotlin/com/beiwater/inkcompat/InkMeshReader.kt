package com.beiwater.inkcompat

import android.annotation.SuppressLint
import android.util.Log
import androidx.ink.geometry.Mesh
import androidx.ink.geometry.getRawTriangleIndexBuffer
import androidx.ink.geometry.getRawVertexBuffer
import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.getRawTriangleIndexBuffer
import androidx.ink.strokes.getRawVertexBuffer

/**
 * 从官方 `Mesh` / `InProgressStroke` 取原始缓冲并解码为 [MeshGeometry] 的桥接层。
 *
 * 用到的 `getRaw*Buffer`、`vertexAttributeUnpackingParams` 都是 `@InkInternalOnlyApi`（ERROR 级 opt-in）
 * 且 `@RestrictTo`，alpha 版本之间可能变动，所以全部收在这个文件里。
 * 读取出任何问题都返回 null（调用方回退官方渲染器），日志只写异常类名，不带笔迹内容。
 */
@OptIn(InkInternalOnlyApi::class)
@SuppressLint("RestrictedApi")
internal object InkMeshReader {
    private const val TAG = "InkCompat"

    /** 完成笔画的一个 Mesh（packed）。 */
    fun readStrokeMesh(mesh: Mesh): MeshGeometry? = try {
        val params = mesh.vertexAttributeUnpackingParams.map { p ->
            p.components.map { AxisParams(it.offset, it.scale) }
        }
        MeshGeometryBuilder.fromStrokeMesh(
            // 官方返回的只读缓冲是 BIG_ENDIAN；packed 解码只做按字节绝对读取，字节序无关
            vertexBuffer = mesh.getRawVertexBuffer(),
            indexBuffer = mesh.getRawTriangleIndexBuffer(),
            vertexCount = mesh.vertexCount,
            attributeCount = mesh.format.attributeCount(),
            vertexStride = mesh.vertexStride,
            unpackParams = params,
        )
    } catch (e: RuntimeException) {
        Log.w(TAG, "读取笔画网格失败：${e.javaClass.simpleName}")
        null
    }

    /** 湿笔某个 coat 的一个分区（unpacked float）。 */
    fun readInProgressPartition(stroke: InProgressStroke, coatIndex: Int, partitionIndex: Int): MeshGeometry? = try {
        MeshGeometryBuilder.fromUnpacked(
            vertexBuffer = stroke.getRawVertexBuffer(coatIndex, partitionIndex),
            indexBuffer = stroke.getRawTriangleIndexBuffer(coatIndex, partitionIndex),
            vertexCount = stroke.getVertexCount(coatIndex, partitionIndex),
        )
    } catch (e: RuntimeException) {
        Log.w(TAG, "读取湿笔网格失败：${e.javaClass.simpleName}")
        null
    }

    /** 湿笔某个 coat 的全部分区；任一分区读取失败则整体为 null。 */
    fun readInProgressCoat(stroke: InProgressStroke, coatIndex: Int): List<MeshGeometry>? = try {
        val count = stroke.getMeshPartitionCount(coatIndex)
        val out = ArrayList<MeshGeometry>(count)
        for (p in 0 until count) out += readInProgressPartition(stroke, coatIndex, p) ?: return null
        out
    } catch (e: RuntimeException) {
        Log.w(TAG, "读取湿笔分区失败：${e.javaClass.simpleName}")
        null
    }

    fun version(stroke: InProgressStroke): Long = stroke.getVersion()
}
