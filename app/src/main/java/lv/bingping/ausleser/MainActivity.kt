package lv.bingping.ausleser

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
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
import lv.bingping.ausleser.data.SelectMember
import lv.bingping.ausleser.ui.AddMemberBottomSheet
import lv.bingping.ausleser.ui.GroupManageBottomSheet
import lv.bingping.ausleser.ui.MemberListAdapter
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
 *  - 第三层：成员工具栏（[R.id.members_toolbar]）+ 当前分组的自选列表，
 *    列表数据来自 t_selber_select_member，右侧“+”可按代码/名称/拼音首字母搜索添加。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: DbHelper

    /** 网络操作（服务端登记 / 停用跟踪）用后台线程，模式同 KLineActivity。 */
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var btnSelberSelect: Button
    private lateinit var btnGroupManage: Button
    private lateinit var btnSyncGroup: ImageButton
    private lateinit var btnDownloadGroup: ImageButton
    private lateinit var subBar: View
    private lateinit var groupChipGroup: ChipGroup

    private lateinit var tvMembersTitle: TextView
    private lateinit var btnAddMember: ImageButton
    private lateinit var rvMembers: RecyclerView
    private lateinit var tvMembersEmpty: TextView
    private lateinit var memberAdapter: MemberListAdapter
    private lateinit var swipeCallback: SwipeRevealCallback
    private var syncAnimator: ObjectAnimator? = null

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
        btnSyncGroup = findViewById(R.id.btn_sync_group)
        btnDownloadGroup = findViewById(R.id.btn_download_group)
        subBar = findViewById(R.id.sub_bar)
        groupChipGroup = findViewById(R.id.group_chip_group)

        tvMembersTitle = findViewById(R.id.tv_members_title)
        btnAddMember = findViewById(R.id.btn_add_member)
        rvMembers = findViewById(R.id.rv_members)
        tvMembersEmpty = findViewById(R.id.tv_members_empty)

        memberAdapter = MemberListAdapter(
            onDelete = { member -> removeMember(member) },
            onClick = { member -> openKLine(member) }
        )
        rvMembers.layoutManager = LinearLayoutManager(this)
        rvMembers.adapter = memberAdapter

        swipeCallback = SwipeRevealCallback()
        ItemTouchHelper(swipeCallback).attachToRecyclerView(rvMembers)
        rvMembers.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                swipeCallback.closeOpen()
            }
        })
        rvMembers.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
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
            GroupManageBottomSheet(
                this,
                dbHelper,
                onChanged = { refreshGroups() },
                onDeleted = { group -> unregisterGroup(group.id) }
            ).show()
        }
        btnSyncGroup.setOnClickListener { syncCurrentGroup() }
        btnDownloadGroup.setOnClickListener { downloadCurrentGroup() }
        btnAddMember.setOnClickListener {
            if (selectedGroupId > 0) {
            AddMemberBottomSheet(this, dbHelper, selectedGroupId) { reloadMembers() }.show()
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
                    tvMembersTitle.text = it.text
                    reloadMembers()
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
            tvMembersTitle.text = ""
            reloadMembers()
        }
    }

    /** 加载当前选中分组的成员列表并切换空态。 */
    private fun reloadMembers() {
        if (::swipeCallback.isInitialized) swipeCallback.closeOpen()
        val members = if (selectedGroupId > 0) dbHelper.queryMembers(selectedGroupId) else emptyList()
        memberAdapter.submit(members)
        tvMembersEmpty.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 对账当前群组成员，并由服务端逐成员串行同步 K 线。 */
    private fun syncCurrentGroup() {
        if (selectedGroupId <= 0 || syncAnimator != null) return
        val members = dbHelper.queryMembers(selectedGroupId)
        val groupName = tvMembersTitle.text.toString()
        val groupId = selectedGroupId
        btnSyncGroup.isEnabled = false
        btnDownloadGroup.isEnabled = false
        syncAnimator = ObjectAnimator.ofFloat(btnSyncGroup, View.ROTATION, 0f, 360f).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        executor.execute {
            val result = try {
                val taskId = DatasourceApi.syncGroup(this, groupId, groupName, members)
                DatasourceApi.awaitGroupSync(this, taskId)
            } catch (e: Exception) {
                AppLog.netError("群组同步失败: groupId=$groupId", e)
                null
            }
            runOnUiThread {
                stopSyncAnimation()
                val message = when {
                    result == null -> getString(R.string.sync_group_failed)
                    result.status == "cancelled" -> getString(R.string.sync_group_cancelled)
                    result.failed > 0 -> getString(
                        R.string.sync_group_partial,
                        result.completed,
                        result.failed
                    )
                    else -> getString(R.string.sync_group_done, result.completed)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopSyncAnimation() {
        syncAnimator?.cancel()
        syncAnimator = null
        btnSyncGroup.rotation = 0f
        btnSyncGroup.isEnabled = true
        btnDownloadGroup.isEnabled = true
    }

    /** 从数据源逐一下载当前群组全部成员的 K 线并写入 APP 数据库。 */
    private fun downloadCurrentGroup() {
        if (selectedGroupId <= 0 || syncAnimator != null) return
        val members = dbHelper.queryMembers(selectedGroupId)
        if (members.isEmpty()) {
            Toast.makeText(this, R.string.download_group_empty, Toast.LENGTH_SHORT).show()
            return
        }

        btnSyncGroup.isEnabled = false
        btnDownloadGroup.isEnabled = false
        val dropDistance = 8f * resources.displayMetrics.density
        syncAnimator = ObjectAnimator.ofFloat(
            btnDownloadGroup,
            View.TRANSLATION_Y,
            0f,
            dropDistance,
            0f
        ).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        executor.execute {
            var succeeded = 0
            members.forEach { member ->
                try {
                    KLineSync.syncMember(this, dbHelper, member.code)
                    succeeded++
                } catch (e: Exception) {
                    AppLog.netError("群组成员下载失败: code=${member.code}", e)
                }
            }
            val failed = members.size - succeeded
            runOnUiThread {
                syncAnimator?.cancel()
                syncAnimator = null
                btnDownloadGroup.translationY = 0f
                btnSyncGroup.isEnabled = true
                btnDownloadGroup.isEnabled = true
                Toast.makeText(
                    this,
                    if (failed == 0) {
                        getString(R.string.download_group_done, succeeded)
                    } else {
                        getString(R.string.download_group_partial, succeeded, failed)
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 从当前分组移除一只成员，并通知服务端只删除对应群组关系。 */
    private fun removeMember(member: SelectMember) {
        dbHelper.deleteMember(member.id)
        executor.execute {
            try {
                DatasourceApi.unregisterMember(this, member.code, member.groupId)
            } catch (e: Exception) {
                AppLog.netError(
                    "服务端移除群组成员失败: groupId=${member.groupId} code=${member.code}",
                    e
                )
            }
        }
        reloadMembers()
    }

    private fun unregisterGroup(groupId: Long) {
        executor.execute {
            try {
                DatasourceApi.unregisterGroup(this, groupId)
            } catch (e: Exception) {
                AppLog.netError("服务端删除群组失败: groupId=$groupId", e)
            }
        }
    }

    /** 打开指定成员的 K 线图页面。 */
    private fun openKLine(member: SelectMember) {
        startActivity(
            KLineActivity.intent(
                this,
                member.code,
                member.name,
                member.groupId,
                tvMembersTitle.text.toString()
            )
        )
    }

    override fun onDestroy() {
        syncAnimator?.cancel()
        executor.shutdown()
        dbHelper.close()
        super.onDestroy()
    }
}
