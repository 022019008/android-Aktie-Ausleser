package lv.bingping.ausleser

import lv.bingping.ausleser.data.KBar
import lv.bingping.ausleser.data.KLineSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [KLineSynth] 合成逻辑 JVM 单测：覆盖 to30m / to60m 的桶边界
 * （30m：10:00/10:30/11:00/11:30/13:30/14:00/14:30/15:00，每交易日 8 根；
 * 60m：10:30/11:30/14:00/15:00，每交易日 4 根）与 toDay 的日线约定（ts 落当日 15:00）。
 */
class KLineSynthTest {

    private val ZONE_BJ: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 北京时间 → Unix 秒（测试基准，独立于实现的换算路径）。 */
    private fun ts(month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZONE_BJ).toEpochSecond()

    private fun bar(timestamp: Long, idx: Int) = KBar(
        timestamp = timestamp,
        open = 10.0 + idx * 0.01,
        high = 10.9,
        low = 9.1,
        close = 10.0 - idx * 0.01,
        volume = 100.0 + idx,
        amount = 1000.0 + idx
    )

    @Test
    fun to30m_emptyInput_returnsEmpty() {
        assertTrue(KLineSynth.to30m(emptyList()).isEmpty())
    }

    @Test
    fun to30m_morningBucket_aggregatesSixBars() {
        // 9:35..10:00 六根 5 分钟线 → 一根 10:00 的 30 分钟线
        val bars = listOf(935, 940, 945, 950, 955, 1000).mapIndexed { i, hm ->
            bar(ts(7, 24, hm / 100, hm % 100), i)
        }
        val out = KLineSynth.to30m(bars)
        assertEquals(1, out.size)
        val b = out[0]
        assertEquals(ts(7, 24, 10, 0), b.timestamp)
        assertEquals(bars.first().open, b.open, 1e-9)     // open 取首根
        assertEquals(bars.last().close, b.close, 1e-9)    // close 取末根
        assertEquals(10.9, b.high, 1e-9)                  // high 取极值
        assertEquals(9.1, b.low, 1e-9)
        assertEquals(bars.sumOf { it.volume }, b.volume, 1e-9)
        assertEquals(bars.sumOf { it.amount }, b.amount, 1e-9)
    }

    @Test
    fun to30m_bucketBoundaries_areBarEndTimes() {
        // 各桶边界上的 bar 应归入「本桶」：10:00→10:00、10:05→10:30、13:05→13:30、15:00→15:00
        val bars = listOf(
            ts(7, 24, 10, 0),
            ts(7, 24, 10, 5),
            ts(7, 24, 13, 5),
            ts(7, 24, 15, 0)
        ).mapIndexed { i, t -> bar(t, i) }
        val out = KLineSynth.to30m(bars)
        assertEquals(
            listOf(
                ts(7, 24, 10, 0),
                ts(7, 24, 10, 30),
                ts(7, 24, 13, 30),
                ts(7, 24, 15, 0)
            ),
            out.map { it.timestamp }
        )
    }

    @Test
    fun to30m_fullTradingDay_yieldsEightBars() {
        // 完整交易日 48 根 5 分钟线（9:35..11:30、13:05..15:00）→ 8 根 30 分钟线
        val morning = generateSequence(9 * 60 + 35) { it + 5 }.takeWhile { it <= 11 * 60 + 30 }
        val afternoon = generateSequence(13 * 60 + 5) { it + 5 }.takeWhile { it <= 15 * 60 }
        val bars = (morning + afternoon).mapIndexed { i, m -> bar(ts(7, 24, m / 60, m % 60), i) }.toList()
        assertEquals(48, bars.size)
        val out = KLineSynth.to30m(bars)
        assertEquals(
            listOf(1000 to 0, 1030 to 0, 1100 to 0, 1130 to 0, 1330 to 0, 1400 to 0, 1430 to 0, 1500 to 0)
                .map { (hm, _) -> ts(7, 24, hm / 100, hm % 100) },
            out.map { it.timestamp }
        )
        // 每桶恰含 6 根：volume 为该桶内 6 根之和
        out.forEachIndexed { j, b ->
            assertEquals(bars.subList(j * 6, j * 6 + 6).sumOf { it.volume }, b.volume, 1e-9)
        }
    }

    @Test
    fun to30m_acrossDays_notMerged() {
        // 相邻两日各自收盘/开盘 bar 不得跨日合并
        val bars = listOf(
            bar(ts(7, 24, 15, 0), 0),
            bar(ts(7, 27, 9, 35), 1)
        )
        val out = KLineSynth.to30m(bars)
        assertEquals(2, out.size)
        assertEquals(ts(7, 24, 15, 0), out[0].timestamp)
        assertEquals(ts(7, 27, 10, 0), out[1].timestamp)
    }

    @Test
    fun to30m_preOpenAuctionBar_fallsIntoFirstBucket() {
        // 9:25 集合竞价 bar（若出现）应落入首个 9:30 基桶（输出时间戳 9:30）
        val out = KLineSynth.to30m(listOf(bar(ts(7, 24, 9, 25), 0)))
        assertEquals(1, out.size)
        assertEquals(ts(7, 24, 9, 30), out[0].timestamp)
    }

    // ---------------------------------------------------------------- to60m

    @Test
    fun to60m_emptyInput_returnsEmpty() {
        assertTrue(KLineSynth.to60m(emptyList()).isEmpty())
    }

    @Test
    fun to60m_fullTradingDay_yieldsFourBars() {
        // 完整交易日 48 根 5 分钟线 → 4 根 60 分钟线（每桶 12 根）
        val morning = generateSequence(9 * 60 + 35) { it + 5 }.takeWhile { it <= 11 * 60 + 30 }
        val afternoon = generateSequence(13 * 60 + 5) { it + 5 }.takeWhile { it <= 15 * 60 }
        val bars = (morning + afternoon).mapIndexed { i, m -> bar(ts(7, 24, m / 60, m % 60), i) }.toList()
        val out = KLineSynth.to60m(bars)
        assertEquals(
            listOf(1030, 1130, 1400, 1500).map { ts(7, 24, it / 100, it % 100) },
            out.map { it.timestamp }
        )
        out.forEachIndexed { j, b ->
            assertEquals(bars.subList(j * 12, j * 12 + 12).sumOf { it.volume }, b.volume, 1e-9)
        }
    }

    @Test
    fun to60m_bucketBoundaries_areBarEndTimes() {
        // 10:00→10:30、10:30→10:30（本桶末根）、10:35→11:30、13:05→14:00、15:00→15:00
        val bars = listOf(
            ts(7, 24, 10, 0),
            ts(7, 24, 10, 30),
            ts(7, 24, 10, 35),
            ts(7, 24, 13, 5),
            ts(7, 24, 15, 0)
        ).mapIndexed { i, t -> bar(t, i) }
        val out = KLineSynth.to60m(bars)
        assertEquals(
            listOf(
                ts(7, 24, 10, 30),
                ts(7, 24, 11, 30),
                ts(7, 24, 14, 0),
                ts(7, 24, 15, 0)
            ),
            out.map { it.timestamp }
        )
    }

    // ---------------------------------------------------------------- toDay

    @Test
    fun toDay_emptyInput_returnsEmpty() {
        assertTrue(KLineSynth.toDay(emptyList()).isEmpty())
    }

    @Test
    fun toDay_singleDay_aggregatesToOneBar() {
        val bars = listOf(935, 940, 945).mapIndexed { i, hm ->
            bar(ts(7, 24, hm / 100, hm % 100), i)
        }
        val out = KLineSynth.toDay(bars)
        assertEquals(1, out.size)
        val b = out[0]
        assertEquals(ts(7, 24, 15, 0), b.timestamp)       // 时间戳落当日 15:00（与库存日线约定一致）
        assertEquals(bars.first().open, b.open, 1e-9)     // open 取日内首根
        assertEquals(bars.last().close, b.close, 1e-9)    // close 取末根
        assertEquals(10.9, b.high, 1e-9)
        assertEquals(9.1, b.low, 1e-9)
        assertEquals(bars.sumOf { it.volume }, b.volume, 1e-9)
        assertEquals(bars.sumOf { it.amount }, b.amount, 1e-9)
    }

    @Test
    fun toDay_acrossDays_groupedPerDay() {
        // 跨多个交易日时逐日各出一根（ts 各落当日 15:00）
        val bars = listOf(
            bar(ts(7, 24, 14, 55), 0),
            bar(ts(7, 24, 15, 0), 1),
            bar(ts(7, 27, 9, 35), 2)
        )
        val out = KLineSynth.toDay(bars)
        assertEquals(listOf(ts(7, 24, 15, 0), ts(7, 27, 15, 0)), out.map { it.timestamp })
    }
}
