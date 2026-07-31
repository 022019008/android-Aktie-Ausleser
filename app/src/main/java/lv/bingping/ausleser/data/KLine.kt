package lv.bingping.ausleser.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * K 线一根，对应 t_k_5m / t_k_day 的一行（30分钟与周线由合成/网络得到）。
 *
 * @property timestamp Unix 秒（北京时间语义：分钟线为 bar 结束时间，日线为收盘 15:00）
 */
data class KBar(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val amount: Double
)

/** K 线合成工具：输入须为时间升序。 */
object KLineSynth {

    private val ZONE_BJ: ZoneId = ZoneId.of("Asia/Shanghai")

    /**
     * 由日线合成周线：按自然周（周一为起点）聚合，
     * open 取周内首根、close 取末根、high/low 取极值、volume/amount 求和，
     * 时间戳取当周第一根日线。
     */
    fun toWeekly(days: List<KBar>): List<KBar> {
        if (days.isEmpty()) return days
        // 键 = (ISO 年, ISO 周)；升序输入 + LinkedHashMap 保证输出有序
        val weeks = LinkedHashMap<Long, MutableList<KBar>>()
        for (bar in days) {
            val date = Instant.ofEpochSecond(bar.timestamp).atZone(ZONE_BJ).toLocalDate()
            val key = date.get(IsoFields.WEEK_BASED_YEAR).toLong() * 100 +
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            weeks.getOrPut(key) { mutableListOf() }.add(bar)
        }
        return weeks.values.map { group ->
            KBar(
                timestamp = group.first().timestamp,
                open = group.first().open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = group.last().close,
                volume = group.sumOf { it.volume },
                amount = group.sumOf { it.amount }
            )
        }
    }

    /**
     * 由 5 分钟线合成 30 分钟线：按「30 分钟桶结束时刻」聚合。
     * 桶边界为 10:00/10:30/11:00/11:30 与 13:30/14:00/14:30/15:00（每交易日 8 根）——
     * 上午桶以 9:30 为基、下午桶以 13:00 为基，将 bar 结束时间向上取整到 30 分钟边界
     * （与东财 klt=30 的时间标签一致）。
     * open 取桶内首根、close 取末根、high/low 取极值、volume/amount 求和，
     * 时间戳取桶结束时刻（沿用分钟线「bar 结束时间」的约定）。
     */
    fun to30m(bars: List<KBar>): List<KBar> = toSessionBars(bars, 30)

    /**
     * 由 5 分钟线合成 60 分钟线：桶边界为 10:30/11:30/14:00/15:00（每交易日 4 根），
     * 约定与 [to30m] 相同（与东财 klt=60 的时间标签一致）。
     */
    fun to60m(bars: List<KBar>): List<KBar> = toSessionBars(bars, 60)

    /**
     * 由 5 分钟线合成日线：按自然日聚合，时间戳落当日 15:00（与库存日线约定一致，
     * 实时补齐后历史同步可按主键覆盖）。open 取日内首根、close 取末根、
     * high/low 取极值、volume/amount 求和。输入跨多个交易日时逐日各出一根。
     */
    fun toDay(bars: List<KBar>): List<KBar> {
        if (bars.isEmpty()) return bars
        val days = LinkedHashMap<Long, MutableList<KBar>>()
        for (bar in bars) {
            val zdt = Instant.ofEpochSecond(bar.timestamp).atZone(ZONE_BJ)
            val key = zdt.toLocalDate().atTime(15, 0).atZone(ZONE_BJ).toEpochSecond()
            days.getOrPut(key) { mutableListOf() }.add(bar)
        }
        return days.entries.map { (key, group) ->
            KBar(
                timestamp = key,
                open = group.first().open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = group.last().close,
                volume = group.sumOf { it.volume },
                amount = group.sumOf { it.amount }
            )
        }
    }

    /**
     * 交易时段分桶聚合的通用实现：上午以 9:30 为基、下午（13:00 起）以 13:00 为基，
     * 将 bar 结束时间向上取整到 [minutes] 分钟边界（与东财 klt=[minutes] 标签一致）；
     * 时间戳取桶结束时刻，聚合规则见 [to30m]。输入须时间升序。
     */
    private fun toSessionBars(bars: List<KBar>, minutes: Int): List<KBar> {
        if (bars.isEmpty()) return bars
        val buckets = LinkedHashMap<Long, MutableList<KBar>>()
        for (bar in bars) {
            val zdt = Instant.ofEpochSecond(bar.timestamp).atZone(ZONE_BJ)
            val minuteOfDay = zdt.hour * 60 + zdt.minute
            // 上午以 9:30 为基，下午（13:00 起）以 13:00 为基向上取整
            val base = if (minuteOfDay >= 13 * 60) 13 * 60 else 9 * 60 + 30
            val ceil = base + ((minuteOfDay - base + minutes - 1) / minutes) * minutes
            val key = zdt.toLocalDate().atTime(ceil / 60, ceil % 60)
                .atZone(ZONE_BJ).toEpochSecond()
            buckets.getOrPut(key) { mutableListOf() }.add(bar)
        }
        return buckets.entries.map { (key, group) ->
            KBar(
                timestamp = key,
                open = group.first().open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = group.last().close,
                volume = group.sumOf { it.volume },
                amount = group.sumOf { it.amount }
            )
        }
    }
}
