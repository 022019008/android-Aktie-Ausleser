package lv.bingping.ausleser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * 左滑揭示右侧操作按钮的容器。
 *
 * 子视图约定：第 0 个 = 内容（铺满宽度），第 1 个 = 操作区。
 * 操作区在 onLayout 中被排到右边缘之外，并因 clipChildren 被裁剪——即“藏在屏幕外”；
 * [setOffset] 同时平移内容/操作区，dx∈[-操作区宽, 0]，左滑时操作区从右边缘滑入。
 */
class SwipeRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    init {
        clipChildren = true
        clipToPadding = true
    }

    private val content: View? get() = if (childCount > 0) getChildAt(0) else null
    private val actions: View? get() = if (childCount > 1) getChildAt(1) else null

    fun revealWidth(): Int = actions?.measuredWidth ?: 0

    fun setOffset(dx: Float) {
        val v = dx.coerceIn(-revealWidth().toFloat(), 0f)
        content?.translationX = v
        actions?.translationX = v
    }

    fun currentOffset(): Float = content?.translationX ?: 0f

    fun isClosed(): Boolean = currentOffset() == 0f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val c = content
        if (c == null) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        c.measure(widthSpec, heightSpec)
        val h = c.measuredHeight
        actions?.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(c.measuredWidth, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        content?.layout(0, 0, w, h)
        val a = actions
        if (a != null) a.layout(w, 0, w + a.measuredWidth, h)
    }
}
