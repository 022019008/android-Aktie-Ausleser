package lv.bingping.ausleser.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import lv.bingping.ausleser.R
import lv.bingping.ausleser.data.SelectStock
import lv.bingping.ausleser.data.Stock

/** 当前分组已添加股票的列表适配器。左滑揭示“同步 / 删除”两个操作按钮，整行点击进入 K 线图。 */
class StockListAdapter(
    private val onDelete: (SelectStock) -> Unit,
    private val onSync: (SelectStock) -> Unit,
    private val onClick: (SelectStock) -> Unit
) : RecyclerView.Adapter<StockListAdapter.VH>() {

    private val data = mutableListOf<SelectStock>()

    fun submit(list: List<SelectStock>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_select_stock, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val stock = data[position]
        holder.code.text = stock.code
        holder.name.text = stock.name
        holder.delete.setOnClickListener { onDelete(stock) }
        holder.sync.setOnClickListener { onSync(stock) }
        holder.itemView.setOnClickListener { onClick(stock) }
        // 复用 / 重绑时复位滑动状态，避免残留展开
        (holder.itemView as SwipeRevealLayout).setOffset(0f)
    }

    override fun getItemCount(): Int = data.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val code: TextView = v.findViewById(R.id.tv_stock_code)
        val name: TextView = v.findViewById(R.id.tv_stock_name)
        val sync: ImageButton = v.findViewById(R.id.btn_sync_stock)
        val delete: ImageButton = v.findViewById(R.id.btn_delete_stock)
    }
}

/** 搜索候选股票适配器：整行点击即加入当前分组，已加入项置灰禁点。 */
class StockSearchAdapter(
    private val data: List<Stock>,
    private val isAdded: (String) -> Boolean,
    private val onPick: (Stock) -> Unit
) : RecyclerView.Adapter<StockSearchAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stock_search, parent, false)
        val ctx = parent.context
        val addColor = ContextCompat.getColor(ctx, R.color.purple_500)
        val addedColor = ContextCompat.getColor(ctx, R.color.stock_added)
        return VH(v, addColor, addedColor)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val stock = data[position]
        val added = isAdded(stock.code)
        holder.name.text = stock.name
        holder.sub.text = holder.itemView.context.getString(
            R.string.search_sub_format, stock.code, stock.pinyinInitials
        )
        if (added) {
            holder.status.text = holder.itemView.context.getString(R.string.already_added)
            holder.status.setTextColor(holder.addedColor)
            holder.itemView.isClickable = false
            holder.itemView.isLongClickable = false
            holder.itemView.alpha = 0.5f
        } else {
            holder.status.text = "+"
            holder.status.setTextColor(holder.addColor)
            holder.itemView.isClickable = true
            holder.itemView.alpha = 1f
            holder.itemView.setOnClickListener { onPick(stock) }
        }
    }

    override fun getItemCount(): Int = data.size

    class VH(v: View, val addColor: Int, val addedColor: Int) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tv_search_name)
        val sub: TextView = v.findViewById(R.id.tv_search_sub)
        val status: TextView = v.findViewById(R.id.tv_add_status)
    }
}
