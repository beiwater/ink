package com.beiwater.inkcompat

/** `BrushPaint.selfOverlap` 的纯数据镜像；无法识别的取值记为 [UNKNOWN]。 */
enum class OverlapKind { ANY, ACCUMULATE, DISCARD, UNKNOWN }

/** `BrushPaint` 第一个纹理层的种类（官方只按第一层决定纹理映射方式）。 */
enum class TextureKind { NONE, TILING, STAMPING, UNKNOWN }

/** 印章纹理参数：[textureId] 是 `TextureBitmapStore` 的键；[frames]、[rows]、[columns] 对应动画网格。 */
data class StampSpec(
    val textureId: String,
    val frames: Int,
    val rows: Int,
    val columns: Int,
    val reverse: Boolean,
)

/**
 * 从 `BrushPaint` 提取出来、判定用的纯数据特征（native 对象只在 [PaintFeaturesReader] 里碰）。
 *
 * @property firstLayerModulate 第一个纹理层的混合模式是否为 MODULATE（drawVertices 只能表达这一种）
 * @property colorFunctions 全部可识别的颜色函数；含无法识别的子类时为 null
 */
data class PaintFeatures(
    val selfOverlap: OverlapKind,
    val firstLayer: TextureKind,
    val layerCount: Int,
    val firstLayerModulate: Boolean,
    val stamp: StampSpec?,
    val colorFunctions: List<InkColorFunction>?,
)

/** 一个 paint 偏好由谁来画。 */
enum class PaintRoute {
    /** 兼容渲染器（drawVertices）。 */
    COMPAT,

    /** 官方渲染器：API<34 上是路径渲染器。 */
    OFFICIAL,

    /** 两边都画不了；交给官方（它会跳过并打一条日志）。 */
    NONE,
}

/** 一个 coat 的选路结果：[paintIndex] 是选中的 `paintPreferences` 下标，[PaintRoute.NONE] 时为 -1。 */
data class CoatRoute(val route: PaintRoute, val paintIndex: Int)

/**
 * 判定哪些笔刷要走兼容渲染器。全部是纯函数，输入是 [PaintFeatures]，可在 JVM 单测里覆盖。
 *
 * 规则是照官方 `CanvasStrokeUnifiedRenderer.drawCoat` 在 API<34 的行为反推的：
 * 只有路径渲染器时，它收 `selfOverlap ∈ {ANY, DISCARD}` 且纹理映射为 TILING（无纹理层也算）的 paint；
 * 兼容渲染器只补它拒绝、而网格渲染器会收的那一类：印章纹理 + `selfOverlap ∈ {ANY, ACCUMULATE}`。
 */
object CompatDecision {
    /** 官方网格渲染器可用的最低 API 级别（`android.graphics.Mesh`）。 */
    const val MESH_MIN_SDK = 34

    /** 官方路径渲染器会不会收这个 paint（对应 `CanvasPathRenderer.canDraw` 的 paint 条件）。 */
    fun officialPathAccepts(p: PaintFeatures): Boolean =
        (p.selfOverlap == OverlapKind.ANY || p.selfOverlap == OverlapKind.DISCARD) &&
            (p.firstLayer == TextureKind.NONE || p.firstLayer == TextureKind.TILING)

    /**
     * 兼容渲染器能不能画这个 paint。限制：单个纹理层、MODULATE、动画帧数为 1（动画 UV 需要时钟，暂不实现）、
     * 颜色函数全部可识别。
     */
    fun compatAccepts(p: PaintFeatures): Boolean {
        val stamp = p.stamp ?: return false
        return p.firstLayer == TextureKind.STAMPING &&
            p.layerCount == 1 &&
            (p.selfOverlap == OverlapKind.ANY || p.selfOverlap == OverlapKind.ACCUMULATE) &&
            p.firstLayerModulate &&
            stamp.frames == 1 &&
            stamp.rows >= 1 && stamp.columns >= 1 &&
            p.colorFunctions != null
    }

    fun routeOf(p: PaintFeatures): PaintRoute = when {
        officialPathAccepts(p) -> PaintRoute.OFFICIAL
        compatAccepts(p) -> PaintRoute.COMPAT
        else -> PaintRoute.NONE
    }

    /** 与官方一致：按 `paintPreferences` 顺序，取第一个有人能画的。 */
    fun routeCoat(preferences: List<PaintFeatures>): CoatRoute {
        for (i in preferences.indices) {
            val route = routeOf(preferences[i])
            if (route != PaintRoute.NONE) return CoatRoute(route, i)
        }
        return CoatRoute(PaintRoute.NONE, -1)
    }

    fun routeStroke(coats: List<List<PaintFeatures>>): List<CoatRoute> = coats.map(::routeCoat)

    /**
     * 这条笔画（的任何一个 coat）是否需要兼容渲染器。
     * `sdkInt >= 34` 恒为 false：官方网格渲染器能画，一律委托官方。
     */
    fun needsCompat(sdkInt: Int, coats: List<List<PaintFeatures>>): Boolean =
        sdkInt < MESH_MIN_SDK && routeStroke(coats).any { it.route == PaintRoute.COMPAT }

    /** [needsCompat] 的无 SDK 门版本，供“强制兼容”（设备对拍）使用。 */
    fun hasCompatCoat(routes: List<CoatRoute>): Boolean = routes.any { it.route == PaintRoute.COMPAT }

    /** 所有 coat 都由兼容渲染器画，不需要再调用官方。 */
    fun allCompat(routes: List<CoatRoute>): Boolean =
        routes.isNotEmpty() && routes.all { it.route == PaintRoute.COMPAT }
}
