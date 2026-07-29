package lv.bingping.ausleser.data

/**
 * 缠论（缠中说禅）形态计算：K 线包含处理 → 分型 → 笔（新笔规则）→ 中枢。
 * 全部纯函数，输入须为时间升序的 [KBar]；仅供 [lv.bingping.ausleser.ui.KLineChartView] 叠加绘制。
 *
 * 口径说明：
 *  - 包含合并方向取自最近一对非包含关系：向上取高的高/低的也取高，向下反之；
 *  - 顶分型 = 合并序列中间元素 high 最高，底分型 = low 最低（包含处理后高低同向，比一侧即可）；
 *  - 新笔规则：两分型中枢元素在合并序列中间距 ≥4（不共享 K 线且中间至少 1 根独立），
 *    同型相邻取更极端者；仅输出已成笔（不含未确认的临时笔）；
 *  - 中枢：连续 3 笔区间重叠（zg=min(各笔高点) > zd=max(各笔低点)）成中枢，
 *    其后与 [zd,zg] 相交的笔依次延伸（区间不重算），延伸结束后不重叠地继续划分。
 */

/** 笔端点（分型极值）：顶分型取 high、底分型取 low。 */
data class ChanPoint(val ts: Long, val price: Double)

/** 一笔：首尾为顶底交替的 [ChanPoint]。 */
data class ChanBi(val start: ChanPoint, val end: ChanPoint) {
    val high: Double get() = maxOf(start.price, end.price)
    val low: Double get() = minOf(start.price, end.price)
    val startTs: Long get() = start.ts
    val endTs: Long get() = end.ts
}

/** 中枢：价格区间 [zd, zg]，时间区间 [startTs, endTs]（含延伸的笔）。 */
data class ChanZhongshu(val startTs: Long, val endTs: Long, val zg: Double, val zd: Double)

/** [Chan.analyze] 结果。 */
data class ChanAnalysis(val bi: List<ChanBi>, val zhongshu: List<ChanZhongshu>)

object Chan {

    /** 计算笔与中枢；不足 5 根 K 线无法成笔，直接返回空。 */
    fun analyze(bars: List<KBar>): ChanAnalysis {
        if (bars.size < 5) return ChanAnalysis(emptyList(), emptyList())
        val bi = buildBi(findFenxing(mergeIncluded(bars), bars))
        return ChanAnalysis(bi, buildZhongshu(bi))
    }

    // ---------------------------------------------------------------- 包含处理

    /** 包含处理后的合并 K 线：高低为合并值，极值下标指向真实极值所在的原始 bar。 */
    internal data class MergedBar(
        val high: Double,
        val low: Double,
        val highIdx: Int,
        val lowIdx: Int,
    )

    /** 相邻包含则合并（方向决定取高取低），非包含则按与上一合并 bar 的高低关系定新方向。 */
    internal fun mergeIncluded(bars: List<KBar>): List<MergedBar> {
        val out = mutableListOf<MergedBar>()
        var up = true   // 初始方向任意，首个非包含关系出现后即被纠正
        for (i in bars.indices) {
            val h = bars[i].high
            val l = bars[i].low
            if (out.isEmpty()) {
                out.add(MergedBar(h, l, i, i))
                continue
            }
            val last = out.last()
            val included = (last.high >= h && last.low <= l) || (h >= last.high && l <= last.low)
            if (!included) {
                up = h > last.high
                out.add(MergedBar(h, l, i, i))
                continue
            }
            out[out.size - 1] = if (up) {
                MergedBar(
                    high = maxOf(last.high, h),
                    low = maxOf(last.low, l),
                    highIdx = if (h >= last.high) i else last.highIdx,
                    lowIdx = if (l >= last.low) i else last.lowIdx,
                )
            } else {
                MergedBar(
                    high = minOf(last.high, h),
                    low = minOf(last.low, l),
                    highIdx = if (h <= last.high) i else last.highIdx,
                    lowIdx = if (l <= last.low) i else last.lowIdx,
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 分型

    internal enum class FxType { TOP, BOTTOM }

    /** 分型：idx 为合并序列下标（中间元素），point 为极值（落在真实极值的原始 bar 上）。 */
    internal data class Fx(val type: FxType, val idx: Int, val point: ChanPoint)

    internal fun findFenxing(merged: List<MergedBar>, bars: List<KBar>): List<Fx> {
        val out = mutableListOf<Fx>()
        for (i in 1 until merged.size - 1) {
            val a = merged[i - 1]
            val b = merged[i]
            val c = merged[i + 1]
            when {
                b.high > a.high && b.high > c.high ->
                    out.add(Fx(FxType.TOP, i, ChanPoint(bars[b.highIdx].timestamp, b.high)))
                b.low < a.low && b.low < c.low ->
                    out.add(Fx(FxType.BOTTOM, i, ChanPoint(bars[b.lowIdx].timestamp, b.low)))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 成笔

    /** 新笔规则成笔：同型取极值替换；异型需中枢元素间距 ≥4 且价格方向有效。 */
    internal fun buildBi(fxs: List<Fx>): List<ChanBi> {
        if (fxs.size < 2) return emptyList()
        val pts = mutableListOf<Fx>()
        for (f in fxs) {
            if (pts.isEmpty()) {
                pts.add(f)
                continue
            }
            val last = pts.last()
            if (f.type == last.type) {
                val better = if (f.type == FxType.TOP) f.point.price > last.point.price
                else f.point.price < last.point.price
                if (better) pts[pts.size - 1] = f
            } else {
                val farEnough = f.idx - last.idx >= 4
                val priceOk = if (last.type == FxType.TOP) f.point.price < last.point.price
                else f.point.price > last.point.price
                if (farEnough && priceOk) pts.add(f)
                // 间距/方向不满足：丢弃该分型（不构成笔端点）
            }
        }
        return pts.zipWithNext { a, b -> ChanBi(a.point, b.point) }
    }

    // ---------------------------------------------------------------- 中枢

    /** 连续 3 笔重叠成中枢，随后相交的笔延伸；中枢间不重叠划分。 */
    internal fun buildZhongshu(bi: List<ChanBi>): List<ChanZhongshu> {
        val out = mutableListOf<ChanZhongshu>()
        var i = 0
        while (i + 2 <= bi.size - 1) {
            val zg = minOf(bi[i].high, bi[i + 1].high, bi[i + 2].high)
            val zd = maxOf(bi[i].low, bi[i + 1].low, bi[i + 2].low)
            if (zg > zd) {
                var endTs = bi[i + 2].endTs
                var j = i + 3
                while (j < bi.size && bi[j].low < zg && bi[j].high > zd) {
                    endTs = bi[j].endTs
                    j++
                }
                out.add(ChanZhongshu(bi[i].startTs, endTs, zg, zd))
                i = j
            } else {
                i++
            }
        }
        return out
    }
}
