package lv.bingping.ausleser.ui

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * 配合 [SwipeRevealLayout] 的 ItemTouchHelper 回调：仅做“左滑揭示/回弹/单开”，
 * 不触发删除（删除由行内按钮点击负责）。
 */
class SwipeRevealCallback : ItemTouchHelper.Callback() {

    /** 当前处于展开状态的行（同一时间最多一个）。 */
    var openVH: RecyclerView.ViewHolder? = null
        private set

    private var dragStartOffset = 0f

    override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
        makeMovementFlags(0, ItemTouchHelper.LEFT)

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        // 揭示模式，永不走“滑动 dismissal”
    }

    /** 阈值设大，避免 ItemTouchHelper 触发 onSwiped。 */
    override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 2f

    override fun onChildDraw(
        c: Canvas,
        rv: RecyclerView,
        vh: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val layout = vh.itemView as? SwipeRevealLayout ?: return
        if (isCurrentlyActive) {
            val base = if (openVH === vh) dragStartOffset else 0f
            layout.setOffset(base + dX)
        } else {
            // 回弹/恢复阶段：已展开的行必须保持展开，不能被默认动画合上
            if (openVH === vh) {
                layout.setOffset(-layout.revealWidth().toFloat())
            } else {
                layout.setOffset(dX)
            }
        }
    }

    override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(vh, actionState)
        if (vh != null && actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            dragStartOffset = (vh.itemView as SwipeRevealLayout).currentOffset()
            openVH?.takeIf { it !== vh }?.let { animateClose(it) }
        }
    }

    override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
        val layout = vh.itemView as? SwipeRevealLayout ?: return
        val w = layout.revealWidth().toFloat()
        val cur = layout.currentOffset()
        openVH?.takeIf { it !== vh }?.let { animateClose(it) }
        if (cur < -w / 2f) {
            // 先置 openVH，使 super 触发的回弹动画在 onChildDraw 里被“保持展开”分支拦截
            openVH = vh
            layout.setOffset(-w)
        } else if (openVH === vh) {
            openVH = null
        }
        // 必须调用 super：清除 ItemTouchHelper 内部“选中态”，否则该行触摸会被其拦截，按钮点不到
        super.clearView(rv, vh)
    }

    /** 关闭当前展开的行（滚动 / 点击外部 / 数据刷新时调用）。 */
    fun closeOpen() {
        openVH?.let { animateClose(it) }
    }

    private fun animateClose(vh: RecyclerView.ViewHolder) {
        val layout = vh.itemView as? SwipeRevealLayout ?: return
        animateTo(layout, layout.currentOffset(), 0f) { if (openVH === vh) openVH = null }
    }

    private fun animateTo(layout: SwipeRevealLayout, from: Float, to: Float, onEnd: () -> Unit) {
        if (from == to) {
            onEnd()
            return
        }
        ValueAnimator.ofFloat(from, to).apply {
            duration = 180
            addUpdateListener { layout.setOffset(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }
}
