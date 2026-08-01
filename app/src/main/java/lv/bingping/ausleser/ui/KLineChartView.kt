package lv.bingping.ausleser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.os.SystemClock
import lv.bingping.ausleser.R
import lv.bingping.ausleser.data.ChanBi
import lv.bingping.ausleser.data.ChanZhongshu
import lv.bingping.ausleser.data.KBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * K 线图控件：蜡烛图（红涨绿跌）+ 右侧价格轴 + 底部时间轴 + 网格。
 *
 * 交互：
 *  - 单指拖动：向前/向后翻看历史；
 *  - 双指合拢/展开：zoom-out / zoom-in，以手指焦点处的 K 线为锚点；
 *    缩到极密时绘制自动降级：蜡烛 → 条形（仅高低竖线）→ 收盘折线，
 *    1080p 横屏一屏可看约 2900 根；
 *  - 长按：显示十字光标——纵线吸附手指最近的 K 线（底部时间轴区显示时间标签），
 *    横线跟随手指（右侧价格轴区显示价格标签），拖动可移动；抬手后光标保留，
 *    再次点按（无拖动）或第二指落下（切回缩放）才消失；
 *  - 可见条数与右边界均带边界钳制：最新一根始终可回到右缘，最早一根不会被翻过去。
 *
 * 数据由 [setData] 一次性提供（升序），翻页/缩放只在内存窗口上移动，不再回调外部。
 */
class KLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 全部已加载 K 线（旧 -> 新）。 */
    private var bars: List<KBar> = emptyList()

    /** 缠论叠加层（调用方以 Chan.analyze 计算后经 [setChanOverlay] 提供；[setData] 时清空）。 */
    private var chanBi: List<ChanBi> = emptyList()
    private var chanZs: List<ChanZhongshu> = emptyList()
    private var chanSubZs: List<ChanZhongshu> = emptyList()

    /** 当前可见条数（浮点，支持平滑缩放）。 */
    var visibleCount: Float = DEFAULT_PORTRAIT_COUNT
        private set

    /** 最右侧可见 K 线的浮点下标（bars.size-1 表示最新一根贴右缘）。 */
    private var rightIndex: Float = 0f

    /** 分钟级周期为 true：时间轴主格式 HH:mm，跨日显示 MM-dd。 */
    var intraday: Boolean = false

    // ---------------- 十字光标（长按）状态 ----------------

    /** 十字光标是否激活：长按显示，抬手保留，再次点按（无拖动）或第二指落下隐藏。 */
    private var crossActive = false

    /** 纵线吸附的 K 线下标（取最近一根）。 */
    private var crossIndex = 0

    /** 横线跟随的手指 y（原始像素，绘制时按绘图区钳制）。 */
    private var crossY = 0f

    /** 光标激活期间的点按判定：按下起点 + 是否移动过（超 [touchSlop] 即算移动）。
     *  唤出光标的长按那一笔也视作"移动过"（见 [enterCrosshair]），防止抬手即被收起。 */
    private var crossDownX = 0f
    private var crossDownY = 0f
    private var crossMoved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** 当前是否有手指在屏幕上：用于拒绝抬手后才送达的陈旧长按回调。 */
    private var touchInProgress = false

    // ---------------- 绘图相关 ----------------

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    /** 右侧价格轴宽度 / 底部时间轴高度。 */
    private val axisRightW = dp(62f)
    private val axisBottomH = dp(20f)

    /**
     * 单根 K 线槽位的像素上下限，决定缩放倍率边界（上限可见根数 ≈ 绘图区宽 ÷ [minSlotPx]）。
     * 缩到很密时绘制按槽宽分档降级（与主流行情 app 一致）：
     * ≥[barModeSlotPx] 完整蜡烛；≥[lineModeSlotPx] 条形（仅涨跌色高低竖线，1px）；
     * 更密则折线（收盘 polyline）。
     */
    private val minSlotPx = dp(0.25f)
    private val maxSlotPx = dp(48f)
    private val barModeSlotPx = dp(1.5f)
    private val lineModeSlotPx = dp(0.8f)

    private val gridPaint = Paint().apply {
        color = 0x28888888
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        color = 0xFF9E9E9E.toInt()
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.kline_up, null)
        strokeWidth = dp(1f)
    }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.kline_down, null)
        strokeWidth = dp(1f)
    }

    /** 极密折线模式线条（关闭抗锯齿：1px 直线段更锐利、更快）。 */
    private val linePaint = Paint().apply {
        color = resources.getColor(R.color.kline_line, null)
        strokeWidth = 1f
    }

    /** 缠论：笔（黄色折线）。 */
    private val biPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.chan_bi, null)
        strokeWidth = dp(1.5f)
    }

    /** 缠论：本级别中枢（蓝）与次级别中枢（橙）——半透明填充 + 1px 描边各一支笔。 */
    private val zsFillPaint = Paint().apply { color = resources.getColor(R.color.chan_zs_fill, null) }
    private val zsStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.chan_zs_stroke, null)
        strokeWidth = 1f
    }
    private val zsSubFillPaint = Paint().apply { color = resources.getColor(R.color.chan_zs_sub_fill, null) }
    private val zsSubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.chan_zs_sub_stroke, null)
        strokeWidth = 1f
    }

    /** 十字光标：虚线 + 交点圆点（中灰，明暗主题均可读）。 */
    private val crossPaint = Paint().apply {
        color = resources.getColor(R.color.kline_cross_line, null)
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val crossDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.kline_cross_line, null)
    }

    /** 十字光标坐标标签：深色半透明圆角底 + 白字（明暗主题均可读）。 */
    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.kline_cross_chip, null)
    }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    /** 标签 chip 尺寸（底边时间 chip 需落得进 [axisBottomH]）。 */
    private val chipH = dp(16f)
    private val chipCorner = dp(3f)
    private val chipPadX = dp(6f)
    private val chipRect = RectF()

    private val fmtDay = newTimeFormat("MM-dd")
    private val fmtTime = newTimeFormat("HH:mm")

    /** 十字光标时间标签用完整格式：分钟级 MM-dd HH:mm，日/周级 yyyy-MM-dd。 */
    private val fmtCrossIntraday = newTimeFormat("MM-dd HH:mm")
    private val fmtCrossDay = newTimeFormat("yyyy-MM-dd")

    private val tmpDate = Date()

    // ---------------- 手势 ----------------

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (bars.isEmpty()) return false
                val slotW = slotWidth()
                val startF = rightIndex - visibleCount + 1
                // 焦点压在浮点下标 focusF 这根 K 线上，缩放后保持不动
                val focusF = startF + (detector.focusX - paddingLeft - slotW / 2) / slotW
                visibleCount = clampCount(visibleCount / detector.scaleFactor)
                val newSlot = slotWidth()
                val newStart = focusF - (detector.focusX - paddingLeft - newSlot / 2) / newSlot
                rightIndex = newStart + visibleCount - 1
                clampRight()
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // 十字光标激活期间平移让位于光标移动
                if (bars.isEmpty() || scaleDetector.isInProgress || crossActive) return false
                rightIndex += distanceX / slotWidth()
                clampRight()
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (bars.isEmpty() || scaleDetector.isInProgress) return
                enterCrosshair(e.x, e.y)
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bars.isEmpty()) return false
        var crossHandled = false
        when (event.actionMasked) {
            // 第二指落下：结束十字光标，交还缩放手势
            MotionEvent.ACTION_POINTER_DOWN -> hideCrosshair()
            MotionEvent.ACTION_DOWN -> {
                touchInProgress = true
                if (crossActive) {
                    // 光标留存期间的新一笔：记起点，供抬手时判定"点按即消失"
                    crossDownX = event.x
                    crossDownY = event.y
                    crossMoved = false
                    crossHandled = true
                }
            }
            MotionEvent.ACTION_MOVE ->
                if (crossActive) {
                    if (!crossMoved &&
                        (abs(event.x - crossDownX) > touchSlop ||
                         abs(event.y - crossDownY) > touchSlop)) {
                        crossMoved = true
                    }
                    updateCrosshair(event.x, event.y)
                    crossHandled = true
                }
            MotionEvent.ACTION_UP -> {
                touchInProgress = false
                if (crossActive) {
                    // 抬手不消失：仅无移动的"点按"才收起光标；移动过则保持显示
                    if (!crossMoved) hideCrosshair()
                    crossHandled = true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                touchInProgress = false
                if (crossActive) crossHandled = true  // 系统打断也保持显示
            }
        }
        // 光标期间的 MOVE/UP 也继续喂给手势探测器：否则 GestureDetector 只见过 DOWN、
        // 它的长按定时器会在抬手后迟到触发，把刚收起的光标又错误激活（"点按消不掉"）。
        // 光标期间 onScroll/onLongPress 各有 crossActive 守卫，转发后均为空操作。
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return crossHandled || handled || super.onTouchEvent(event)
    }

    // ---------------- 十字光标 ----------------

    /** 长按进入十字光标模式：期间禁止父容器拦截触摸（保证竖向拖动只移动光标）。
     * 已激活时忽略（避免光标留存期间再次长按产生重复震动/跳位）；
     * 手指已离屏时忽略（拒绝迟到触发，见 [onTouchEvent] 的转发说明）。 */
    private fun enterCrosshair(x: Float, y: Float) {
        if (crossActive || !touchInProgress) return
        crossActive = true
        // 长按唤出光标的这一笔不是"点按"：置位 crossMoved，避免抬手时被 ACTION_UP 立刻收起
        crossMoved = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        updateCrosshair(x, y)
    }

    private fun hideCrosshair() {
        if (!crossActive) return
        crossActive = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    /** 移动十字光标：纵线吸附手指最近的 K 线，横线跟随手指 y（均钳制在绘图区内）。 */
    private fun updateCrosshair(x: Float, y: Float) {
        val slotW = slotWidth()
        val startF = rightIndex - visibleCount + 1
        val idx = (startF + (x - paddingLeft - slotW / 2) / slotW).roundToInt()
        val firstVis = max(0, floor(startF).toInt())
        val lastVis = min(bars.size - 1, ceil(rightIndex).toInt())
        crossIndex = idx.coerceIn(firstVis, lastVis)
        crossY = y.coerceIn(paddingTop.toFloat(), paddingTop + chartH())
        invalidate()
    }

    // ---------------- 对外接口 ----------------

    /**
     * 设置数据并复位视窗。
     *
     * @param initialCount 初始可见条数（竖屏 200 / 横屏 500，由调用方按屏幕方向给；不足时按实际条数钳制）
     * @param anchorTs 不为 null 时把该时间戳所在 K 线锚定到右缘（用于屏幕旋转后恢复位置）
     */
    fun setData(newBars: List<KBar>, initialCount: Float, anchorTs: Long? = null) {
        hideCrosshair()
        bars = newBars
        chanBi = emptyList()
        chanZs = emptyList()
        chanSubZs = emptyList()
        visibleCount = clampCount(initialCount)
        val anchorIdx = if (anchorTs != null) bars.indexOfFirst { it.timestamp >= anchorTs }.takeIf { it >= 0 } else null
        rightIndex = (anchorIdx ?: bars.size - 1).toFloat()
        clampRight()
        invalidate()
    }

    /**
     * 设置缠论叠加层：本级别笔、本级别中枢、次级别中枢（均由 `Chan.analyze` 计算）。
     * 传 null 清除对应部分；换数据时 [setData] 会一并清空。
     */
    fun setChanOverlay(bi: List<ChanBi>?, zs: List<ChanZhongshu>?, subZs: List<ChanZhongshu>?) {
        chanBi = bi.orEmpty()
        chanZs = zs.orEmpty()
        chanSubZs = subZs.orEmpty()
        invalidate()
    }

    /** 当前右缘 K 线时间戳（旋转恢复用）；无数据返回 null。 */
    fun rightEdgeTimestamp(): Long? = bars.getOrNull(rightIndex.roundToInt())?.timestamp

    // ---------------- 布局与钳制 ----------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        visibleCount = clampCount(visibleCount)
        clampRight()
    }

    private fun chartW(): Float = max(0f, width - paddingLeft - paddingRight - axisRightW)
    private fun chartH(): Float = max(0f, height - paddingTop - paddingBottom - axisBottomH)
    private fun slotWidth(): Float = chartW() / visibleCount

    private fun clampCount(count: Float): Float {
        if (bars.isEmpty()) return count.coerceAtLeast(2f)
        val w = chartW()
        val byWidthLo = if (w > 0f) w / maxSlotPx else 2f
        val byWidthHi = if (w > 0f) w / minSlotPx else count
        val lo = min(max(2f, byWidthLo), bars.size.toFloat())
        val hi = max(lo, min(byWidthHi, bars.size.toFloat()))
        return count.coerceIn(lo, hi)
    }

    private fun clampRight() {
        if (bars.isEmpty()) {
            rightIndex = 0f
            return
        }
        val hi = bars.size - 1f
        val lo = min(visibleCount - 1f, hi)
        rightIndex = rightIndex.coerceIn(lo, hi)
    }

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty() || chartW() <= 0f || chartH() <= 0f) return

        val slotW = slotWidth()
        val startF = rightIndex - visibleCount + 1
        val first = max(0, floor(startF).toInt())
        val last = min(bars.size - 1, ceil(rightIndex).toInt())
        if (first > last) return

        // 可见区间价格范围（上下各留 6% 边距）
        var minLow = Float.MAX_VALUE.toDouble()
        var maxHigh = -Float.MAX_VALUE.toDouble()
        for (i in first..last) {
            if (bars[i].low < minLow) minLow = bars[i].low
            if (bars[i].high > maxHigh) maxHigh = bars[i].high
        }
        var range = maxHigh - minLow
        if (range <= 0.0) {
            maxHigh += 1.0
            minLow -= 1.0
            range = 2.0
        }
        minLow -= range * 0.06
        maxHigh += range * 0.06
        range = maxHigh - minLow

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val cW = chartW()
        val cH = chartH()

        fun xOf(i: Int): Float = left + (i - startF + 0.5f) * slotW
        fun yOf(p: Double): Float = top + ((maxHigh - p) / range * cH).toFloat()

        // 横向网格 + 右侧价格标签（含上下边共 5 档）
        labelPaint.textAlign = Paint.Align.LEFT
        for (g in 0..4) {
            val y = top + cH * g / 4
            canvas.drawLine(left, y, left + cW, y, gridPaint)
            val price = maxHigh - range * g / 4
            canvas.drawText(formatPrice(price), left + cW + dp(4f), y + dp(3.5f), labelPaint)
        }

        // 纵向网格 + 底部时间标签（按槽宽自适应间隔，绝对下标取整保证平移时稳定）
        val labelEvery = max(1, (dp(72f) / slotW).roundToInt())
        labelPaint.textAlign = Paint.Align.CENTER
        var prevLabelDay = -1
        for (i in first..last) {
            if (i % labelEvery != 0) continue
            val x = xOf(i)
            if (x < left || x > left + cW) continue
            canvas.drawLine(x, top, x, top + cH, gridPaint)
            canvas.drawText(formatTimeLabel(bars[i], i, first, prevLabelDay), x, top + cH + dp(14f), labelPaint)
            prevLabelDay = dayNumber(bars[i].timestamp)
        }

        // 蜡烛：红涨绿跌；槽位过密时分档降级——蜡烛 → 条形（仅高低竖线）→ 收盘折线
        when {
            slotW >= barModeSlotPx -> {
                upPaint.strokeWidth = dp(1f)
                downPaint.strokeWidth = dp(1f)
                val bodyW = max(1f, slotW * 0.72f)
                for (i in first..last) {
                    val bar = bars[i]
                    val x = xOf(i)
                    val paint = if (bar.close >= bar.open) upPaint else downPaint
                    canvas.drawLine(x, yOf(bar.high), x, yOf(bar.low), paint)
                    val yOpen = yOf(bar.open)
                    val yClose = yOf(bar.close)
                    val bodyTop = min(yOpen, yClose)
                    val bodyH = max(abs(yOpen - yClose), dp(1f))
                    canvas.drawRect(x - bodyW / 2, bodyTop, x + bodyW / 2, bodyTop + bodyH, paint)
                }
            }
            slotW >= lineModeSlotPx -> {
                // 条形模式：只画 1px 涨跌色高-低竖线，不画实体，避免相邻糊成色带
                upPaint.strokeWidth = 1f
                downPaint.strokeWidth = 1f
                for (i in first..last) {
                    val bar = bars[i]
                    val x = xOf(i)
                    val paint = if (bar.close >= bar.open) upPaint else downPaint
                    canvas.drawLine(x, yOf(bar.high), x, yOf(bar.low), paint)
                }
            }
            else -> {
                // 折线模式：收盘价 polyline，可铺满数千根
                for (i in first until last) {
                    canvas.drawLine(xOf(i), yOf(bars[i].close), xOf(i + 1), yOf(bars[i + 1].close), linePaint)
                }
            }
        }

        // 缠论叠加：次级别中枢 → 本级别中枢 → 笔（笔在最上），裁剪在绘图区内
        if (chanBi.isNotEmpty() || chanZs.isNotEmpty() || chanSubZs.isNotEmpty()) {
            drawChanOverlay(canvas, first, last, startF, slotW, left, top, cW, cH, maxHigh, range)
        }

        // 十字光标（最上层）：仅当吸附的 K 线仍在可见区间内绘制
        if (crossActive && crossIndex in first..last) {
            drawCrosshair(canvas, crossIndex, crossY, startF, slotW, left, top, cW, cH, maxHigh, range)
        }
    }

    /**
     * 绘制缠论叠加层。时间戳经二分定位到浮点下标再取 x（次级别中枢的时间戳
     * 不在本级 bars 序列里，只能按时间戳插入点定位）；价格直接走与蜡烛相同的 y 映射。
     */
    private fun drawChanOverlay(
        canvas: Canvas, first: Int, last: Int, startF: Float, slotW: Float,
        left: Float, top: Float, cW: Float, cH: Float, maxHigh: Double, range: Double,
    ) {
        fun xOfTs(ts: Long): Float {
            var lo = 0
            var hi = bars.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (bars[mid].timestamp < ts) lo = mid + 1 else hi = mid
            }
            return left + (lo.coerceAtMost(bars.size - 1) - startF + 0.5f) * slotW
        }
        fun yOf(p: Double): Float = top + ((maxHigh - p) / range * cH).toFloat()
        fun drawZs(zs: ChanZhongshu, fill: Paint, stroke: Paint) {
            val x1 = xOfTs(zs.startTs)
            val x2 = maxOf(xOfTs(zs.endTs), x1 + 1f)
            canvas.drawRect(x1, yOf(zs.zg), x2, yOf(zs.zd), fill)
            canvas.drawRect(x1, yOf(zs.zg), x2, yOf(zs.zd), stroke)
        }

        canvas.save()
        canvas.clipRect(left, top, left + cW, top + cH)
        val loTs = bars[first].timestamp
        val hiTs = bars[last].timestamp
        for (zs in chanSubZs) {
            if (zs.endTs >= loTs && zs.startTs <= hiTs) drawZs(zs, zsSubFillPaint, zsSubStrokePaint)
        }
        for (zs in chanZs) {
            if (zs.endTs >= loTs && zs.startTs <= hiTs) drawZs(zs, zsFillPaint, zsStrokePaint)
        }
        for (bi in chanBi) {
            if (bi.endTs >= loTs && bi.startTs <= hiTs) {
                canvas.drawLine(
                    xOfTs(bi.startTs), yOf(bi.start.price),
                    xOfTs(bi.endTs), yOf(bi.end.price), biPaint
                )
            }
        }
        canvas.restore()
    }

    /**
     * 绘制十字光标：纵线吸附手指所按 K 线（交点画圆点），横线跟随手指 y。
     * 时间标签居中于纵线、落在底部时间轴区内（贴近左右缘时收进绘图区防出屏）；
     * 价格标签固定在右侧价格轴区、随手指 y 上下移动（贴绘图区上下缘时收进）。
     */
    private fun drawCrosshair(
        canvas: Canvas, index: Int, y: Float, startF: Float, slotW: Float,
        left: Float, top: Float, cW: Float, cH: Float, maxHigh: Double, range: Double,
    ) {
        val x = left + (index - startF + 0.5f) * slotW
        val cy = y.coerceIn(top, top + cH)

        canvas.drawLine(x, top, x, top + cH, crossPaint)
        canvas.drawLine(left, cy, left + cW, cy, crossPaint)
        canvas.drawCircle(x, cy, dp(2.5f), crossDotPaint)

        // 底部时间标签：对应纵线吸附的那根 K 线
        val timeText = formatCrossTime(bars[index])
        val timeW = chipTextPaint.measureText(timeText) + chipPadX * 2
        val timeCx = x.coerceIn(left + timeW / 2, left + cW - timeW / 2)
        drawChip(canvas, timeText, timeCx, top + cH + axisBottomH / 2, timeW)

        // 右侧价格标签：由手指 y 反推当前价位
        val price = maxHigh - (cy - top) / cH * range
        val priceText = formatPrice(price)
        val priceW = min(axisRightW - dp(4f), chipTextPaint.measureText(priceText) + chipPadX * 2)
        val priceCy = cy.coerceIn(top + chipH / 2, top + cH - chipH / 2)
        drawChip(canvas, priceText, left + cW + axisRightW / 2, priceCy, priceW)
    }

    /** 坐标标签 chip：深色半透明圆角底 + 居中白字。 */
    private fun drawChip(canvas: Canvas, text: String, cx: Float, cy: Float, w: Float) {
        chipRect.set(cx - w / 2, cy - chipH / 2, cx + w / 2, cy + chipH / 2)
        canvas.drawRoundRect(chipRect, chipCorner, chipCorner, chipBgPaint)
        val fm = chipTextPaint.fontMetrics
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, chipTextPaint)
    }

    // ---------------- 格式化 ----------------

    private fun newTimeFormat(pattern: String) =
        SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }

    private fun formatPrice(p: Double): String =
        when {
            p < 10.0 -> "%.3f".format(p)
            p < 10000.0 -> "%.2f".format(p)
            else -> "%.0f".format(p)
        }

    private fun dayNumber(ts: Long): Int = ((ts + 8 * 3600L) / 86400L).toInt()

    /** 分钟级显示 HH:mm，跨日处改显 MM-dd；日/周级统一 MM-dd。 */
    private fun formatTimeLabel(bar: KBar, index: Int, firstVisible: Int, prevLabelDay: Int): String {
        tmpDate.time = bar.timestamp * 1000
        val day = dayNumber(bar.timestamp)
        if (!intraday || index == firstVisible || day != prevLabelDay) {
            // 周线第一根或跨年时补年份
            return fmtDay.format(tmpDate)
        }
        return fmtTime.format(tmpDate)
    }

    /** 十字光标时间标签：分钟级 MM-dd HH:mm，日/周级 yyyy-MM-dd。 */
    private fun formatCrossTime(bar: KBar): String {
        tmpDate.time = bar.timestamp * 1000
        return (if (intraday) fmtCrossIntraday else fmtCrossDay).format(tmpDate)
    }

    companion object {
        const val DEFAULT_PORTRAIT_COUNT = 200f
        const val DEFAULT_LANDSCAPE_COUNT = 500f
    }
}
