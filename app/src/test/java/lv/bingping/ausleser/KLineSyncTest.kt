package lv.bingping.ausleser

import lv.bingping.ausleser.data.KBar
import lv.bingping.ausleser.data.KLineSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [KLineSync] 纯逻辑 JVM 单测：交易日估算、增量拉取量计算、复权检测。
 * （涉及 DB/网络的 syncMember 编排由模拟器端到端验证。）
 */
class KLineSyncTest {

    private val ZONE_BJ: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 北京时间 → Unix 秒。 */
    private fun ts(year: Int, month: Int, day: Int, hour: Int = 15, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE_BJ).toEpochSecond()

    private fun bar(timestamp: Long, close: Double) = KBar(
        timestamp = timestamp,
        open = close,
        high = close * 1.01,
        low = close * 0.99,
        close = close,
        volume = 100.0,
        amount = 1000.0
    )

    // ---------- estimateTradingDays ----------

    @Test
    fun estimateTradingDays_sameDay_isOne() {
        // 2026-07-28（周二）09:30 → 同日 15:00
        assertEquals(1, KLineSync.estimateTradingDays(ts(2026, 7, 28, 9, 30), ts(2026, 7, 28, 15, 0)))
    }

    @Test
    fun estimateTradingDays_invertedOrder_isOne() {
        assertEquals(1, KLineSync.estimateTradingDays(ts(2026, 7, 28), ts(2026, 7, 27)))
    }

    @Test
    fun estimateTradingDays_overWeekend_countsOnlyWeekdays() {
        // 2026-07-24（周五）→ 2026-07-27（周一）：周五+周一 = 2 个交易日
        assertEquals(2, KLineSync.estimateTradingDays(ts(2026, 7, 24), ts(2026, 7, 27)))
    }

    @Test
    fun estimateTradingDays_fullWeek_countsFive() {
        // 2026-07-27（周一）→ 2026-08-02（周日）：周一至周五 = 5
        assertEquals(5, KLineSync.estimateTradingDays(ts(2026, 7, 27), ts(2026, 8, 2)))
    }

    @Test
    fun estimateTradingDays_crossYear() {
        // 2025-12-29（周一）→ 2026-01-04（周日）：12/29~1/2 周一至周五 = 5
        assertEquals(5, KLineSync.estimateTradingDays(ts(2025, 12, 29), ts(2026, 1, 4)))
    }

    // ---------- estimateTailBars / syncLimit ----------

    @Test
    fun estimateTailBars_scalesByBarsPerDay() {
        // 周五 → 周一 = 2 交易日 × 48 根
        assertEquals(96, KLineSync.estimateTailBars(ts(2026, 7, 24), ts(2026, 7, 27), 48))
        assertEquals(16, KLineSync.estimateTailBars(ts(2026, 7, 24), ts(2026, 7, 27), 8))
    }

    @Test
    fun syncLimit_takesMaxOfTailAndOverlap_cappedByTarget() {
        assertEquals(96, KLineSync.syncLimit(tailEstimate = 10, overlap = 96, firstTarget = 24_000))
        assertEquals(500, KLineSync.syncLimit(tailEstimate = 500, overlap = 96, firstTarget = 24_000))
        // 超长未同步（估算超全量）时不超过首次量级
        assertEquals(24_000, KLineSync.syncLimit(tailEstimate = 99_999, overlap = 96, firstTarget = 24_000))
    }

    // ---------- detectAdjustChange ----------

    @Test
    fun detectAdjustChange_emptyInputs_isFalse() {
        assertFalse(KLineSync.detectAdjustChange(emptyList(), emptyList()))
        assertFalse(KLineSync.detectAdjustChange(listOf(bar(100, 10.0)), emptyList()))
        assertFalse(KLineSync.detectAdjustChange(emptyList(), listOf(bar(100, 10.0))))
    }

    @Test
    fun detectAdjustChange_pricesMatch_isFalse() {
        val t = (0L until 5).map { ts(2026, 7, 20 + it.toInt()) }
        val stored = t.map { bar(it, 10.0) }
        val fetched = t.map { bar(it, 10.0) }
        assertFalse(KLineSync.detectAdjustChange(stored, fetched))
    }

    @Test
    fun detectAdjustChange_historyRecomputed_isTrue() {
        // 除权除息：历史收盘价整体被前复权重算（-2%），仅最新 bar 不变
        val t = (0L until 5).map { ts(2026, 7, 20 + it.toInt()) }
        val stored = t.map { bar(it, 10.0) }
        val fetched = t.dropLast(1).map { bar(it, 9.8) } + bar(t.last(), 10.0)
        assertTrue(KLineSync.detectAdjustChange(stored, fetched))
    }

    @Test
    fun detectAdjustChange_onlyInProgressBarDiffers_isFalse() {
        // 本次拉取的最新一根（盘中变动）与库存最新一根（当初未收盘写入）均应跳过
        val t1 = ts(2026, 7, 27)
        val t2 = ts(2026, 7, 28)
        val stored = listOf(bar(t1, 10.0), bar(t2, 10.0))       // t2 当初在途，收盘值后来变了
        val fetched = listOf(bar(t1, 10.0), bar(t2, 10.5), bar(ts(2026, 7, 29), 10.6))
        assertFalse(KLineSync.detectAdjustChange(stored, fetched))
    }

    @Test
    fun detectAdjustChange_noTimestampOverlap_isFalse() {
        val stored = listOf(bar(ts(2026, 7, 20), 10.0))
        val fetched = listOf(bar(ts(2026, 7, 27), 9.0), bar(ts(2026, 7, 28), 9.1))
        assertFalse(KLineSync.detectAdjustChange(stored, fetched))
    }

    @Test
    fun detectAdjustChange_roundingNoiseBelowEpsilon_isFalse() {
        // 跨源小数点舍入级别的差异（<0.1%）不应误报
        val t = (0L until 4).map { ts(2026, 7, 20 + it.toInt()) }
        val stored = t.map { bar(it, 10.0) }
        val fetched = t.map { bar(it, 10.005) }   // +0.05% < 1e-3
        assertFalse(KLineSync.detectAdjustChange(stored, fetched))
    }

    // ---------- needsRealtime / isTradingHours ----------

    @Test
    fun needsRealtime_emptyTable_isTrue() {
        assertTrue(KLineSync.needsRealtime(null, ts(2026, 7, 28, 10, 0)))
    }

    @Test
    fun needsRealtime_noTodayBars_isTrue() {
        // 存量最新为昨日 → 缺当日 bar，需要实时补齐
        assertTrue(KLineSync.needsRealtime(ts(2026, 7, 27, 15, 0), ts(2026, 7, 28, 10, 0)))
    }

    @Test
    fun needsRealtime_hasTodayBarsFresh_isFalse() {
        // 已有 2 分钟前的当日 bar → 不刷新
        val now = ts(2026, 7, 28, 10, 0)
        assertFalse(KLineSync.needsRealtime(now - 120, now))
    }

    @Test
    fun needsRealtime_hasTodayBarsStaleInSession_isTrue() {
        // 盘中当日 bar 已陈旧（>5 分钟）→ 刷新
        val now = ts(2026, 7, 28, 10, 0)
        assertTrue(KLineSync.needsRealtime(now - 400, now))
    }

    @Test
    fun needsRealtime_staleButAfterClose_isFalse() {
        // 收盘后不再拉实时（当晚历史同步即权威值）
        val now = ts(2026, 7, 28, 16, 30)
        assertFalse(KLineSync.needsRealtime(ts(2026, 7, 28, 14, 55), now))
    }

    @Test
    fun needsRealtime_weekendWithoutTodayBars_isTrue_serverSkips() {
        // 周六看周五的存量：本地无"当日" bar → 判为需要（App 无交易日历），
        // 由服务端交易日历判定非交易日返回空，调用无害
        val now = ts(2026, 8, 1, 10, 0)  // 周六
        assertTrue(KLineSync.needsRealtime(ts(2026, 7, 31, 14, 55), now))
    }

    @Test
    fun isTradingHours_boundaries() {
        assertTrue(KLineSync.isTradingHours(ts(2026, 7, 28, 9, 30)))   // 周二开盘
        assertTrue(KLineSync.isTradingHours(ts(2026, 7, 28, 15, 0)))   // 收盘
        assertFalse(KLineSync.isTradingHours(ts(2026, 7, 28, 9, 20)))  // 盘前
        assertFalse(KLineSync.isTradingHours(ts(2026, 7, 28, 15, 10))) // 盘后
        assertFalse(KLineSync.isTradingHours(ts(2026, 8, 1, 10, 0)))   // 周六
    }
}
