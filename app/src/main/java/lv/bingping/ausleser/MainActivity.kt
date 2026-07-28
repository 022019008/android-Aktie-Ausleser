package lv.bingping.ausleser

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import lv.bingping.ausleser.data.DbHelper
import lv.bingping.ausleser.ui.AddStockBottomSheet
import lv.bingping.ausleser.ui.GroupManageBottomSheet
import lv.bingping.ausleser.ui.StockListAdapter
import lv.bingping.ausleser.ui.SwipeRevealCallback
import lv.bingping.ausleser.util.AppLog

/**
 * 主页。
 *
 * 结构：
 *  - 第一层：顶部工具栏（[R.id.top_toolbar]），内含“自选”按钮；
 *  - 第二层：顶部工具栏子栏（[R.id.sub_bar]），点击“自选”后显示全部自选分组，
 *    分组来自数据库表 t_selber_select_group，横向排列并可横向滑动；
 *  - 第三层：股票工具栏（[R.id.stocks_toolbar]）+ 当前分组的自选列表，
 *    列表数据来自 t_selber_select_stock，右侧“+”可按代码/名称/拼音首字母搜索添加。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: DbHelper

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
            // 同步按钮点击功能待定，先占位不做事（行情数据源接入前仅记录网络日志）
            onSync = { stock ->
                AppLog.net("点击同步（占位，未接入行情数据源）: code=${stock.code}, name=${stock.name}")
            },
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

    /** 从当前分组移除一只股票并刷新列表。 */
    private fun removeStock(stock: lv.bingping.ausleser.data.SelectStock) {
        dbHelper.deleteStock(stock.id)
        reloadStocks()
    }

    /** 打开指定股票的 K 线图页面。 */
    private fun openKLine(stock: lv.bingping.ausleser.data.SelectStock) {
        startActivity(KLineActivity.intent(this, stock.code, stock.name))
    }

    override fun onDestroy() {
        dbHelper.close()
        super.onDestroy()
    }
}
