package lv.bingping.ausleser

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import lv.bingping.ausleser.data.Chan
import lv.bingping.ausleser.data.DatasourceApi
import lv.bingping.ausleser.data.DbHelper
import lv.bingping.ausleser.data.KBar
import lv.bingping.ausleser.data.KLineSync
import lv.bingping.ausleser.data.KLineSynth
import lv.bingping.ausleser.ui.KLineChartView
import lv.bingping.ausleser.util.AppLog
import java.util.concurrent.Executors

/**
 * K 线图页面：展示某只股票的 K 线，右上角下拉切换周期
 * （5分钟 / 30分钟 / 60分钟 / 日线 / 周线），默认日线。
 *
 * 进入页面先经 [KLineSync.syncStock] 在后台同步该股历史 K 线（前复权入库，
 * 首次下载 5m/30m 两年、60m/日线五年，其后增量补尾、除权除息时自动重建），
 * 历史同步成功入库后再经 [KLineSync.syncRealtime] 按需补齐当日实时 bar
 * （库存定稿前历史接口不含当日数据；历史全部失败则跳过实时请求）；
 * 同步失败仅提示，仍展示本地已有数据；同一页面生命周期内只同步一次。
 *
 * 数据源（同步后全部读库）：
 *  - 5分钟：库表 t_k_5m；30分钟：t_k_30m；60分钟：t_k_60m
 *   （均由自建数据源 Aktie_datasource 网络拉取）；
 *  - 日线：库表 t_k_day；周线：由日线内存聚合（[KLineSynth.toWeekly]）。
 *
 * 图上叠加缠论笔与中枢（[Chan] 纯算法计算）：本级别笔 + 本级别中枢 +
 * 次级别中枢（日线图叠 30 分钟中枢、60 分钟图叠 30 分钟中枢、
 * 30 分钟图叠 5 分钟中枢、周线图叠日线中枢）。
 *
 * 默认可见条数按屏幕方向：竖屏 [KLineChartView.DEFAULT_PORTRAIT_COUNT] 根，
 * 横屏 [KLineChartView.DEFAULT_LANDSCAPE_COUNT] 根；旋转后恢复周期与视窗位置。
 */
class KLineActivity : AppCompatActivity() {

    private lateinit var dbHelper: DbHelper
    private lateinit var chart: KLineChartView
    private lateinit var emptyView: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 加载序号，切换周期时丢弃过期结果。 */
    private var loadSeq = 0

    private var code = ""
    private var name = ""
    private var groupId = -1L
    private var groupName = ""

    /** 当前周期下标（对应 R.array.kline_periods），默认日线。 */
    private var periodIndex = PERIOD_DAY

    /** 本页生命周期内是否已同步过（旋转后经 savedInstanceState 保留，避免重复拉取）。 */
    private var synced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kline)

        code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1L)
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME).orEmpty()

        val toolbar = findViewById<MaterialToolbar>(R.id.kline_toolbar)
        toolbar.title = name
        toolbar.subtitle = code
        toolbar.setNavigationOnClickListener { finish() }

        chart = findViewById(R.id.kline_chart)
        emptyView = findViewById(R.id.tv_kline_empty)

        dbHelper = DbHelper(this)

        // 恢复旋转前的周期、视窗与同步状态
        var restoredCount: Float? = null
        var restoredAnchor: Long? = null
        savedInstanceState?.let { s ->
            periodIndex = s.getInt(KEY_PERIOD, PERIOD_DAY)
            restoredCount = s.getFloat(KEY_COUNT, 0f).takeIf { it > 0f }
            restoredAnchor = s.getLong(KEY_ANCHOR, -1L).takeIf { it > 0L }
            synced = s.getBoolean(KEY_SYNCED, false)
        }

        setupPeriodSpinner(toolbar)
        reload(anchorTs = restoredAnchor, count = restoredCount)
    }

    /** 右上角周期下拉；选中与当前一致时跳过（也挡掉首次布局的自动回调）。 */
    private fun setupPeriodSpinner(toolbar: MaterialToolbar) {
        toolbar.inflateMenu(R.menu.kline_menu)
        val spinner = toolbar.menu.findItem(R.id.action_period).actionView as Spinner
        // 顶栏为深色底：下拉箭头染白，便于辨识
        spinner.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        val items = resources.getStringArray(R.array.kline_periods)
        // 顶栏为深色底：选中项文字用白色，下拉列表保持默认
        spinner.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.setTextColor(Color.WHITE)
                return v
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(periodIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == periodIndex) return
                periodIndex = position
                reload(anchorTs = null, count = null)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 按当前周期后台加载 K 线并刷新图表；[anchorTs]/[count] 用于旋转恢复。
     *
     * 容错：数据源连接报错（或 DB 读取异常）一律回落存量数据展示——
     * 同步失败仅提示（[ensureSynced] 内部消化），读库失败降级为空列表，
     * 后台任务整体兜底捕获，任何异常都不会让页面停留在"同步中…"。
     */
    private fun reload(anchorTs: Long?, count: Float?) {
        val seq = ++loadSeq
        val period = periodIndex
        if (!synced) {
            // 首次进入：先展示同步提示，后台同步完成后再读库绘图
            emptyView.text = getString(R.string.kline_syncing)
            emptyView.visibility = View.VISIBLE
        }
        executor.execute {
            try {
                ensureSynced()
                val bars = try {
                    loadBars(period)
                } catch (e: Exception) {
                    AppLog.netError("读库失败，按无数据展示: code=$code period=$period", e)
                    emptyList()
                }
                // 缠论：本级别笔与中枢；次级别中枢（日线←30m、30m←5m、周线←日线，5m 无更低级别）
                val chan = Chan.analyze(bars)
                val subZs = try {
                    loadSubBars(period)?.let { Chan.analyze(it).zhongshu }
                } catch (e: Exception) {
                    AppLog.netError("次级别读库失败，跳过次级别中枢: code=$code period=$period", e)
                    null
                }
                mainHandler.post {
                    if (seq != loadSeq || isDestroyed) return@post
                    chart.intraday = period == PERIOD_5M || period == PERIOD_30M || period == PERIOD_60M
                    chart.setData(bars, count ?: defaultCount(), anchorTs)
                    chart.setChanOverlay(chan.bi, chan.zhongshu, subZs)
                    emptyView.text = getString(R.string.kline_empty)
                    emptyView.visibility = if (bars.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                // 兜底：后台任务不得异常中止，否则页面会永远停留在"同步中…"
                AppLog.netError("K线加载流程异常: code=$code period=$period", e)
                mainHandler.post {
                    if (seq == loadSeq && !isDestroyed) {
                        emptyView.text = getString(R.string.kline_empty)
                        emptyView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    /** 页面生命周期内只同步一次；同步失败仅提示，随后继续展示本地数据。
     *
     * 捕获放宽到 [Exception]：数据源不可达（IOException）之外，服务端异常响应、
     * JSON/DB 运行期错误等同样不得阻断后续的本地读库绘图。
     */
    private fun ensureSynced() {
        if (synced) return
        synced = true
        // 兜底登记：确保服务端已跟踪该股（幂等；新股会触发服务端后台全量回填，
        // 首次打开可能尚无数据，稍后重开即有。种子库预置股也经此自动登记。）
        try {
            DatasourceApi.registerStock(
                applicationContext,
                code,
                name,
                groupId.takeIf { it > 0 },
                groupName
            )
        } catch (e: Exception) {
            AppLog.netError("服务端登记失败（不影响本次读库）: code=$code", e)
        }
        var historyOk = false
        try {
            KLineSync.syncStock(
                applicationContext,
                dbHelper,
                code,
                fallbackOn5mTimeout = true
            )
            historyOk = true
        } catch (e: Exception) {
            AppLog.netError("K线同步失败，回落本地数据: code=$code", e)
            mainHandler.post {
                if (!isDestroyed) {
                    Toast.makeText(this, R.string.kline_sync_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        // 当日实时补齐：仅在历史同步成功入库后执行（尽力而为，实时失败不影响展示）。
        // 历史接口定稿前补不到当日 bar，改走实时接口；历史全部失败（数据源不可达）时
        // 实时接口同属一个数据源，不再发无谓请求。
        if (historyOk) {
            try {
                KLineSync.syncRealtime(applicationContext, dbHelper, code)
            } catch (e: Exception) {
                AppLog.netError("实时补齐异常（展示历史数据）: code=$code", e)
            }
        }
    }

    private fun loadBars(period: Int): List<KBar> =
        when (period) {
            PERIOD_5M -> dbHelper.queryKBars(DbHelper.TABLE_K_5M, code, LIMIT_5M)
            PERIOD_30M -> dbHelper.queryKBars(DbHelper.TABLE_K_30M, code, LIMIT_30M)
            PERIOD_60M -> dbHelper.queryKBars(DbHelper.TABLE_K_60M, code, LIMIT_60M)
            PERIOD_DAY -> dbHelper.queryKBars(DbHelper.TABLE_K_DAY, code)
            else -> KLineSynth.toWeekly(dbHelper.queryKBars(DbHelper.TABLE_K_DAY, code))
        }

    /** 次级别 K 线（用于叠加次级别中枢）；5 分钟无更低级别返回 null。 */
    private fun loadSubBars(period: Int): List<KBar>? =
        when (period) {
            PERIOD_30M -> dbHelper.queryKBars(DbHelper.TABLE_K_5M, code, LIMIT_5M)
            PERIOD_60M -> dbHelper.queryKBars(DbHelper.TABLE_K_30M, code, LIMIT_30M)
            PERIOD_DAY -> dbHelper.queryKBars(DbHelper.TABLE_K_30M, code, LIMIT_30M)
            PERIOD_WEEK -> dbHelper.queryKBars(DbHelper.TABLE_K_DAY, code)
            else -> null
        }

    /** 竖屏 200 根、横屏 500 根。 */
    private fun defaultCount(): Float =
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            KLineChartView.DEFAULT_PORTRAIT_COUNT
        } else {
            KLineChartView.DEFAULT_LANDSCAPE_COUNT
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PERIOD, periodIndex)
        outState.putFloat(KEY_COUNT, chart.visibleCount)
        outState.putBoolean(KEY_SYNCED, synced)
        chart.rightEdgeTimestamp()?.let { outState.putLong(KEY_ANCHOR, it) }
    }

    override fun onDestroy() {
        executor.shutdown()
        dbHelper.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "extra_code"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_GROUP_NAME = "extra_group_name"

        private const val KEY_PERIOD = "key_period"
        private const val KEY_COUNT = "key_count"
        private const val KEY_ANCHOR = "key_anchor"
        private const val KEY_SYNCED = "key_synced"

        private const val PERIOD_5M = 0
        private const val PERIOD_30M = 1
        private const val PERIOD_60M = 2
        private const val PERIOD_DAY = 3
        private const val PERIOD_WEEK = 4

        /** 5 分钟读库上限（两年约 2.3 万根，与 KLineSync.FIRST_5M 对齐）。 */
        private const val LIMIT_5M = KLineSync.FIRST_5M

        /** 30 分钟读库上限（两年约 3900 根，与 KLineSync.FIRST_30M 对齐）。 */
        private const val LIMIT_30M = KLineSync.FIRST_30M

        /** 60 分钟读库上限（五年约 4800 根，与 KLineSync.FIRST_60M 对齐）。 */
        private const val LIMIT_60M = KLineSync.FIRST_60M

        fun intent(
            activity: android.content.Context,
            code: String,
            name: String,
            groupId: Long,
            groupName: String
        ): Intent =
            Intent(activity, KLineActivity::class.java)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_GROUP_ID, groupId)
                .putExtra(EXTRA_GROUP_NAME, groupName)
    }
}
