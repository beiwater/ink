package com.beiwater.inkcompat

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.Log
import androidx.ink.brush.Brush
import androidx.ink.brush.ExperimentalInkAnimationApi
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.populateMatrix
import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke

/**
 * Android 13 兼容的 [CanvasStrokeRenderer]：官方网格渲染器要 `android.graphics.Mesh`（API 34），
 * 这里对“印章纹理 + `SelfOverlap.ANY/ACCUMULATE`”的笔刷（铅笔 v5）改用 `Canvas.drawVertices` 画；
 * 其余一律委托官方渲染器。
 *
 * 选路：
 * - `SDK_INT >= 34` 恒委托官方（除非 [create] 的 `alwaysCompat` 为 true，仅供设备对拍）。
 * - 否则逐 coat 判定（[CompatDecision]）。没有 coat 需要兼容、或者读取网格/取不到印章位图等任何环节失败，
 *   整条笔画委托官方，不抛异常。
 * - 混合笔画（部分 coat 需要兼容）：兼容 coat 先画，随后官方画其余 coat，所以兼容 coat 的层级会在其他 coat 之上；
 *   官方每次还会为被它跳过的 coat 打一条 Log.i。当前墨记没有这种笔刷。
 *
 * 与官方相同：[strokeToScreenTransform] 不会被应用到 [Canvas]，调用方负责 `canvas` 上已有的变换；
 * 兼容路径不做边缘外扩/抗锯齿，因此不需要这个矩阵。
 *
 * 线程：与 Canvas 绘制所在线程一致即可；内部缓存已加同步。
 */
@OptIn(ExperimentalInkAnimationApi::class, InkInternalOnlyApi::class)
@SuppressLint("RestrictedApi")
class CompatStrokeRenderer private constructor(
    private val delegate: CanvasStrokeRenderer,
    private val textureStore: TextureBitmapStore,
    private val sdkInt: Int,
    private val alwaysCompat: Boolean,
) : CanvasStrokeRenderer {

    private val strokeMeshes = WeakEntryCache<androidx.ink.geometry.Mesh>()
    private val wetMeshes = VersionedCache<InProgressStroke, List<List<MeshEntry>?>>()
    private val scratchMatrix = Matrix()
    private val warned = HashSet<String>()

    /** 一个待绘制的 coat：网格条目、印章 Paint 以及两个缓存键。 */
    private class CoatJob(
        val entries: List<MeshEntry>,
        val paint: Paint,
        val texKey: TexKey,
        val colorKey: ColorKey,
    )

    private fun enabled(): Boolean = alwaysCompat || sdkInt < CompatDecision.MESH_MIN_SDK

    // ---- Stroke ----

    override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: AffineTransform) {
        if (!drawStrokeCompat(canvas, stroke)) delegate.draw(canvas, stroke, strokeToScreenTransform)
    }

    override fun draw(
        canvas: Canvas, stroke: Stroke, strokeToScreenTransform: AffineTransform, animatorClockStateMillis: Long,
    ) {
        if (!drawStrokeCompat(canvas, stroke)) {
            delegate.draw(canvas, stroke, strokeToScreenTransform, animatorClockStateMillis)
        }
    }

    override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: Matrix) {
        if (!drawStrokeCompat(canvas, stroke)) delegate.draw(canvas, stroke, strokeToScreenTransform)
    }

    override fun draw(
        canvas: Canvas, stroke: Stroke, strokeToScreenTransform: Matrix, animatorClockStateMillis: Long,
    ) {
        if (!drawStrokeCompat(canvas, stroke)) {
            delegate.draw(canvas, stroke, strokeToScreenTransform, animatorClockStateMillis)
        }
    }

    // ---- InProgressStroke（湿笔） ----

    override fun draw(canvas: Canvas, inProgressStroke: InProgressStroke, strokeToScreenTransform: AffineTransform) {
        if (!drawWetCompat(canvas, inProgressStroke)) delegate.draw(canvas, inProgressStroke, strokeToScreenTransform)
    }

    override fun draw(
        canvas: Canvas, inProgressStroke: InProgressStroke, strokeToScreenTransform: AffineTransform,
        animatorClockStateMillis: Long,
    ) {
        if (!drawWetCompat(canvas, inProgressStroke)) {
            delegate.draw(canvas, inProgressStroke, strokeToScreenTransform, animatorClockStateMillis)
        }
    }

    override fun draw(canvas: Canvas, inProgressStroke: InProgressStroke, strokeToScreenTransform: Matrix) {
        if (!drawWetCompat(canvas, inProgressStroke)) delegate.draw(canvas, inProgressStroke, strokeToScreenTransform)
    }

    override fun draw(
        canvas: Canvas, inProgressStroke: InProgressStroke, strokeToScreenTransform: Matrix,
        animatorClockStateMillis: Long,
    ) {
        if (!drawWetCompat(canvas, inProgressStroke)) {
            delegate.draw(canvas, inProgressStroke, strokeToScreenTransform, animatorClockStateMillis)
        }
    }

    // ---- 兼容路径 ----

    /**
     * 返回 true 表示整条笔画已由兼容路径画完，不必再委托官方；
     * 返回 false 表示没画（或只画了其中的兼容 coat），调用方要委托官方。
     */
    private fun drawStrokeCompat(canvas: Canvas, stroke: Stroke): Boolean {
        if (!enabled()) return false
        return try {
            val brush = stroke.brush
            val coats = PaintFeaturesReader.readCoats(brush)
            val routes = CompatDecision.routeStroke(coats)
            if (!CompatDecision.hasCompatCoat(routes)) return false
            val shape = stroke.shape
            val jobs = buildJobs(brush, coats, routes) { coat ->
                shape.renderGroupMeshes(coat).map { mesh ->
                    strokeMeshes.getOrPut(mesh) { InkMeshReader.readStrokeMesh(mesh) } ?: return@buildJobs null
                }
            } ?: return false
            drawJobs(canvas, jobs)
            CompatDecision.allCompat(routes)
        } catch (e: RuntimeException) {
            warnOnce("stroke:${e.javaClass.simpleName}", "兼容绘制失败，回退官方渲染器")
            false
        }
    }

    private fun drawWetCompat(canvas: Canvas, wet: InProgressStroke): Boolean {
        if (!enabled()) return false
        return try {
            val brush = wet.brush ?: return false
            val coats = PaintFeaturesReader.readCoats(brush)
            val routes = CompatDecision.routeStroke(coats)
            if (!CompatDecision.hasCompatCoat(routes)) return false
            // 湿笔按 version 整体重取；笔刷对象变了（InProgressStroke 复用）也重取
            val perCoat = wetMeshes.get(wet, InkMeshReader.version(wet), brush) {
                List(wet.getBrushCoatCount()) { coat ->
                    if (routes.getOrNull(coat)?.route != PaintRoute.COMPAT) null
                    else InkMeshReader.readInProgressCoat(wet, coat)?.map(::MeshEntry)
                }
            }
            val jobs = buildJobs(brush, coats, routes) { coat -> perCoat.getOrNull(coat) } ?: return false
            drawJobs(canvas, jobs)
            CompatDecision.allCompat(routes)
        } catch (e: RuntimeException) {
            warnOnce("wet:${e.javaClass.simpleName}", "兼容绘制湿笔失败，回退官方渲染器")
            false
        }
    }

    /**
     * 为所有兼容 coat 准备绘制任务；任何一个 coat 准备不了（读不到网格、缺印章位图、缺 UV）就返回 null，
     * 此时一个 coat 也不画，整条笔画交给官方。[entriesOf] 返回 null 表示读取失败。
     */
    private inline fun buildJobs(
        brush: Brush,
        coats: List<List<PaintFeatures>>,
        routes: List<CoatRoute>,
        entriesOf: (Int) -> List<MeshEntry>?,
    ): List<CoatJob>? {
        val jobs = ArrayList<CoatJob>(routes.size)
        for ((coat, route) in routes.withIndex()) {
            if (route.route != PaintRoute.COMPAT) continue
            val features = coats[coat][route.paintIndex]
            val stamp = features.stamp ?: return null
            val bitmap = loadBitmap(stamp.textureId) ?: run {
                warnOnce("bitmap", "取不到印章位图，回退官方渲染器")
                return null
            }
            val entries = entriesOf(coat) ?: run {
                warnOnce("layout", "网格布局无法识别，回退官方渲染器")
                return null
            }
            if (entries.any { it.geometry.vertexCount > 0 && !it.geometry.hasSurfaceUv }) {
                warnOnce("nouv", "网格缺少表面 UV，回退官方渲染器")
                return null
            }
            jobs += CoatJob(
                entries = entries,
                paint = StampPaints.paintFor(bitmap),
                texKey = TexKey(stamp.rows, stamp.columns, bitmap.width, bitmap.height),
                colorKey = PaintFeaturesReader.colorKey(brush, features),
            )
        }
        return jobs
    }

    private fun drawJobs(canvas: Canvas, jobs: List<CoatJob>) {
        for (job in jobs) {
            for (entry in job.entries) {
                CompatVerticesDrawer.draw(canvas, entry, job.texKey, job.colorKey, job.paint)
            }
        }
    }

    private fun loadBitmap(id: String): Bitmap? =
        textureStore[id]?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }

    /** 同一原因只记一次日志，避免每帧刷屏；消息里不含任何用户内容。 */
    private fun warnOnce(key: String, message: String) {
        synchronized(warned) { if (!warned.add(key)) return }
        Log.w(TAG, message)
    }

    companion object {
        private const val TAG = "InkCompat"

        /**
         * 创建兼容渲染器，内部同时持有官方渲染器（`CanvasStrokeRenderer.create(textureStore)`）。
         *
         * @param textureStore 与官方相同的纹理位图仓库；印章位图从这里按 `clientTextureId` 取
         * @param alwaysCompat 为 true 时不看 SDK 版本，凡是需要兼容的笔刷都走 drawVertices。
         *   只用于在 API 34+ 上和官方渲染器做像素对拍，正式使用保持 false
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            textureStore: TextureBitmapStore = TextureBitmapStore { null },
            alwaysCompat: Boolean = false,
        ): CompatStrokeRenderer = CompatStrokeRenderer(
            delegate = CanvasStrokeRenderer.create(textureStore),
            textureStore = textureStore,
            sdkInt = Build.VERSION.SDK_INT,
            alwaysCompat = alwaysCompat,
        )

        /** 当前系统上是否会启用兼容路径，供调用方决定笔刷版本等。 */
        @JvmStatic
        fun isCompatActive(): Boolean = Build.VERSION.SDK_INT < CompatDecision.MESH_MIN_SDK
    }
}
