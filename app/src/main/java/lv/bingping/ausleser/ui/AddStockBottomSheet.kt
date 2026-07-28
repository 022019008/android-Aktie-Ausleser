package lv.bingping.ausleser.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import lv.bingping.ausleser.R
import lv.bingping.ausleser.data.DbHelper
import lv.bingping.ausleser.data.Stock
import lv.bingping.ausleser.data.StockSearchApi
import java.io.IOException
import java.util.concurrent.Executors

/**
 * “添加自选”搜索弹层：点击候选项即加入当前分组（[groupId]），
 * 并通过 [onAdded] 通知外部刷新列表。
 *
 * 候选数据来自东方财富联想接口（[StockSearchApi]）：输入防抖 [SEARCH_DEBOUNCE_MS]
 * 后按 [shouldSearch] 阈值触发后台搜索，过期响应按请求序号丢弃。
 */
class AddStockBottomSheet(
    private val context: Context,
    private val dbHelper: DbHelper,
    private val groupId: Long,
    private val onAdded: () -> Unit
) {
    private val dialog = BottomSheetDialog(context)
    private val existing = dbHelper.queryStockCodes(groupId).toMutableSet()
    private val results = mutableListOf<Stock>()
    private lateinit var adapter: StockSearchAdapter
    private lateinit var emptyView: TextView

    /** 搜索用单线程池；弹层关闭时 shutdown。 */
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 请求序号，用于丢弃过期响应（避免慢请求覆盖新结果）。 */
    private var requestSeq = 0

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_stock, null)
        val editText = view.findViewById<EditText>(R.id.et_search)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_search)
        emptyView = view.findViewById(R.id.tv_search_empty)

        adapter = StockSearchAdapter(
            data = results,
            isAdded = { code -> existing.contains(code) },
            onPick = ::pick
        )
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        // 初始无输入：不显示空态，仅展示搜索框提示
        emptyView.visibility = View.GONE

        view.findViewById<ImageButton>(R.id.btn_search_close).setOnClickListener { dialog.dismiss() }

        editText.doAfterTextChanged { editable ->
            mainHandler.removeCallbacksAndMessages(null)
            val query = editable?.toString()?.trim().orEmpty()
            if (!shouldSearch(query)) {
                // 未达搜索阈值：清掉旧结果，不显示“无匹配”
                results.clear()
                adapter.notifyDataSetChanged()
                emptyView.visibility = View.GONE
                return@doAfterTextChanged
            }
            mainHandler.postDelayed({ searchAsync(query) }, SEARCH_DEBOUNCE_MS)
        }

        // 处理状态栏 + 输入法 insets，保证结果列表始终在键盘上方
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, ime.bottom)
            insets
        }

        dialog.setContentView(view)
        dialog.behavior.apply {
            isFitToContents = false
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.setOnShowListener {
            view.post {
                ViewCompat.requestApplyInsets(view)
                editText.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.setOnDismissListener {
            mainHandler.removeCallbacksAndMessages(null)
            executor.shutdown()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
        }
        dialog.show()
    }

    /** 后台线程搜索，完成后切回主线程按序号更新候选列表。 */
    private fun searchAsync(query: String) {
        val seq = ++requestSeq
        executor.execute {
            val list = try {
                StockSearchApi.search(query)
            } catch (e: IOException) {
                mainHandler.post {
                    if (seq == requestSeq && !executor.isShutdown) {
                        showResults(emptyList())
                        Toast.makeText(context, R.string.search_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                return@execute
            }
            mainHandler.post {
                if (seq == requestSeq && !executor.isShutdown) showResults(list)
            }
        }
    }

    /** 用搜索结果替换候选列表并切换空态。 */
    private fun showResults(list: List<Stock>) {
        results.clear()
        results.addAll(list)
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 搜索触发阈值：
     *  - 含中文（名称）：>=1 字即搜；
     *  - 纯数字（代码）：>5 位即搜（A股代码为 6 位）；
     *  - 纯字母（拼音首字母）：>=3 位即搜。
     */
    private fun shouldSearch(query: String): Boolean {
        if (query.any { it in '\u4E00'..'\u9FFF' }) return true
        return when {
            query.isEmpty() -> false
            query.all { it.isDigit() } -> query.length > 5
            query.all { it.isLetter() } -> query.length >= 3
            else -> false
        }
    }

    private fun pick(stock: Stock) {
        if (!existing.add(stock.code)) {
            Toast.makeText(context, R.string.stock_exists, Toast.LENGTH_SHORT).show()
            return
        }
        val rowId = dbHelper.insertStock(groupId, stock.code, stock.name)
        if (rowId >= 0L) {
            Toast.makeText(context, context.getString(R.string.stock_added, stock.name), Toast.LENGTH_SHORT).show()
            onAdded()
        } else {
            existing.remove(stock.code)
            Toast.makeText(context, R.string.stock_exists, Toast.LENGTH_SHORT).show()
        }
        val pos = results.indexOf(stock)
        if (pos >= 0) adapter.notifyItemChanged(pos)
    }

    companion object {
        /** 输入防抖时长：停止输入后才发起搜索。 */
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
