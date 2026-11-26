package com.padhai.app.ui.learn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a left-to-right mind map: centre topic -> branches -> points.
 * - Drag to move, pinch to zoom, double-tap to fit the whole map.
 * - Tap a branch to collapse or expand its points.
 */
class MindMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    class Branch(val title: String, val children: List<String>)

    private class Node(
        val text: String,
        val level: Int,
        val branchIndex: Int,
        val color: Int
    ) {
        var layout: StaticLayout? = null
        var parent: Node? = null
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f
    }

    private class Style(
        val paint: TextPaint,
        val maxTextWidth: Int,
        val maxLines: Int,
        val align: Layout.Alignment
    )

    private var rootTitle = "Mind Map"
    private var branches: List<Branch> = emptyList()
    private var collapsed = BooleanArray(0)
    private val nodes = ArrayList<Node>()

    private var contentW = 0f
    private var contentH = 0f

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var needsFit = false

    private val palette = intArrayOf(
        Color.parseColor("#6366F1"),
        Color.parseColor("#10B981"),
        Color.parseColor("#F59E0B"),
        Color.parseColor("#EF4444"),
        Color.parseColor("#06B6D4"),
        Color.parseColor("#EC4899"),
        Color.parseColor("#8B5CF6"),
        Color.parseColor("#84CC16")
    )

    private val rootTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = sp(16f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val branchTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val leafTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5E7EB")
        textSize = sp(12f)
    }
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = sp(13f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val rootStyle = Style(rootTextPaint, dp(150f).toInt(), 4, Layout.Alignment.ALIGN_CENTER)
    private val branchStyle = Style(branchTextPaint, dp(150f).toInt(), 3, Layout.Alignment.ALIGN_CENTER)
    private val leafStyle = Style(leafTextPaint, dp(190f).toInt(), 3, Layout.Alignment.ALIGN_NORMAL)

    private val rect = RectF()
    private val path = Path()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val factor = newScale / scale
                offsetX = detector.focusX - (detector.focusX - offsetX) * factor
                offsetY = detector.focusY - (detector.focusY - offsetY) * factor
                scale = newScale
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
                offsetX -= distanceX
                offsetY -= distanceY
                invalidate()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                fitToScreen()
                return true
            }
        }
    )

    /** Show a new mind map. */
    fun setData(title: String, newBranches: List<Branch>) {
        rootTitle = title.ifBlank { "Mind Map" }
        branches = newBranches.take(8).map { Branch(it.title, it.children.take(6)) }
        collapsed = BooleanArray(branches.size)
        relayout()
        needsFit = true
        if (width > 0 && height > 0) fitToScreen()
        invalidate()
    }

    /** Zoom and move so the whole map is visible. */
    fun fitToScreen() {
        if (width == 0 || height == 0 || contentW <= 0f || contentH <= 0f) {
            needsFit = true
            return
        }
        val margin = dp(16f)
        val s = min((width - 2 * margin) / contentW, (height - 2 * margin) / contentH)
        scale = s.coerceIn(MIN_SCALE, 1.2f)
        offsetX = (width - contentW * scale) / 2f
        offsetY = (height - contentH * scale) / 2f
        needsFit = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (needsFit && w > 0 && h > 0) fitToScreen()
    }

    // ---------------------------------------------------------------- layout

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun styleFor(level: Int): Style = when (level) {
        0 -> rootStyle
        1 -> branchStyle
        else -> leafStyle
    }

    private fun buildLayout(text: String, style: Style, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, style.paint, max(1, width))
            .setAlignment(style.align)
            .setMaxLines(style.maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()

    private fun naturalWidth(layout: StaticLayout): Int {
        var w = 0f
        for (i in 0 until layout.lineCount) {
            w = max(w, layout.getLineWidth(i))
        }
        return ceil(w).toInt()
    }

    /** Gives every node of one level the same width, so each column looks tidy. */
    private fun sizeNodes(group: List<Node>, padX: Float, padY: Float) {
        if (group.isEmpty()) return
        val style = styleFor(group[0].level)
        var textW = 0
        for (n in group) {
            textW = max(textW, naturalWidth(buildLayout(n.text, style, style.maxTextWidth)))
        }
        for (n in group) {
            val layout = buildLayout(n.text, style, textW)
            n.layout = layout
            n.w = textW + 2 * padX
            n.h = layout.height + 2 * padY
        }
    }

    private fun relayout() {
        nodes.clear()
        contentW = 0f
        contentH = 0f

        val padX = dp(12f)
        val padY = dp(8f)
        val colGap = dp(44f)
        val leafGap = dp(8f)
        val groupGap = dp(20f)

        val root = Node(rootTitle, 0, -1, Color.parseColor("#F8FAFC"))
        val branchNodes = branches.mapIndexed { i, b ->
            Node(b.title, 1, i, palette[i % palette.size]).also { it.parent = root }
        }
        val leafNodes = branches.mapIndexed { i, b ->
            b.children.map { text ->
                Node(text, 2, i, branchNodes[i].color).also { it.parent = branchNodes[i] }
            }
        }

        sizeNodes(listOf(root), padX, padY)
        sizeNodes(branchNodes, padX, padY)
        sizeNodes(leafNodes.flatten(), padX, padY)

        val branchX = root.w + colGap
        val branchW = if (branchNodes.isEmpty()) 0f else branchNodes[0].w
        val leafX = branchX + branchW + colGap

        var cursorY = 0f
        var anyLeaves = false
        for (i in branchNodes.indices) {
            val b = branchNodes[i]
            val visible = if (collapsed[i]) emptyList() else leafNodes[i]
            val leavesTotal = if (visible.isEmpty()) {
                0f
            } else {
                visible.sumOf { it.h.toDouble() }.toFloat() + leafGap * (visible.size - 1)
            }
            val groupH = max(b.h, leavesTotal)

            b.x = branchX
            b.y = cursorY + (groupH - b.h) / 2f

            var leafY = cursorY + (groupH - leavesTotal) / 2f
            for (leaf in visible) {
                leaf.x = leafX
                leaf.y = leafY
                leafY += leaf.h + leafGap
                nodes.add(leaf)
                anyLeaves = true
            }
            nodes.add(b)
            cursorY += groupH + groupGap
        }

        val totalH = if (branchNodes.isEmpty()) root.h else cursorY - groupGap
        root.x = 0f
        root.y = (totalH - root.h) / 2f
        nodes.add(0, root)

        contentH = totalH
        contentW = when {
            branchNodes.isEmpty() -> root.w
            anyLeaves -> leafX + leafNodes.flatten().maxOf { it.w }
            else -> branchX + branchW
        }
    }

    // ------------------------------------------------------------------ input

    private fun handleTap(x: Float, y: Float) {
        val wx = (x - offsetX) / scale
        val wy = (y - offsetY) / scale
        for (n in nodes) {
            if (n.level != 1) continue
            if (branches[n.branchIndex].children.isEmpty()) continue
            if (wx >= n.x && wx <= n.x + n.w && wy >= n.y && wy <= n.y + n.h) {
                val idx = n.branchIndex
                val screenYBefore = n.y * scale + offsetY
                collapsed[idx] = !collapsed[idx]
                relayout()
                // Keep the tapped branch under the finger.
                val moved = nodes.firstOrNull { it.level == 1 && it.branchIndex == idx }
                if (moved != null) {
                    offsetY += screenYBefore - (moved.y * scale + offsetY)
                }
                invalidate()
                return
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    // ------------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // Lines first, so boxes sit on top of them.
        for (n in nodes) {
            val p = n.parent ?: continue
            val x1 = p.x + p.w
            val y1 = p.y + p.h / 2f
            val x2 = n.x
            val y2 = n.y + n.h / 2f
            val mid = (x1 + x2) / 2f
            path.reset()
            path.moveTo(x1, y1)
            path.cubicTo(mid, y1, mid, y2, x2, y2)
            edgePaint.color = if (n.level == 2) {
                Color.argb(170, Color.red(n.color), Color.green(n.color), Color.blue(n.color))
            } else {
                n.color
            }
            canvas.drawPath(path, edgePaint)
        }

        for (n in nodes) {
            drawNode(canvas, n)
        }

        canvas.restore()
    }

    private fun drawNode(canvas: Canvas, n: Node) {
        val radius = dp(12f)
        rect.set(n.x, n.y, n.x + n.w, n.y + n.h)

        when (n.level) {
            0 -> {
                fillPaint.color = Color.parseColor("#F8FAFC")
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }

            1 -> {
                fillPaint.color = n.color
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }

            else -> {
                fillPaint.color = Color.parseColor("#1E293B")
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                strokePaint.color = n.color
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
            }
        }

        val layout = n.layout
        if (layout != null) {
            canvas.save()
            canvas.translate(n.x + dp(12f), n.y + dp(8f))
            layout.draw(canvas)
            canvas.restore()
        }

        // Small +/- badge on branches that have points.
        if (n.level == 1 && branches[n.branchIndex].children.isNotEmpty()) {
            val cx = n.x + n.w
            val cy = n.y + n.h / 2f
            val r = dp(9f)
            fillPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r, fillPaint)
            val label = if (collapsed[n.branchIndex]) "+" else "-"
            val ty = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(label, cx, ty, badgeTextPaint)
        }
    }

    companion object {
        private const val MIN_SCALE = 0.25f
        private const val MAX_SCALE = 3f
    }
}
