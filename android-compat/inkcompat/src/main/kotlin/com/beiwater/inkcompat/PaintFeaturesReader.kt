package com.beiwater.inkcompat

import android.annotation.SuppressLint
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.ExperimentalInkAnimationApi
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap

/**
 * 把 `Brush` / `BrushPaint` 读成 [PaintFeatures] 等纯数据。桥接层里唯一碰笔刷 API 的地方，
 * 升级 Ink 版本时若笔刷 API 有变动，只需改这里。
 */
@OptIn(ExperimentalInkAnimationApi::class, ExperimentalInkCustomBrushApi::class)
@SuppressLint("RestrictedApi")
internal object PaintFeaturesReader {

    fun read(paint: BrushPaint): PaintFeatures {
        val layers = paint.textureLayers
        val first = layers.firstOrNull()
        val kind = when (first) {
            null -> TextureKind.NONE
            is BrushPaint.TilingTexture -> TextureKind.TILING
            is BrushPaint.StampingTexture -> TextureKind.STAMPING
            else -> TextureKind.UNKNOWN
        }
        val stamp = (first as? BrushPaint.StampingTexture)?.let {
            StampSpec(
                textureId = it.clientTextureId,
                frames = it.animationFrames,
                rows = it.animationRows,
                columns = it.animationColumns,
                reverse = it.animationRepeatMode == BrushPaint.TextureLayer.AnimationRepeatMode.REVERSE,
            )
        }
        val overlap = when (paint.selfOverlap) {
            SelfOverlap.ANY -> OverlapKind.ANY
            SelfOverlap.ACCUMULATE -> OverlapKind.ACCUMULATE
            SelfOverlap.DISCARD -> OverlapKind.DISCARD
            else -> OverlapKind.UNKNOWN
        }
        return PaintFeatures(
            selfOverlap = overlap,
            firstLayer = kind,
            layerCount = layers.size,
            firstLayerModulate = first == null || first.blendMode == BrushPaint.TextureLayer.BlendMode.MODULATE,
            stamp = stamp,
            colorFunctions = readColorFunctions(paint),
        )
    }

    /** 每个 coat 的每个 paint 偏好；顺序与 `brush.family.coats[i].paintPreferences` 一致。 */
    fun readCoats(brush: Brush): List<List<PaintFeatures>> =
        brush.family.coats.map { coat -> coat.paintPreferences.map(::read) }

    /** 颜色缓存键：笔刷色加该 paint 的颜色函数。 */
    fun colorKey(brush: Brush, features: PaintFeatures): ColorKey =
        ColorKey(brush.colorIntArgb, features.colorFunctions.orEmpty())

    private fun readColorFunctions(paint: BrushPaint): List<InkColorFunction>? {
        val out = ArrayList<InkColorFunction>()
        for (f in paint.colorFunctions) {
            out += when (f) {
                is BrushPaint.ColorFunction.OpacityMultiplier -> InkColorFunction.OpacityMultiplier(f.multiplier)
                is BrushPaint.ColorFunction.HueOffset ->
                    InkColorFunction.HueOffset(Math.toRadians(f.offsetDegrees.toDouble()).toFloat())
                is BrushPaint.ColorFunction.ChromaMultiplier -> InkColorFunction.ChromaMultiplier(f.multiplier)
                is BrushPaint.ColorFunction.LightnessOffset -> InkColorFunction.LightnessOffset(f.offset)
                is BrushPaint.ColorFunction.ReplaceColor -> InkColorFunction.ReplaceColor(f.colorIntArgb)
                else -> return null
            }
        }
        return out
    }
}
