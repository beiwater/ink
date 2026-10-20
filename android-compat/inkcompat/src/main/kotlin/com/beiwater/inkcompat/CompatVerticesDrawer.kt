package com.beiwater.inkcompat

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import java.util.WeakHashMap

/** 印章位图对应的 Paint：不透明白色，避免默认黑色把顶点色乘黑。 */
internal object StampPaints {
    private val cache = WeakHashMap<Bitmap, Paint>()

    /**
     * 官方对印章纹理固定用 REPEAT/REPEAT（BrushPaintCache），这里照做；
     * 待设备对拍：REPEAT 在 UV=1 的边上做双线性采样会混入对边像素，印章边缘 alpha 为 0 时应无可见差别。
     */
    @Synchronized
    fun paintFor(bitmap: Bitmap): Paint = cache.getOrPut(bitmap) {
        Paint().apply {
            color = Color.WHITE
            isFilterBitmap = true
            isAntiAlias = false
            shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}

/** `Canvas.drawVertices` 的薄封装；参数装配与校验是纯函数，见 [DrawArgs]。 */
internal object CompatVerticesDrawer {
    fun draw(canvas: Canvas, entry: MeshEntry, texKey: TexKey, colorKey: ColorKey, paint: Paint) {
        val g = entry.geometry
        if (g.vertexCount == 0) return
        val texs = entry.texCoords(texKey)
        val colors = entry.colors(colorKey)
        if (!DrawArgs.isConsistent(g, texs, colors)) return
        // 待设备对拍：colors 语义（非预乘 sRGB ARGB）、colors × shader 的相乘方式、无抗锯齿
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            g.vertexFloatCount, g.positions, 0,
            texs, 0,
            colors, 0,
            g.indices, 0, g.indices.size,
            paint,
        )
    }
}

/** `drawVertices` 参数的一致性校验（纯函数，JVM 可测）。 */
object DrawArgs {
    /**
     * 位置数组长度必须等于 `vertexFloatCount`，纹理坐标同长，颜色长度等于顶点数，
     * 索引个数是 3 的倍数。不满足时调用方应跳过绘制，不要把越界数组交给 Canvas。
     */
    fun isConsistent(g: MeshGeometry, texs: FloatArray, colors: IntArray): Boolean =
        g.positions.size == g.vertexFloatCount &&
            texs.size == g.vertexFloatCount &&
            colors.size == g.vertexCount &&
            g.indices.size % 3 == 0
}
