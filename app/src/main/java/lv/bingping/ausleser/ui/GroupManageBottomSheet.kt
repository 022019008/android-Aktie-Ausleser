package lv.bingping.ausleser.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import lv.bingping.ausleser.R
import lv.bingping.ausleser.data.DbHelper
import lv.bingping.ausleser.data.SelectGroup

/**
 * 自选分组管理底部弹层：对自选分组进行 增 / 改 / 删。
 *
 * 任何改动成功后会通过 [onChanged] 通知调用方刷新外部 UI（子栏的分组 Chip）。
 */
class GroupManageBottomSheet(
    private val context: Context,
    private val dbHelper: DbHelper,
    private val onChanged: () -> Unit,
    private val onDeleted: (SelectGroup) -> Unit = {}
) {
    private val dialog = BottomSheetDialog(context)
    private val items = mutableListOf<SelectGroup>()
    private val adapter = GroupAdapter(items, ::requestEdit, ::requestDelete)

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_group_manage, null)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_groups)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.isNestedScrollingEnabled = false
        recyclerView.adapter = adapter

        view.findViewById<Button>(R.id.btn_add_group).setOnClickListener {
            promptName(context.getString(R.string.add_group), "") { name ->
                if (name.isNotBlank()) {
                    dbHelper.insertSelectGroup(name.trim())
                    reloadAndNotify()
                } else {
                    toastEmpty()
                }
            }
        }

        dialog.setContentView(view)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        reload()
        dialog.show()
    }

    private fun reload() {
        items.clear()
        items.addAll(dbHelper.querySelectGroups())
        adapter.notifyDataSetChanged()
    }

    private fun reloadAndNotify() {
        reload()
        onChanged()
    }

    private fun requestEdit(group: SelectGroup) {
        promptName(context.getString(R.string.edit_group), group.name) { name ->
            if (name.isNotBlank()) {
                dbHelper.updateSelectGroupName(group.id, name.trim())
                reloadAndNotify()
            } else {
                toastEmpty()
            }
        }
    }

    private fun requestDelete(group: SelectGroup) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.confirm_delete, group.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                dbHelper.deleteSelectGroup(group.id)
                onDeleted(group)
                reloadAndNotify()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptName(title: String, initial: String, onOk: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val input = EditText(context).apply {
            setText(initial)
            setSelection(text.length)
            setHint(R.string.group_name_hint)
        }
        val container = FrameLayout(context).apply {
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(pad, pad, pad, pad / 2) }
            )
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.action_ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toastEmpty() {
        Toast.makeText(context, R.string.group_name_empty, Toast.LENGTH_SHORT).show()
    }

    /** 分组列表适配器：每行 = 分组名 + 编辑 + 删除。 */
    private class GroupAdapter(
        private val data: List<SelectGroup>,
        private val onEdit: (SelectGroup) -> Unit,
        private val onDelete: (SelectGroup) -> Unit
    ) : RecyclerView.Adapter<GroupAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_group_manage, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val group = data[position]
            holder.name.text = group.name
            holder.edit.setOnClickListener { onEdit(group) }
            holder.delete.setOnClickListener { onDelete(group) }
        }

        override fun getItemCount(): Int = data.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tv_group_name)
            val edit: ImageButton = v.findViewById(R.id.btn_edit)
            val delete: ImageButton = v.findViewById(R.id.btn_delete)
        }
    }
}
