package lv.bingping.ausleser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import lv.bingping.ausleser.R
import lv.bingping.ausleser.data.DbHelper
import lv.bingping.ausleser.data.DatasourceApi
import lv.bingping.ausleser.data.KLineSync
import lv.bingping.ausleser.data.Settings
import lv.bingping.ausleser.util.AppLog
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * 盘中轮询前台服务：每 3 分钟从自建数据源同步自选股 K 线（补足缺省K线）。
 *
 * 与打开 K 线页触发的 [KLineSync] 互补：页内同步面向"正在看的股票"，
 * 本服务面向"全部自选股"后台保鲜，两者写同一组表、同为前复权 upsert，幂等不冲突。
 *
 * 同步策略（增量）：
 *  - 5m / 30m / day 分别以 since=本地最新时间戳 拉取；无存档的首次回退 limit（500 / 320 / 260 根）；
 *  - 按主键 (code,timestamp) upsert，同一时间戳的 bar 以最新值反复覆盖；
 *  - 服务生命周期内首轮将全部自选股登记到服务端（幂等），
 *    使服务端把它们排进同步与盘中实时任务。
 *
 * 时段门控：周一~周五、Asia/Shanghai 09:00–20:59（延到 20:59 是为接住
 * 服务端 20:13 晚间任务落库的当日权威数据）；服务启动后的第一轮始终执行
 * （不受门控），保证随时打开 App 都能立即补足；周末与深夜跳过不空转。
 *
 * 已知取舍：不使用 WakeLock / AlarmManager——前台服务 + 充电常亮的
 * 局域网测试机足够；深度 Doze 下 postDelayed 可能延迟。
 */
class PollService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var dbHelper: DbHelper

    /** 本服务生命周期内已向服务端登记过的代码（避免重复请求）。 */
    private val registeredCodes = mutableSetOf<String>()
    private var firstCycleDone = false
    private var warnedNotConfigured = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            executor.execute { doPollCycle() }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DbHelper(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        AppLog.net("PollService 已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 去重：重复 start() 时不叠加轮询循环
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        dbHelper.close()
        AppLog.net("PollService 已停止")
        super.onDestroy()
    }

    /** 一轮同步：后台线程遍历自选股，逐只增量取 5m / 30m / day。 */
    private fun doPollCycle() {
        if (!Settings.isConfigured(this)) {
            if (!warnedNotConfigured) {
                warnedNotConfigured = true
                AppLog.net("poll: 未配置服务器地址，跳过本轮（设置页配置后自动恢复）")
            }
            return
        }
        if (firstCycleDone && !shouldSyncNow()) {
            AppLog.net("poll: 非同步时段（周一至周五 09:00–20:59 北京时间），跳过")
            return
        }
        val stocks = dbHelper.queryAllSelectStocks()
        if (stocks.isEmpty()) {
            firstCycleDone = true
            return
        }
        ensureRegistered(stocks.map { it.code to it.name })
        AppLog.net("poll: 开始同步 ${stocks.size} 只自选股")
        for ((i, stock) in stocks.withIndex()) {
            try {
                syncStock(stock.code)
            } catch (e: Exception) {
                AppLog.netError("poll: ${stock.code} 同步失败", e)
            }
            if (i < stocks.size - 1) Thread.sleep(STOCK_INTERVAL_MS)
        }
        AppLog.net("poll: 本轮同步完成")
        firstCycleDone = true
    }

    /** 将全部自选股登记到服务端（已激活的股服务端秒回，幂等）。 */
    private fun ensureRegistered(pairs: List<Pair<String, String>>) {
        for ((code, name) in pairs) {
            if (registeredCodes.contains(code)) continue
            try {
                if (DatasourceApi.registerStock(this, code, name)) registeredCodes.add(code)
            } catch (e: Exception) {
                AppLog.netError("poll: 登记失败 code=$code", e)
            }
        }
    }

    /** 单只股票增量同步：5m + 30m + day；无存档时首次回退 limit 取数。 */
    private fun syncStock(code: String) {
        syncTable(DbHelper.TABLE_K_5M, code, FREQ_5M, INITIAL_LIMIT_5M)
        syncTable(DbHelper.TABLE_K_30M, code, FREQ_30M, INITIAL_LIMIT_30M)
        syncTable(DbHelper.TABLE_K_DAY, code, FREQ_DAY, INITIAL_LIMIT_DAY)
    }

    /**
     * 单表增量同步：since=本地水位拉全部新 bar；无存档时首次取最近 [initialLimit] 根。
     * 入库复权类型与 [KLineSync] 一致（qfq），同一时间戳按主键覆盖。
     */
    private fun syncTable(table: String, code: String, freq: String, initialLimit: Int) {
        val watermark = dbHelper.maxKTimestamp(table, code)
        val bars = if (watermark == 0L) {
            DatasourceApi.fetchKline(this, code, freq, initialLimit)
        } else {
            DatasourceApi.fetchKlineSince(this, code, freq, watermark)
        }
        if (bars.isNotEmpty()) dbHelper.upsertKBars(table, code, bars, KLineSync.ADJUST_QFQ)
    }

    /** 时段门控：周一至周五、Asia/Shanghai 09:00–20:59。 */
    private fun shouldSyncNow(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(SHANGHAI_TZ))
        when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY, Calendar.SUNDAY -> return false
        }
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return minutes in WINDOW_START_MINUTES..WINDOW_END_MINUTES
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            getString(R.string.poll_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(getString(R.string.poll_notification_title))
            .setContentText(getString(R.string.poll_notification_text))
            .setOngoing(true)
            .build()

    companion object {
        private const val NOTIF_CHANNEL_ID = "poll_channel"
        private const val NOTIF_ID = 1001

        /** 轮询间隔：3 分钟。 */
        private const val POLL_INTERVAL_MS = 3 * 60 * 1000L

        /** 个股之间的间隔，避免连发请求。 */
        private const val STOCK_INTERVAL_MS = 200L

        private const val FREQ_5M = "5m"
        private const val FREQ_30M = "30m"
        private const val FREQ_DAY = "day"

        /** 无存档时首次取数根数（与服务端缺省窗口一致）。 */
        private const val INITIAL_LIMIT_5M = 500
        private const val INITIAL_LIMIT_30M = 320
        private const val INITIAL_LIMIT_DAY = 260

        private const val SHANGHAI_TZ = "Asia/Shanghai"
        private const val WINDOW_START_MINUTES = 9 * 60        // 09:00
        private const val WINDOW_END_MINUTES = 20 * 60 + 59    // 20:59

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PollService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PollService::class.java))
        }
    }
}
