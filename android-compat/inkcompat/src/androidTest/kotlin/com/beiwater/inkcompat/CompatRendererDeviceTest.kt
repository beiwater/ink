package com.beiwater.inkcompat

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.geometry.MutableVec
import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备对拍（本容器只保证能编译，没有运行过）。
 *
 * - 位置金标：用官方 `Mesh.fillPosition` 校验 [InkMeshReader] 的解码，API 级别不限。
 * - 像素对拍：API 34+ 上同一条笔画分别用官方渲染器（网格）和 [CompatStrokeRenderer]（强制兼容）画到 Bitmap，
 *   输出差异统计到 logcat（tag `InkCompatDiff`）并把两张图与差异图写到应用缓存目录。
 * - 烟雾：任意 API 上兼容路径画出来不是空白、不崩溃。
 *
 * 断言的阈值是占位的宽松值；“视觉是否一致”要看输出的统计和 PNG，再据此收紧。
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalInkCustomBrushApi::class, InkInternalOnlyApi::class)
@SuppressLint("RestrictedApi")
class CompatRendererDeviceTest {
    private val stampId = "compat-test-stamp"
    private lateinit var stampBitmap: Bitmap
    private lateinit var store: TextureBitmapStore

    @Before
    fun setUp() {
        stampBitmap = makeStampBitmap(64)
        store = TextureBitmapStore { id -> if (id == stampId) stampBitmap else null }
    }

    @Test
    fun decodedPositionsMatchOfficialFillPosition() {
        val stroke = buildStroke(pencilBrush())
        var checked = 0
        for (group in 0 until stroke.shape.getRenderGroupCount()) {
            for (mesh in stroke.shape.renderGroupMeshes(group)) {
                val g = InkMeshReader.readStrokeMesh(mesh)
                assertNotNull("无法解码网格（布局推断失败？）attrs=${mesh.format.attributeCount()} stride=${mesh.vertexStride}", g)
                if (g === MeshGeometry.EMPTY) continue
                assertEquals(mesh.vertexCount, g!!.vertexCount)
                val out = MutableVec()
                var worst = 0f
                for (i in 0 until mesh.vertexCount) {
                    mesh.fillPosition(i, out)
                    worst = maxOf(worst, Math.abs(out.x - g.positions[i * 2]), Math.abs(out.y - g.positions[i * 2 + 1]))
                }
                Log.i(TAG, "位置对拍：顶点=${mesh.vertexCount} 属性数=${mesh.format.attributeCount()} 步长=${mesh.vertexStride} 最大误差=$worst")
                assertTrue("位置误差过大：$worst", worst < 1e-3f)
                checked += mesh.vertexCount
            }
        }
        assertTrue("没有可对拍的顶点", checked > 0)
    }

    @Test
    fun compatSmokeDrawsNonBlank() {
        val stroke = buildStroke(pencilBrush())
        val bmp = render { canvas, m -> CompatStrokeRenderer.create(store, alwaysCompat = true).draw(canvas, stroke, m) }
        val stats = PixelDiff.compare(bmp, bmp)
        assertTrue("兼容路径画出空白图", stats.inkFractionA > 0.0005)
    }

    @Test
    fun stampedStrokeCompatVsOfficialPixelDiff() {
        assumeTrue("官方网格渲染器需要 API 34+", Build.VERSION.SDK_INT >= 34)
        val stroke = buildStroke(pencilBrush())
        val official = CanvasStrokeRenderer.create(store)
        val compat = CompatStrokeRenderer.create(store, alwaysCompat = true)
        val a = render { canvas, m -> official.draw(canvas, stroke, m) }
        val b = render { canvas, m -> compat.draw(canvas, stroke, m) }
        val stats = PixelDiff.compare(a, b)
        Log.i(DIFF_TAG, "完成笔画：$stats")
        dump("stroke", a, b)
        assertTrue("官方画出空白图（印章位图或笔刷有问题）", stats.inkFractionA > 0.0005)
        assertTrue("兼容路径画出空白图", stats.inkFractionB > 0.0005)
        assertTrue("平均差异过大：$stats", stats.meanAbs < 16.0)
    }

    @Test
    fun wetStrokeCompatVsOfficialPixelDiff() {
        assumeTrue("官方网格渲染器需要 API 34+", Build.VERSION.SDK_INT >= 34)
        val wet = buildWetStroke(pencilBrush())
        val official = CanvasStrokeRenderer.create(store)
        val compat = CompatStrokeRenderer.create(store, alwaysCompat = true)
        val a = render { canvas, m -> official.draw(canvas, wet, m) }
        val b = render { canvas, m -> compat.draw(canvas, wet, m) }
        val stats = PixelDiff.compare(a, b)
        Log.i(DIFF_TAG, "湿笔：$stats")
        dump("wet", a, b)
        assertTrue("兼容路径画出空白图", stats.inkFractionB > 0.0005)
        assertTrue("平均差异过大：$stats", stats.meanAbs < 16.0)
    }

    // ---- 夹具 ----

    private fun pencilBrush(): Brush {
        val family = BrushFamily(
            tip = BrushTip(scaleX = 1.7f, scaleY = 1.7f, cornerRounding = 1f, particleGapDistanceScale = 0.35f),
            paint = BrushPaint(
                textureLayers = listOf(BrushPaint.StampingTexture(stampId)),
                colorFunctions = listOf(BrushPaint.ColorFunction.OpacityMultiplier(0.5f)),
                selfOverlap = SelfOverlap.ANY,
            ),
        )
        return Brush.createWithColorIntArgb(family, Color.argb(255, 30, 40, 90), 6f, 0.1f)
    }

    private fun inputs(): MutableStrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        for (i in 0..60) {
            val x = 20f + i * 2.2f
            val y = 70f + 25f * Math.sin(i / 8.0).toFloat()
            batch.add(InputToolType.STYLUS, x, y, i * 8L)
        }
        return batch
    }

    private fun buildStroke(brush: Brush): Stroke = Stroke(brush, inputs())

    private fun buildWetStroke(brush: Brush): InProgressStroke {
        val wet = InProgressStroke()
        wet.start(brush)
        wet.enqueueInputs(inputs(), MutableStrokeInputBatch())
        wet.finishInput()
        wet.updateShape(Long.MAX_VALUE)
        return wet
    }

    /** 中心实、边缘透明的圆形印章。 */
    private fun makeStampBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size / 2f, size / 2f, size / 2f,
                intArrayOf(Color.WHITE, Color.argb(120, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
        }
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bmp
    }

    private fun render(draw: (Canvas, Matrix) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        val m = Matrix().apply { setScale(SCALE, SCALE) }
        canvas.concat(m)
        draw(canvas, m)
        return bmp
    }

    private fun dump(name: String, official: Bitmap, compat: Bitmap) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "inkcompat-diff").apply { mkdirs() }
        val diff = Bitmap.createBitmap(official.width, official.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until official.height) for (x in 0 until official.width) {
            val p = official.getPixel(x, y)
            val q = compat.getPixel(x, y)
            val d = maxOf(
                Math.abs(Color.alpha(p) - Color.alpha(q)), Math.abs(Color.red(p) - Color.red(q)),
                Math.abs(Color.green(p) - Color.green(q)), Math.abs(Color.blue(p) - Color.blue(q)),
            )
            diff.setPixel(x, y, Color.rgb(d, d, d))
        }
        for ((suffix, bmp) in listOf("official" to official, "compat" to compat, "diff" to diff)) {
            FileOutputStream(File(dir, "$name-$suffix.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Log.i(DIFF_TAG, "对拍图已写入 ${dir.absolutePath}/$name-*.png")
    }

    private companion object {
        const val TAG = "InkCompatTest"
        const val DIFF_TAG = "InkCompatDiff"
        const val SIZE = 512
        const val SCALE = 4f
    }
}
