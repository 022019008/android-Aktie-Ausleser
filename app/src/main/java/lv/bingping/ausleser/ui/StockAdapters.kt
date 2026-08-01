package lv.bingping.ausleser.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import lv.bingping.ausleser.R
import lv.bingping.ausleser.data.SelectMember
import lv.bingping.ausleser.data.Member

/** 当前分组已添加成员的列表适配器。左滑揭示“删除”操作按钮，整行点击进入 K 线图。 */
class MemberListAdapter(
    private val onDelete: (SelectMember) -> Unit,
    private val onClick: (SelectMember) -> Unit
) : RecyclerView.Adapter<MemberListAdapter.VH>() {

    private val data = mutableListOf<SelectMember>()

    fun submit(list: List<SelectMember>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_select_member, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val member = data[position]
        holder.code.text = member.code
        holder.name.text = member.name
        holder.delete.setOnClickListener { onDelete(member) }
        holder.itemView.setOnClickListener { onClick(member) }
        // 复用 / 重绑时复位滑动状态，避免残留展开
        (holder.itemView as SwipeRevealLayout).setOffset(0f)
    }

    override fun getItemCount(): Int = data.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val code: TextView = v.findViewById(R.id.tv_member_code)
        val name: TextView = v.findViewById(R.id.tv_member_name)
        val delete: ImageButton = v.findViewById(R.id.btn_delete_member)
    }
}

/** 搜索候选成员适配器：整行点击即加入当前分组，已加入项置灰禁点。 */
class MemberSearchAdapter(
    private val data: List<Member>,
    private val isAdded: (String) -> Boolean,
    private val onPick: (Member) -> Unit
) : RecyclerView.Adapter<MemberSearchAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_member_search, parent, false)
        val ctx = parent.context
        val addColor = ContextCompat.getColor(ctx, R.color.purple_500)
        val addedColor = ContextCompat.getColor(ctx, R.color.member_added)
        return VH(v, addColor, addedColor)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val member = data[position]
        val added = isAdded(member.code)
        holder.name.text = member.name
        holder.sub.text = holder.itemView.context.getString(
            R.string.search_sub_format, member.code, member.pinyinInitials
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
            holder.itemView.setOnClickListener { onPick(member) }
        }
    }

    override fun getItemCount(): Int = data.size

    class VH(v: View, val addColor: Int, val addedColor: Int) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tv_search_name)
        val sub: TextView = v.findViewById(R.id.tv_search_sub)
        val status: TextView = v.findViewById(R.id.tv_add_status)
    }
}
