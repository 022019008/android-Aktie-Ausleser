package lv.bingping.ausleser.data

import android.content.Context
import lv.bingping.ausleser.util.AppLog
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs

/**
 * 个股 K 线同步编排：打开 K 线页时在后台线程调用 [syncStock]，
 * 使本地 t_k_5m / t_k_30m / t_k_60m / t_k_day 保持最新，入库一律前复权（adjust=[ADJUST_QFQ]）。
 *
 * 数据来源为自建数据源 Aktie_datasource（[DatasourceApi]，freq=5m/30m/60m/day），
 * 服务端存储窗口：5m 半年、30m 两年、60m 与日线五年；App 请求条数超过服务端
 * 上限时由服务端自动截断，能拿多少算多少。
 *
 * 每张表独立执行以下策略（单表失败不阻断其余表）：
 *  1. 空表、存在非前复权行（种子库 bfq 遗留）或仅存实时行（新股首登竞态，
 *     见 [syncTable]）→ 清掉该股全部行 → 按首次量级全量拉取入库；
 *     全量/增量以历史定稿行存量为准，实时行不算存量；
 *     请求量级超过服务端存储窗口时由服务端截断，能拿多少算多少；
 *  2. 否则拉尾部增量（至少带 [OVERLAP_5M] 等重叠根数）：
 *     重叠已完成 bar 收盘价一致 → 仅 upsert 拉到的新数据；
 *     价格被重算（除权除息，前复权基准变化）→ 清掉该股该表全部行 → 全量重拉入库；
 *  3. 四个周期均各自从数据源直接拉取（30m/60m 为服务端自有表，不由 5m 聚合）。
 *
 * 仅当四个周期的网络拉取**全部**失败时向外抛 [IOException]；
 * 调用方（KLineActivity）提示用户后仍应展示本地已有数据。
 *
 * 当日实时补齐另走 [syncRealtime]：库存（历史接口）在收盘定稿前不含当日 bar，
 * 故本地补不到当日时改从数据源实时接口（/api/kline/{code}/realtime，东财
 * 当日数据）取当日 bar 本地覆写展示。实时数据只请求 5 分钟一个频率，
 * 30m / 60m / day 由 [KLineSynth] 本地合成（桶边界与库存 bar 主键对齐），
 * 四表写入行均带 is_realtime=1 标记。实时行不参与复权检测（[detectAdjustChange]
 * 的存量样本只取历史定稿行，东财与库存前复权基准的细微差异不会误判除权除息）；
 * 尽力而为，随后历史同步拿到当天权威值时按主键整行覆盖、标记归 0。
 * 编排约束两条：调用方须在历史同步**成功入库后**才执行 [syncRealtime]；
 * 进程级读写锁 [SYNC_LOCK]（历史持读锁、实时持写锁）保证发往数据源的历史与
 * 实时两类请求绝不并行——KLineActivity 与 MainActivity 各有独立后台 executor，
 * 无此锁时两处的历史/实时请求可能在网络上重叠。
 */
object KLineSync {

    /**
     * 进程级同步锁：[syncStock] 持读锁、[syncRealtime] 持写锁。
     * 多路历史同步互不阻塞；实时补齐与任何在途历史同步互斥，
     * 即实时请求只会在全部历史请求完成入库之后才开始。
     */
    private val SYNC_LOCK = ReentrantReadWriteLock()

    private val ZONE_BJ: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 入库复权类型：前复权。 */
    const val ADJUST_QFQ = "qfq"

    /** 首次下载 / 复权重建量级：5m、30m 各两年（约 242 交易日/年），60m、日线五年。 */
    const val FIRST_5M = 24_000
    const val FIRST_30M = 4_000
    const val FIRST_60M = 5_000
    const val FIRST_DAY = 1_300

    /** 数据源频率参数（Aktie_datasource /api/kline 的 freq 取值）。 */
    private const val FREQ_5M = "5m"
    private const val FREQ_30M = "30m"
    private const val FREQ_60M = "60m"
    private const val FREQ_DAY = "day"

    /** 每交易日 bar 数（5m：9:30-11:30 与 13:00-15:00 共 48 根；30m：8 根；60m：4 根）。 */
    private const val BARS_PER_DAY_5M = 48
    private const val BARS_PER_DAY_30M = 8
    private const val BARS_PER_DAY_60M = 4

    /** 增量同步时尾部至少携带的重叠根数（兼作复权检测样本：约 2/2/2/5 个交易日）。 */
    private const val OVERLAP_5M = 96
    private const val OVERLAP_30M = 16
    private const val OVERLAP_60M = 8
    private const val OVERLAP_DAY = 5

    /** 复权检测阈值：重叠 bar 收盘价相对差超过此值（0.1%）判定除权除息（只比价格、不比成交量，避免跨源误判）。 */
    private const val ADJUST_DIFF_EPS = 1e-3

    /** 盘中实时刷新间隔：已有当日 bar 但距今超过该秒数（且仍在交易时段）→ 重新拉实时。 */
    private const val REALTIME_STALE_SEC = 300

    /**
     * 同步一只股票的 K 线（阻塞式，必须在后台线程调用）；持 [SYNC_LOCK] 读锁，
     * 与 [syncRealtime] 互斥（见对象说明）。
     *
     * @param context 用于读取服务器配置（[Settings]）与拼装请求地址
     * @param fallbackOn5mTimeout 5m 请求超时时立即停止后续周期，供页面快速回落本地数据
     * @throws IOException 四个周期的网络拉取全部失败，或启用快速回落时 5m 请求超时
     */
    fun syncStock(
        context: Context,
        db: DbHelper,
        code: String,
        fallbackOn5mTimeout: Boolean = false
    ) = SYNC_LOCK.read {
        val start = android.os.SystemClock.elapsedRealtime()
        val nowSec = System.currentTimeMillis() / 1000
        AppLog.net("同步开始: code=$code")
        var failures = 0

        try {
            syncTable(db, DbHelper.TABLE_K_5M, code, nowSec, BARS_PER_DAY_5M, OVERLAP_5M, FIRST_5M) {
                DatasourceApi.fetchKline(context, code, FREQ_5M, it)
            }
        } catch (e: IOException) {
            failures++
            AppLog.netError("同步 5m 失败: code=$code", e)
            if (fallbackOn5mTimeout && e is SocketTimeoutException) throw e
        }

        try {
            syncTable(db, DbHelper.TABLE_K_30M, code, nowSec, BARS_PER_DAY_30M, OVERLAP_30M, FIRST_30M) {
                DatasourceApi.fetchKline(context, code, FREQ_30M, it)
            }
        } catch (e: IOException) {
            failures++
            AppLog.netError("同步 30m 失败: code=$code", e)
        }

        try {
            syncTable(db, DbHelper.TABLE_K_60M, code, nowSec, BARS_PER_DAY_60M, OVERLAP_60M, FIRST_60M) {
                DatasourceApi.fetchKline(context, code, FREQ_60M, it)
            }
        } catch (e: IOException) {
            failures++
            AppLog.netError("同步 60m 失败: code=$code", e)
        }

        try {
            syncTable(db, DbHelper.TABLE_K_DAY, code, nowSec, 1, OVERLAP_DAY, FIRST_DAY) {
                DatasourceApi.fetchKline(context, code, FREQ_DAY, it)
            }
        } catch (e: IOException) {
            failures++
            AppLog.netError("同步日线失败: code=$code", e)
        }

        AppLog.net("同步结束: code=$code, 失败周期 $failures/4, 耗时 ${android.os.SystemClock.elapsedRealtime() - start}ms")
        if (failures == 4) throw IOException("K线同步全部失败: code=$code")
    }

    /**
     * 当日实时补齐（阻塞式，后台线程调用）。
     *
     * 编排约束：仅在历史同步成功入库之后由调用方触发（见 KLineActivity.ensureSynced）；
     * 持 [SYNC_LOCK] 写锁——等待所有在途 [syncStock] 历史请求完成入库后才开始，
     * 期间也不允许新的历史同步插入，两类数据源请求绝不并行。
     *
     * 实时数据只请求 5 分钟一个频率：以 5m 表的 [needsRealtime] 把关，缺当日
     * 数据或盘中陈旧时经 [DatasourceApi.fetchKlineRealtime] 取当日 5m bar，
     * 30m / 60m / day 全部由 [KLineSynth] 本地合成（桶边界与库存 bar 主键对齐），
     * 四表统一写入（qfq 且 is_realtime=1 标记；实时行不参与复权检测，随后历史
     * 同步拿到当天收盘权威值时按主键整行覆盖、标记归 0）。尽力而为：5m 拉取
     * 失败只记日志、不向外抛（页面照常展示历史数据）；单表合成/写入失败不影响其余表。
     */
    fun syncRealtime(context: Context, db: DbHelper, code: String) = SYNC_LOCK.write {
        val nowSec = System.currentTimeMillis() / 1000
        // 四表实时数据同源于一次 5m 拉取（整取整写），只需以 5m 表把关
        val summary = db.kBarSummary(DbHelper.TABLE_K_5M, code)
        val maxTs = summary.maxTimestamp.takeIf { summary.count > 0 }
        if (!needsRealtime(maxTs, nowSec)) return@write

        val bars5m: List<KBar>
        try {
            bars5m = DatasourceApi.fetchKlineRealtime(context, code, FREQ_5M)
        } catch (e: Exception) {
            AppLog.netError("实时补齐失败（回落历史展示）: code=$code freq=5m", e)
            return@write
        }
        if (bars5m.isEmpty()) {
            AppLog.net("实时 5m 无当日数据（非交易日或盘中无新 bar）: code=$code")
            return@write
        }
        val written = db.upsertKBars(DbHelper.TABLE_K_5M, code, bars5m, ADJUST_QFQ, isRealtime = true)
        AppLog.net("实时 5m 补齐: code=$code, 拉取 ${bars5m.size} 根, 写入 $written 行")

        // 其余频率由当日 5m 本地合成后写入（不再向数据源发 realtime 请求）
        synthRealtime(db, DbHelper.TABLE_K_30M, code, bars5m) { KLineSynth.to30m(it) }
        synthRealtime(db, DbHelper.TABLE_K_60M, code, bars5m) { KLineSynth.to60m(it) }
        synthRealtime(db, DbHelper.TABLE_K_DAY, code, bars5m) { KLineSynth.toDay(it) }
    }

    /** 由当日 5m bar 本地合成一个频率并整表写入（is_realtime=1）；单表失败只记日志。 */
    private fun synthRealtime(
        db: DbHelper,
        table: String,
        code: String,
        bars5m: List<KBar>,
        synth: (List<KBar>) -> List<KBar>
    ) {
        try {
            val bars = synth(bars5m)
            val written = db.upsertKBars(table, code, bars, ADJUST_QFQ, isRealtime = true)
            AppLog.net("实时 $table 由 5m 合成: code=$code, ${bars.size} 根, 写入 $written 行")
        } catch (e: Exception) {
            AppLog.netError("实时合成写入失败: table=$table, code=$code", e)
        }
    }

    /**
     * 该频率是否需要实时补齐：
     *  - 无当日 bar（存量最新 < 今日 0 点，或空表）→ 需要（是否交易日由服务端判定，
     *    非交易日实时接口返回空，调用无害）；
     *  - 已有当日 bar → 仅盘中时段且数据陈旧（超 [REALTIME_STALE_SEC]）才刷新，
     *    收盘后不再拉（当晚历史同步即为权威定稿值）。
     */
    fun needsRealtime(maxTimestampSec: Long?, nowSec: Long): Boolean {
        val todayStart = Instant.ofEpochSecond(nowSec).atZone(ZONE_BJ)
            .toLocalDate().atStartOfDay(ZONE_BJ).toEpochSecond()
        if (maxTimestampSec == null || maxTimestampSec < todayStart) return true
        return isTradingHours(nowSec) && nowSec - maxTimestampSec > REALTIME_STALE_SEC
    }

    /** 交易时段判定：工作日 9:25–15:05（含盘前盘后余量；午间休市拉取无害）。 */
    fun isTradingHours(nowSec: Long): Boolean {
        val zdt = Instant.ofEpochSecond(nowSec).atZone(ZONE_BJ)
        if (zdt.dayOfWeek.value > 5) return false
        val minutes = zdt.hour * 60 + zdt.minute
        return minutes in 9 * 60 + 25..15 * 60 + 5
    }

    /**
     * 单表同步：空表/含 bfq 行/仅存实时行 → 全量拉；否则尾部增量 + 复权检测（检测到除权除息则清空重拉）。
     *
     * 全量/增量以**历史定稿行**存量（[DbHelper.kBarSummaryHistory]）为准：
     * 新股首登时服务端回填未完成，历史拉取返回空（非异常）而实时行先入库，
     * 若按含实时行的总存量决策会误入增量路径、只带重叠下限根数，历史深度
     * 永远长不回去（死锁）；仅存实时行时按空表走全量即可自愈。
     *
     * @param fetch 按给定条数拉取（升序），网络失败抛 [IOException]
     */
    private fun syncTable(
        db: DbHelper,
        table: String,
        code: String,
        nowSec: Long,
        barsPerDay: Int,
        overlap: Int,
        firstTarget: Int,
        fetch: (Int) -> List<KBar>
    ) {
        // 1) 非前复权遗留（种子库 bfq）：整段作废，清掉走全量
        if (db.hasNonQfqBars(table, code)) {
            val removed = db.deleteKBars(table, code)
            AppLog.net("同步检测到非qfq历史，清空重建: table=$table, code=$code, 清掉 $removed 行")
        }

        // 2) 空表或仅存实时行（新股首登竞态）：按首次下载量级全量拉取
        val summary = db.kBarSummaryHistory(table, code)
        val fetched: List<KBar>
        if (summary.count == 0) {
            fetched = fetch(firstTarget)
            if (fetched.isEmpty()) {
                AppLog.net("同步 $table 返回空: code=$code, limit=$firstTarget")
                return
            }
            db.upsertKBars(table, code, fetched, ADJUST_QFQ)
            AppLog.net("同步 $table 全量入库: code=$code, ${fetched.size} 根")
            return
        }

        // 3) 增量：尾部缺失自最后一根历史行起估算 + 重叠下限
        val limit = syncLimit(estimateTailBars(summary.maxTimestamp, nowSec, barsPerDay), overlap, firstTarget)
        fetched = fetch(limit)
        if (fetched.isEmpty()) {
            AppLog.net("同步 $table 返回空: code=$code, limit=$limit")
            return
        }

        // 4) 复权检测：重叠已完成 bar 收盘价被重算 → 除权除息 → 清空重拉
        //    存量样本只取历史定稿行（is_realtime=0）：实时行（东财源）前复权基准
        //    与库存可能存在细微差异，参与比对会误判除权，故实时行不参与复权检测
        val overlapStored = db.queryKBarsSince(table, code, fetched.first().timestamp)
        if (detectAdjustChange(overlapStored, fetched)) {
            val removed = db.deleteKBars(table, code)
            AppLog.net("同步检测到除权除息，清空重建: table=$table, code=$code, 清掉 $removed 行")
            val full = fetch(firstTarget)
            db.upsertKBars(table, code, full, ADJUST_QFQ)
            AppLog.net("同步 $table 重建入库: code=$code, ${full.size} 根")
            return
        }

        val written = db.upsertKBars(table, code, fetched, ADJUST_QFQ)
        AppLog.net("同步 $table 增量入库: code=$code, 拉取 ${fetched.size} 根, 写入 $written 行")
    }

    /**
     * 估算 [fromSec] 所在日到 [toSec] 所在日之间（含两端）的交易日数：
     * 按周一~周五近似，不含节假日表；宁多勿少——多拉的重叠根由幂等 upsert 吸收。
     * 时序颠倒或同日均返回 1。
     */
    fun estimateTradingDays(fromSec: Long, toSec: Long): Int {
        if (toSec <= fromSec) return 1
        var day = Instant.ofEpochSecond(fromSec).atZone(ZONE_BJ).toLocalDate()
        val end = Instant.ofEpochSecond(toSec).atZone(ZONE_BJ).toLocalDate()
        var days = 0
        while (!day.isAfter(end)) {
            if (day.dayOfWeek.value <= 5) days++   // 1=周一 … 5=周五
            day = day.plusDays(1)
        }
        return days.coerceAtLeast(1)
    }

    /** 尾部缺失根数估算：最新存量 bar 至今的交易日数 × 每日根数。 */
    fun estimateTailBars(maxTimestampSec: Long, nowSec: Long, barsPerDay: Int): Int =
        estimateTradingDays(maxTimestampSec, nowSec) * barsPerDay

    /** 增量同步拉取条数：尾部估算与重叠下限取大，不超过全量上限。 */
    fun syncLimit(tailEstimate: Int, overlap: Int, firstTarget: Int): Int =
        maxOf(tailEstimate, overlap).coerceAtMost(firstTarget)

    /**
     * 复权基准变化检测：拉取结果与库中重叠的**已完成** bar，
     * 收盘价相对差超过 [ADJUST_DIFF_EPS] 即判定发生除权除息（前复权历史被重算）。
     *
     * 跳过本次拉取的最新一根（可能仍在盘中变动）与不早于库存最新时刻的 bar
     * （库存最新一根当初写入时也可能未收盘）；只比价格不比成交量/额
     * （不同数据来源量纲可能不同，避免误报）。
     */
    fun detectAdjustChange(stored: List<KBar>, fetched: List<KBar>): Boolean {
        if (stored.isEmpty() || fetched.isEmpty()) return false
        val storedMax = stored.maxOf { it.timestamp }
        val fetchedMax = fetched.maxOf { it.timestamp }
        val cutoff = minOf(storedMax, fetchedMax)   // 重叠且双方均已完成
        val byTs = stored.associateBy { it.timestamp }
        for (bar in fetched) {
            if (bar.timestamp >= cutoff) continue
            val old = byTs[bar.timestamp] ?: continue
            if (old.close == 0.0) continue
            if (abs(bar.close - old.close) / old.close > ADJUST_DIFF_EPS) return true
        }
        return false
    }
}
