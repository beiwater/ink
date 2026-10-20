package com.beiwater.inkcompat

import android.graphics.Bitmap

/** 两张位图的逐像素差异统计（通道取 0..255 的绝对差）。 */
data class PixelDiffStats(
    /** 所有像素、所有通道（含 alpha）的平均绝对差。 */
    val meanAbs: Double,
    val p90: Int,
    val p99: Int,
    val max: Int,
    /** 任一通道差大于 [PixelDiff.VISIBLE_THRESHOLD] 的像素占比。 */
    val visibleFraction: Double,
    /** 两张图里 alpha 不为 0 的像素占比（用来判断是不是空白图）。 */
    val inkFractionA: Double,
    val inkFractionB: Double,
) {
    override fun toString(): String =
        "meanAbs=%.3f p90=%d p99=%d max=%d visible=%.4f ink(official)=%.4f ink(compat)=%.4f"
            .format(meanAbs, p90, p99, max, visibleFraction, inkFractionA, inkFractionB)
}

object PixelDiff {
    const val VISIBLE_THRESHOLD = 8

    fun compare(a: Bitmap, b: Bitmap): PixelDiffStats {
        require(a.width == b.width && a.height == b.height) { "尺寸不同" }
        val n = a.width * a.height
        val pa = IntArray(n).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(n).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val hist = IntArray(256)
        var sum = 0L
        var visible = 0
        var inkA = 0
        var inkB = 0
        for (i in 0 until n) {
            var worst = 0
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val d = Math.abs(((pa[i] ushr shift) and 0xFF) - ((pb[i] ushr shift) and 0xFF))
                sum += d
                if (d > worst) worst = d
            }
            hist[worst]++
            if (worst > VISIBLE_THRESHOLD) visible++
            if ((pa[i] ushr 24) != 0) inkA++
            if ((pb[i] ushr 24) != 0) inkB++
        }
        return PixelDiffStats(
            meanAbs = sum.toDouble() / (n * 4.0),
            p90 = percentile(hist, n, 0.90),
            p99 = percentile(hist, n, 0.99),
            max = hist.indexOfLast { it > 0 }.coerceAtLeast(0),
            visibleFraction = visible.toDouble() / n,
            inkFractionA = inkA.toDouble() / n,
            inkFractionB = inkB.toDouble() / n,
        )
    }

    /** 按“每像素最大通道差”的直方图取分位数。 */
    private fun percentile(hist: IntArray, n: Int, q: Double): Int {
        val target = (n * q).toLong()
        var acc = 0L
        for (v in hist.indices) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }
}
