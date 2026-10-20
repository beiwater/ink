package com.beiwater.inkcompat

import java.util.WeakHashMap

/** 顶点颜色的缓存键：笔刷颜色（sRGB ARGB）加 paint 的颜色函数。 */
data class ColorKey(val argb: Int, val functions: List<InkColorFunction>)

/**
 * 纹理坐标的缓存键。[width]、[height] 是印章位图的像素尺寸：这里直接给 `drawVertices` 纹素坐标
 * （UV 乘以位图尺寸），不给 BitmapShader 设局部矩阵——待设备对拍。
 */
data class TexKey(val rows: Int, val columns: Int, val width: Int, val height: Int)

/**
 * 一个 Mesh 的几何加上两份派生数组：纹理坐标（随印章位图/网格变化）和顶点颜色（随笔刷色变化）。
 * 派生数组各缓存最近一次的键，键不变就复用，所以拖动、缩放、重绘时不会重复计算。
 * 数组由本对象持有，调用方只读、不要修改。
 */
class MeshEntry(val geometry: MeshGeometry) {
    private var colorKey: ColorKey? = null
    private var colors: IntArray? = null
    private var texKey: TexKey? = null
    private var texCoords: FloatArray? = null

    /** 重算次数，供单测断言缓存命中。 */
    @Volatile internal var colorComputations = 0
        private set
    @Volatile internal var texComputations = 0
        private set

    @Synchronized
    fun colors(key: ColorKey): IntArray {
        val cached = colors
        if (cached != null && key == colorKey) return cached
        val out = cached ?: IntArray(geometry.vertexCount)
        ColorMath.fillVertexColors(BrushColor.fromArgb(key.argb, key.functions), geometry.shading, out)
        colors = out
        colorKey = key
        colorComputations++
        return out
    }

    /** 前提：[MeshGeometry.hasSurfaceUv]，否则抛 [IllegalArgumentException]。 */
    @Synchronized
    fun texCoords(key: TexKey): FloatArray {
        val cached = texCoords
        if (cached != null && key == texKey) return cached
        val out = cached ?: FloatArray(geometry.vertexCount * 2)
        // frames == 1：动画进度与偏移都不影响帧号，传 0 即可
        StampUv.fillTexCoords(
            geometry.shading, 0f, 1, key.rows, key.columns, false, out,
            scaleX = key.width.toFloat(), scaleY = key.height.toFloat(),
        )
        texCoords = out
        texKey = key
        texComputations++
        return out
    }
}

/**
 * 以弱引用 [K]（Mesh）为键的 [MeshEntry] 缓存。读取失败（[create] 返回 null）也会记住，避免每帧重试解码。
 * 值不得强引用键，否则永远回收不掉。
 */
class WeakEntryCache<K : Any> {
    private class Slot(val entry: MeshEntry?)

    private val map = WeakHashMap<K, Slot>()

    @Synchronized
    fun getOrPut(key: K, create: () -> MeshGeometry?): MeshEntry? {
        map[key]?.let { return it.entry }
        val entry = create()?.let(::MeshEntry)
        map[key] = Slot(entry)
        return entry
    }

    @Synchronized
    fun size(): Int = map.size
}

/**
 * 按键（湿笔）和版本号缓存整体结果：版本或者“上下文”（例如笔刷）变了就整体重建，与官方
 * `obtainInProgressMeshData` 的做法一致。
 */
class VersionedCache<K : Any, V : Any> {
    private class Slot<V>(val version: Long, val context: Any?, val value: V)

    private val map = WeakHashMap<K, Slot<V>>()

    /** [context] 用引用相等比较（如笔刷对象）。 */
    @Synchronized
    fun get(key: K, version: Long, context: Any?, build: () -> V): V {
        val slot = map[key]
        if (slot != null && slot.version == version && slot.context === context) return slot.value
        val value = build()
        map[key] = Slot(version, context, value)
        return value
    }
}
