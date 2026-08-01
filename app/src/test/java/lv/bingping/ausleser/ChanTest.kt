package lv.bingping.ausleser

import lv.bingping.ausleser.data.Chan
import lv.bingping.ausleser.data.ChanBi
import lv.bingping.ausleser.data.ChanPoint
import lv.bingping.ausleser.data.KBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Chan] 缠论形态计算 JVM 单测：包含合并、分型、成笔（新笔规则）、中枢与延伸。
 */
class ChanTest {

    /** 只关心 high/low 的构造器：open/close 取低/高，ts 即参数。 */
    private fun bar(ts: Long, high: Double, low: Double) =
        KBar(timestamp = ts, open = low, high = high, low = low, close = high, volume = 0.0, amount = 0.0)

    private fun bi(startTs: Long, startP: Double, endTs: Long, endP: Double) =
        ChanBi(ChanPoint(startTs, startP), ChanPoint(endTs, endP))

    private fun fx(type: Chan.FxType, idx: Int, price: Double) =
        Chan.Fx(type, idx, ChanPoint(idx.toLong(), price))

    // ---------------------------------------------------------------- 包含处理

    @Test
    fun merge_up_takes_high_of_both() {
        // A(10,5) 包含 B(9,6)：初始向上 → 高取 max、低也取 max
        val merged = Chan.mergeIncluded(listOf(bar(0, 10.0, 5.0), bar(1, 9.0, 6.0), bar(2, 12.0, 8.0)))
        assertEquals(2, merged.size)
        assertEquals(10.0, merged[0].high, 0.0)
        assertEquals(6.0, merged[0].low, 0.0)
        assertEquals(0, merged[0].highIdx)   // 高点在 A
        assertEquals(1, merged[0].lowIdx)    // 合并后的低点在 B
    }

    @Test
    fun merge_down_takes_low_of_both() {
        // A(10,5) → B(8,3) 定向下；C(9,2) 真包含 B（[2,9]⊇[3,8]）→ 高低皆取 min
        val merged = Chan.mergeIncluded(listOf(bar(0, 10.0, 5.0), bar(1, 8.0, 3.0), bar(2, 9.0, 2.0)))
        assertEquals(2, merged.size)
        assertEquals(8.0, merged[1].high, 0.0)
        assertEquals(2.0, merged[1].low, 0.0)
        assertEquals(1, merged[1].highIdx)   // 合并后高点仍在 B
        assertEquals(2, merged[1].lowIdx)    // 低点来自 C
    }

    @Test
    fun merge_chain_tracks_extreme_indices() {
        // 连续三根被首根包含：极值下标随合并更新
        val merged = Chan.mergeIncluded(listOf(bar(0, 10.0, 5.0), bar(1, 9.0, 6.0), bar(2, 8.0, 7.0)))
        assertEquals(1, merged.size)
        assertEquals(10.0, merged[0].high, 0.0)
        assertEquals(7.0, merged[0].low, 0.0)
        assertEquals(0, merged[0].highIdx)
        assertEquals(2, merged[0].lowIdx)
    }

    // ---------------------------------------------------------------- 分型与成笔

    /** 无包含锯齿：升 5 根（顶@4=18）→ 降 5 根（底@9=7）→ 升 5 根（顶@14）→ 降至 8（底@19=7）后反弹两根。 */
    private fun zigzag(): List<KBar> {
        val hs = listOf(10.0, 12.0, 14.0, 16.0, 18.0, 16.0, 14.0, 12.0, 10.0, 8.0,
            10.0, 12.0, 14.0, 16.0, 18.0, 16.0, 14.0, 12.0, 10.0, 8.0, 10.0, 12.0)
        return hs.mapIndexed { i, h -> bar(i.toLong(), h, h - 1) }
    }

    @Test
    fun fenxing_finds_top_and_bottom() {
        val bars = zigzag()
        val fxs = Chan.findFenxing(Chan.mergeIncluded(bars), bars)
        assertEquals(listOf(Chan.FxType.TOP, Chan.FxType.BOTTOM, Chan.FxType.TOP, Chan.FxType.BOTTOM),
            fxs.map { it.type })
        assertEquals(listOf(4, 9, 14, 19), fxs.map { it.idx })
        assertEquals(listOf(18.0, 7.0, 18.0, 7.0), fxs.map { it.point.price })
    }

    @Test
    fun bi_rejects_spacing_below_4() {
        // 顶@4 与底@7 间距 3（新笔要求 ≥4）→ 不成笔
        assertTrue(Chan.buildBi(listOf(
            fx(Chan.FxType.TOP, 4, 18.0), fx(Chan.FxType.BOTTOM, 7, 7.0)
        )).isEmpty())
    }

    @Test
    fun bi_same_type_keeps_more_extreme() {
        // 顶@4(18)、顶@6(20) 同型取高者，再与底@10(7) 成笔
        val bis = Chan.buildBi(listOf(
            fx(Chan.FxType.TOP, 4, 18.0),
            fx(Chan.FxType.TOP, 6, 20.0),
            fx(Chan.FxType.BOTTOM, 10, 7.0),
        ))
        assertEquals(1, bis.size)
        assertEquals(20.0, bis[0].start.price, 0.0)
        assertEquals(7.0, bis[0].end.price, 0.0)
    }

    @Test
    fun bi_alternates_with_valid_spacing() {
        val bis = Chan.buildBi(listOf(
            fx(Chan.FxType.TOP, 4, 18.0),
            fx(Chan.FxType.BOTTOM, 9, 7.0),
            fx(Chan.FxType.TOP, 14, 18.0),
            fx(Chan.FxType.BOTTOM, 19, 7.0),
        ))
        assertEquals(3, bis.size)
        assertEquals(18.0, bis[0].high, 0.0)
        assertEquals(7.0, bis[0].low, 0.0)
    }

    // ---------------------------------------------------------------- 中枢

    @Test
    fun zhongshu_from_three_overlapping_bi() {
        val zs = Chan.buildZhongshu(listOf(
            bi(1, 10.0, 2, 20.0),
            bi(2, 20.0, 3, 15.0),
            bi(3, 15.0, 4, 25.0),
        ))
        assertEquals(1, zs.size)
        assertEquals(20.0, zs[0].zg, 0.0)   // min(20,20,25)
        assertEquals(15.0, zs[0].zd, 0.0)   // max(10,15,15)
        assertEquals(1L, zs[0].startTs)
        assertEquals(4L, zs[0].endTs)
    }

    @Test
    fun zhongshu_extends_while_bi_intersects() {
        val zs = Chan.buildZhongshu(listOf(
            bi(1, 10.0, 2, 20.0),
            bi(2, 20.0, 3, 15.0),
            bi(3, 15.0, 4, 25.0),
            bi(4, 25.0, 5, 17.0),   // 与 [15,20] 相交 → 延伸
            bi(5, 17.0, 6, 30.0),   // 仍相交 → 延伸
            bi(6, 30.0, 7, 22.0),   // low=22 > zg=20 → 离开，止于上一笔
        ))
        assertEquals(1, zs.size)
        assertEquals(6L, zs[0].endTs)
        assertEquals(20.0, zs[0].zg, 0.0)   // 延伸不重算区间
    }

    @Test
    fun zhongshu_none_without_overlap() {
        val zs = Chan.buildZhongshu(listOf(
            bi(1, 10.0, 2, 20.0),
            bi(2, 20.0, 3, 15.0),
            bi(3, 25.0, 4, 30.0),   // 抬高脱离 → zg=20 < zd=25
        ))
        assertTrue(zs.isEmpty())
    }

    // ---------------------------------------------------------------- 端到端

    @Test
    fun analyze_end_to_end_on_zigzag() {
        val result = Chan.analyze(zigzag())
        assertEquals(3, result.bi.size)
        assertEquals(1, result.zhongshu.size)
        val zs = result.zhongshu[0]
        assertEquals(18.0, zs.zg, 0.0)
        assertEquals(7.0, zs.zd, 0.0)
        assertEquals(4L, zs.startTs)
        assertEquals(19L, zs.endTs)
    }

    @Test
    fun analyze_too_few_bars_returns_empty() {
        val result = Chan.analyze(listOf(bar(0, 10.0, 9.0), bar(1, 11.0, 10.0)))
        assertTrue(result.bi.isEmpty())
        assertTrue(result.zhongshu.isEmpty())
    }
}
