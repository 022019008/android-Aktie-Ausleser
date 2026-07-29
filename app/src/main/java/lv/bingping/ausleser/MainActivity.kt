package lv.bingping.ausleser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import lv.bingping.ausleser.data.DatasourceApi
import lv.bingping.ausleser.data.DbHelper
import lv.bingping.ausleser.data.KLineSync
import lv.bingping.ausleser.data.SelectStock
import lv.bingping.ausleser.ui.AddStockBottomSheet
import lv.bingping.ausleser.ui.GroupManageBottomSheet
import lv.bingping.ausleser.ui.StockListAdapter
import lv.bingping.ausleser.ui.SwipeRevealCallback
import lv.bingping.ausleser.util.AppLog
import java.util.concurrent.Executors

/**
 * 主页。
 *
 * 结构：
 *  - 第一层：顶部工具栏（[R.id.top_toolbar]），标题右侧紧贴“自选”按钮，
 *    最右侧齿轮打开服务器设置页（[SettingsActivity]）；
 *  - 第二层：顶部工具栏子栏（[R.id.sub_bar]），点击“自选”后显示全部自选分组，
 *    分组来自数据库表 t_selber_select_group，横向排列并可横向滑动，
 *    右侧“群组管理”用于增删改分组；
 *  - 第三层：股票工具栏（[R.id.stocks_toolbar]）+ 当前分组的自选列表，
 *    列表数据来自 t_selber_select_stock，右侧“+”可按代码/名称/拼音首字母搜索添加。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: DbHelper

    /** 网络操作（同步请求、服务端登记）用后台线程，模式同 KLineActivity。 */
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var btnSelberSelect: Button
    private lateinit var btnGroupManage: Button
    private lateinit var subBar: View
    private lateinit var groupChipGroup: ChipGroup

    private lateinit var tvStocksTitle: TextView
    private lateinit var btnAddStock: ImageButton
    private lateinit var rvStocks: RecyclerView
    private lateinit var tvStocksEmpty: TextView
    private lateinit var stockAdapter: StockListAdapter
    private lateinit var swipeCallback: SwipeRevealCallback

    /** 当前选中的分组 id，刷新分组时用于保留选中。 */
    private var selectedGroupId: Long = -1L

    /** 正在后台同步 K 线的股票代码（主线程访问），防重复点击。 */
    private val syncingCodes = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 首次启动时安装 assets 预置种子库（未打包种子库时为空操作，回退空库新建）
        DbHelper.installIfNeeded(this)
        dbHelper = DbHelper(this)

        btnSelberSelect = findViewById(R.id.btn_selber_select)
        btnGroupManage = findViewById(R.id.btn_group_manage)
        subBar = findViewById(R.id.sub_bar)
        groupChipGroup = findViewById(R.id.group_chip_group)

        tvStocksTitle = findViewById(R.id.tv_stocks_title)
        btnAddStock = findViewById(R.id.btn_add_stock)
        rvStocks = findViewById(R.id.rv_stocks)
        tvStocksEmpty = findViewById(R.id.tv_stocks_empty)

        stockAdapter = StockListAdapter(
            onDelete = { stock -> removeStock(stock) },
            // 同步按钮：后台补全该股本地 K 线（先服务端登记再拉取，结果 Toast 提示）
            onSync = { stock -> syncStockNow(stock) },
            onClick = { stock -> openKLine(stock) }
        )
        rvStocks.layoutManager = LinearLayoutManager(this)
        rvStocks.adapter = stockAdapter

        swipeCallback = SwipeRevealCallback()
        ItemTouchHelper(swipeCallback).attachToRecyclerView(rvStocks)
        rvStocks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                swipeCallback.closeOpen()
            }
        })
        rvStocks.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (e.action == android.view.MotionEvent.ACTION_DOWN && swipeCallback.openVH != null) {
                    val vh = rv.findChildViewUnder(e.x, e.y)?.let { rv.findContainingViewHolder(it) }
                    if (vh !== swipeCallback.openVH) swipeCallback.closeOpen()
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) = Unit

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        })

        btnSelberSelect.setOnClickListener { toggleSubBar() }
        btnGroupManage.setOnClickListener {
            GroupManageBottomSheet(this, dbHelper) { refreshGroups() }.show()
        }
        btnAddStock.setOnClickListener {
            if (selectedGroupId > 0) {
                AddStockBottomSheet(this, dbHelper, selectedGroupId) { reloadStocks() }.show()
            }
        }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        groupChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = group.findViewById<Chip>(checkedIds.first())
                chip?.let {
                    selectedGroupId = it.tag as? Long ?: -1L
                    tvStocksTitle.text = it.text
                    reloadStocks()
                }
            }
        }

        // 初始即按默认选中分组加载列表（子栏默认隐藏，但选中态与列表需就绪）
        refreshGroups()
    }

    /** 展开 / 收起顶部工具栏子栏。 */
    private fun toggleSubBar() {
        subBar.visibility = if (subBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** 从数据库读取全部自选分组并重建 Chip；尽量保留当前选中分组。 */
    private fun refreshGroups() {
        val groups = dbHelper.querySelectGroups()

        groupChipGroup.removeAllViews()
        var restored = false
        for (group in groups) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = group.name
                isCheckable = true
                tag = group.id
            }
            groupChipGroup.addView(chip)
            if (group.id == selectedGroupId) {
                chip.isChecked = true
                restored = true
            }
        }

        // 选中的分组已不存在或首次加载：选中第一个
        if (!restored && groupChipGroup.childCount > 0) {
            (groupChipGroup.getChildAt(0) as Chip).isChecked = true
        }
        if (groupChipGroup.childCount == 0) {
            selectedGroupId = -1L
            tvStocksTitle.text = ""
            reloadStocks()
        }
    }

    /** 加载当前选中分组的股票列表并切换空态。 */
    private fun reloadStocks() {
        if (::swipeCallback.isInitialized) swipeCallback.closeOpen()
        val stocks = if (selectedGroupId > 0) dbHelper.queryStocks(selectedGroupId) else emptyList()
        stockAdapter.submit(stocks)
        tvStocksEmpty.visibility = if (stocks.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 从当前分组移除一只股票并刷新列表；全部分组都不再引用时通知服务端停用跟踪。 */
    private fun removeStock(stock: SelectStock) {
        dbHelper.deleteStock(stock.id)
        if (dbHelper.countStocksByCode(stock.code) == 0) {
            executor.execute {
                try {
                    DatasourceApi.unregisterStock(this, stock.code)
                } catch (e: Exception) {
                    AppLog.netError("服务端停用跟踪失败: code=${stock.code}", e)
                }
            }
        }
        reloadStocks()
    }

    /**
     * 同步按钮：后台补全该股本地 K 线（与进入 K 线页同一套 [KLineSync.syncStock]：
     * 先向服务端登记，再按频率拉取入库，含复权检测与首次全量策略）。
     * 结果回主线程 Toast；同一只股票同步进行中时忽略重复点击。
     */
    private fun syncStockNow(stock: SelectStock) {
        if (!syncingCodes.add(stock.code)) {
            Toast.makeText(this, getString(R.string.sync_in_progress, stock.name),
                Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.sync_in_progress, stock.name),
            Toast.LENGTH_SHORT).show()
        executor.execute {
            val ok = try {
                DatasourceApi.registerStock(applicationContext, stock.code, stock.name)
                KLineSync.syncStock(applicationContext, dbHelper, stock.code)
                true
            } catch (e: Exception) {
                AppLog.netError("K线同步失败: code=${stock.code}", e)
                false
            }
            mainHandler.post {
                syncingCodes.remove(stock.code)
                if (!isDestroyed) {
                    Toast.makeText(
                        this,
                        if (ok) getString(R.string.sync_complete, stock.name)
                        else getString(R.string.sync_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** 打开指定股票的 K 线图页面。 */
    private fun openKLine(stock: SelectStock) {
        startActivity(KLineActivity.intent(this, stock.code, stock.name))
    }

    override fun onDestroy() {
        executor.shutdown()
        dbHelper.close()
        super.onDestroy()
    }
}
