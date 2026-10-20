package com.beiwater.inkcompat

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 与官方一致的颜色函数：作用在笔刷颜色上，顺序应用。对应 `ink/brush/color_function.cc`。
 * 色相偏移用弧度（官方 `Angle`）。
 */
sealed interface InkColorFunction {
    /** alpha 乘以 [multiplier]，结果钳制到 [0, 1]。 */
    data class OpacityMultiplier(val multiplier: Float) : InkColorFunction

    /** 在 Oklab 的 a/b 平面内旋转 [radians]。 */
    data class HueOffset(val radians: Float) : InkColorFunction

    /** Oklab 的 a/b 同乘 [multiplier]。 */
    data class ChromaMultiplier(val multiplier: Float) : InkColorFunction

    /** Oklab 的 L 加 [offset]。 */
    data class LightnessOffset(val offset: Float) : InkColorFunction

    /** 整个颜色替换为 [argb]（sRGB 编码、非预乘）。 */
    data class ReplaceColor(val argb: Int) : InkColorFunction
}

/**
 * 应用颜色函数之后的笔刷颜色，保存为非预乘 Oklab（对应着色器的 `uBrushColorOklab`）
 * 以及对应的 8 位 sRGB 基色（无 HCL 偏移的顶点直接复用）。
 *
 * 只处理 sRGB；Display P3 的笔刷颜色需要调用方先转成 sRGB。
 */
class BrushColor private constructor(
    val oklabL: Float,
    val oklabA: Float,
    val oklabB: Float,
    val alpha: Float,
    /** 钳制到色域后的 0xRRGGBB（不含 alpha）。 */
    val rgb24: Int,
) {
    companion object {
        /** 由 sRGB 编码的 ARGB 颜色与颜色函数序列构造。 */
        fun fromArgb(argb: Int, functions: List<InkColorFunction> = emptyList()): BrushColor {
            // 与官方一致：中间结果保存为线性、不钳制的 RGB，只有 alpha 被钳制。
            val lin = FloatArray(4)
            argbToLinear(argb, lin)
            val lab = FloatArray(3)
            for (f in functions) {
                when (f) {
                    is InkColorFunction.OpacityMultiplier ->
                        lin[3] = (f.multiplier * lin[3]).coerceIn(0f, 1f)
                    is InkColorFunction.ReplaceColor -> argbToLinear(f.argb, lin)
                    is InkColorFunction.HueOffset -> {
                        ColorMath.linearSrgbToOklab(lin[0], lin[1], lin[2], lab)
                        val c = cos(f.radians)
                        val s = sin(f.radians)
                        val a = lab[1]
                        val b = lab[2]
                        lab[1] = c * a - s * b
                        lab[2] = s * a + c * b
                        ColorMath.oklabToLinearSrgb(lab[0], lab[1], lab[2], lin)
                    }
                    is InkColorFunction.ChromaMultiplier -> {
                        ColorMath.linearSrgbToOklab(lin[0], lin[1], lin[2], lab)
                        ColorMath.oklabToLinearSrgb(lab[0], lab[1] * f.multiplier, lab[2] * f.multiplier, lin)
                    }
                    is InkColorFunction.LightnessOffset -> {
                        ColorMath.linearSrgbToOklab(lin[0], lin[1], lin[2], lab)
                        ColorMath.oklabToLinearSrgb(lab[0] + f.offset, lab[1], lab[2], lin)
                    }
                }
            }
            ColorMath.linearSrgbToOklab(lin[0], lin[1], lin[2], lab)
            val rgb = (ColorMath.encodeChannel8(lin[0]) shl 16) or
                (ColorMath.encodeChannel8(lin[1]) shl 8) or ColorMath.encodeChannel8(lin[2])
            return BrushColor(lab[0], lab[1], lab[2], lin[3], rgb)
        }

        private fun argbToLinear(argb: Int, out: FloatArray) {
            out[0] = ColorMath.srgbDecode(((argb shr 16) and 0xFF) / 255f)
            out[1] = ColorMath.srgbDecode(((argb shr 8) and 0xFF) / 255f)
            out[2] = ColorMath.srgbDecode((argb and 0xFF) / 255f)
            out[3] = ((argb ushr 24) and 0xFF) / 255f
        }
    }
}

/**
 * 顶点着色器颜色部分的 CPU 复刻：Oklab 与线性 sRGB 互转、HCL/不透明度偏移，
 * 公式取自 sksl_vertex/fragment_shader_helper_functions.h 与 ink/color/color.cc（Bottosson 矩阵）。
 */
object ColorMath {
    private const val SRGB_THRESHOLD = 0.04045f

    /** sRGB 解码（编码值 -> 线性），对应 C++ `GammaDecode`，不钳制。 */
    fun srgbDecode(encoded: Float): Float =
        if (encoded < SRGB_THRESHOLD) encoded / 12.92f
        else ((encoded + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    /** sRGB 编码（线性 -> 编码值），对应 C++ `GammaEncode`，不钳制（负值走线性段）。 */
    fun linearToSrgbEncoded(linear: Float): Float =
        if (linear < SRGB_THRESHOLD / 12.92f) linear * 12.92f
        else (1.055 * linear.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    /** 线性值编码并钳制到 0..255 的整数（四舍五入）；NaN 视为 0。 */
    fun encodeChannel8(linear: Float): Int {
        val e = linearToSrgbEncoded(linear)
        if (e.isNaN()) return 0
        return (e.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }

    /** 线性 sRGB -> Oklab，结果 (L, a, b) 写入 [out]。 */
    fun linearSrgbToOklab(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val lc = Math.cbrt(l)
        val mc = Math.cbrt(m)
        val sc = Math.cbrt(s)
        out[0] = (lc * 0.2104542553 + mc * 0.7936177850 - sc * 0.0040720468).toFloat()
        out[1] = (lc * 1.9779984951 - mc * 2.4285922050 + sc * 0.4505937099).toFloat()
        out[2] = (lc * 0.0259040371 + mc * 0.7827717662 - sc * 0.8086757660).toFloat()
    }

    /** sRGB 编码值 -> Oklab。 */
    fun srgbToOklab(r: Float, g: Float, b: Float, out: FloatArray) =
        linearSrgbToOklab(srgbDecode(r), srgbDecode(g), srgbDecode(b), out)

    /** Oklab -> 线性 sRGB（不钳制，可能越出色域），结果写入 [out] 的前 3 项。 */
    fun oklabToLinearSrgb(lightness: Float, a: Float, b: Float, out: FloatArray) {
        val lc = lightness + a * 0.3963377774 + b * 0.2158037573
        val mc = lightness - a * 0.1055613458 - b * 0.0638541728
        val sc = lightness - a * 0.0894841775 - b * 1.2914855480
        val l = lc * lc * lc
        val m = mc * mc * mc
        val s = sc * sc * sc
        out[0] = (l * 4.0767416621 - m * 3.3077115913 + s * 0.2309699292).toFloat()
        out[1] = (-l * 1.2684380046 + m * 2.6097574011 - s * 0.3413193965).toFloat()
        out[2] = (-l * 0.0041960863 - m * 0.7034186147 + s * 1.7076147010).toFloat()
    }

    /** `saturate((opacityShift + 1) * baseOpacity)`。 */
    fun applyOpacityShift(opacityShift: Float, baseOpacity: Float): Float =
        ((opacityShift + 1f) * baseOpacity).coerceIn(0f, 1f)

    /**
     * 对非预乘 Oklab [oklab]（L, a, b, alpha）应用 HCL 偏移与不透明度偏移，结果写入 [out]（4 项，可与输入同一数组）。
     * 色相 `hue * 2π` 旋转 a/b，色度乘 `chroma + 1`，明度加 `lightness`。
     */
    fun applyHclAndOpacityShift(
        hue: Float, chroma: Float, lightness: Float, opacityShift: Float,
        oklab: FloatArray, out: FloatArray,
    ) {
        val angle = hue.toDouble() * (2.0 * Math.PI)
        val cs = cos(angle).toFloat()
        val sn = sin(angle).toFloat()
        val mul = chroma + 1f
        val a = oklab[1]
        val b = oklab[2]
        out[1] = (cs * a - sn * b) * mul
        out[2] = (sn * a + cs * b) * mul
        out[0] = oklab[0] + lightness
        out[3] = applyOpacityShift(opacityShift, oklab[3])
    }

    /**
     * 计算一个顶点的 drawVertices 颜色（非预乘 ARGB，sRGB 编码）。
     *
     * 无 HCL 时 RGB 就是笔刷基色（Oklab 往返恒等），只有 alpha 随 [opacityShift] 变；
     * 有 HCL 时取 [hcl] 的 `hclIndex*3` 起三项走 Oklab 路径，超出色域的通道钳制。
     * [scratch] 至少 4 项，供批量调用时复用，避免逐顶点分配。
     */
    fun vertexColorArgb(
        brush: BrushColor, opacityShift: Float, hcl: FloatArray?, hclIndex: Int,
        scratch: FloatArray = FloatArray(4),
    ): Int {
        if (hcl == null) {
            return (alpha8(applyOpacityShift(opacityShift, brush.alpha)) shl 24) or brush.rgb24
        }
        scratch[0] = brush.oklabL
        scratch[1] = brush.oklabA
        scratch[2] = brush.oklabB
        scratch[3] = brush.alpha
        applyHclAndOpacityShift(
            hcl[hclIndex * 3], hcl[hclIndex * 3 + 1], hcl[hclIndex * 3 + 2], opacityShift, scratch, scratch,
        )
        // 先取出 alpha，再让线性 RGB 覆盖 scratch 的前 3 项
        val l = scratch[0]
        val a = scratch[1]
        val b = scratch[2]
        val alpha = scratch[3]
        oklabToLinearSrgb(l, a, b, scratch)
        return (alpha8(alpha) shl 24) or
            (encodeChannel8(scratch[0]) shl 16) or (encodeChannel8(scratch[1]) shl 8) or encodeChannel8(scratch[2])
    }

    /** 批量计算整个解码结果的顶点颜色，写入 [out]（长度至少 `v.count`）。 */
    fun fillVertexColors(brush: BrushColor, v: DecodedVertices, out: IntArray) {
        require(out.size >= v.count) { "输出数组太短" }
        val scratch = FloatArray(4)
        for (i in 0 until v.count) {
            out[i] = vertexColorArgb(brush, v.opacityShift[i], v.hcl, i, scratch)
        }
    }

    private fun alpha8(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
