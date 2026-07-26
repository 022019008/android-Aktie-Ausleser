package lv.bingping.ausleser.ui

import android.content.Context
import android.view.LayoutInflater
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
import lv.bingping.ausleser.data.StockCatalog

/**
 * “添加自选”搜索弹层：按 代码 / 名称 / 拼音首字母 实时过滤股票宇宙，
 * 点击候选项即加入当前分组（[groupId]），并通过 [onAdded] 通知外部刷新列表。
 */
class AddStockBottomSheet(
    private val context: Context,
    private val dbHelper: DbHelper,
    private val groupId: Long,
    private val onAdded: () -> Unit
) {
    private val dialog = BottomSheetDialog(context)
    private val existing = dbHelper.queryStockCodes(groupId).toMutableSet()
    private val results = StockCatalog.ALL.toMutableList()
    private lateinit var adapter: StockSearchAdapter

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_stock, null)
        val editText = view.findViewById<EditText>(R.id.et_search)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_search)
        val emptyView = view.findViewById<TextView>(R.id.tv_search_empty)

        adapter = StockSearchAdapter(
            data = results,
            isAdded = { code -> existing.contains(code) },
            onPick = ::pick
        )
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        view.findViewById<ImageButton>(R.id.btn_search_close).setOnClickListener { dialog.dismiss() }

        editText.doAfterTextChanged { s ->
            val list = StockCatalog.search(s?.toString().orEmpty())
            results.clear()
            results.addAll(list)
            adapter.notifyDataSetChanged()
            emptyView.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
        }
        dialog.show()
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
}
