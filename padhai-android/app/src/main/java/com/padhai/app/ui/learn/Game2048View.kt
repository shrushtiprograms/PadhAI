package com.padhai.app.ui.learn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * Small 2048 game shown on the Learn screen while the AI is working.
 * - Swipe anywhere on the board to slide all tiles.
 * - Equal tiles merge into one bigger tile.
 * - When no move is left, the game restarts by itself after a moment.
 */
class Game2048View @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Called whenever the score (or best score) changes. */
    var onScoreChanged: ((score: Int, best: Int) -> Unit)? = null

    private val grid = Array(SIZE) { IntArray(SIZE) }
    private val pop = Array(SIZE) { BooleanArray(SIZE) }
    private var popProgress = 1f
    private var popAnimator: ValueAnimator? = null

    private var score = 0
    private var gameOver = false

    private var cellSize = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var gap = 0f
    private var radius = 0f

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var swipeHandled = false
    private val swipeThreshold = 24f * resources.displayMetrics.density

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2A3C")
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B3648")
    }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 24, 34, 49)
    }
    private val rect = RectF()

    private val restart = Runnable {
        resetGame()
    }

    /** Start a fresh game. Call when the loading card appears. */
    fun start() {
        resetGame()
    }

    /** Stop timers and animations. Call when loading finishes. */
    fun stop() {
        removeCallbacks(restart)
        popAnimator?.cancel()
        popProgress = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val margin = 8f * density
        val side = min(w, h).toFloat() - 2f * margin
        cellSize = side / SIZE
        boardLeft = (w - side) / 2f
        boardTop = (h - side) / 2f
        gap = 6f * density
        radius = 6f * density
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun resetGame() {
        removeCallbacks(restart)
        gameOver = false
        score = 0
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                grid[r][c] = 0
            }
        }
        clearPop()
        spawnTile()
        spawnTile()
        startPop()
        onScoreChanged?.invoke(score, best)
        invalidate()
    }

    private fun clearPop() {
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                pop[r][c] = false
            }
        }
    }

    private fun startPop() {
        popAnimator?.cancel()
        popProgress = 0f
        popAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140L
            addUpdateListener {
                popProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun spawnTile() {
        val empty = ArrayList<Int>()
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                if (grid[r][c] == 0) empty.add(r * SIZE + c)
            }
        }
        if (empty.isEmpty()) return
        val pick = empty[Random.nextInt(empty.size)]
        val r = pick / SIZE
        val c = pick % SIZE
        grid[r][c] = if (Random.nextInt(10) == 0) 4 else 2
        pop[r][c] = true
    }

    /** Cell position for the k-th tile of a line, counted from the edge tiles move towards. */
    private fun position(dir: Int, line: Int, k: Int): Pair<Int, Int> = when (dir) {
        DIR_LEFT -> Pair(line, k)
        DIR_RIGHT -> Pair(line, SIZE - 1 - k)
        DIR_UP -> Pair(k, line)
        else -> Pair(SIZE - 1 - k, line)
    }

    /**
     * Slides and merges one line in place. Index 0 is the edge tiles move towards.
     * Returns the score gained. mergedOut marks the cells that were created by a merge.
     */
    private fun slide(values: IntArray, mergedOut: BooleanArray): Int {
        val tiles = values.filter { it != 0 }
        val result = IntArray(SIZE)
        var gained = 0
        var i = 0
        var w = 0
        while (i < tiles.size) {
            if (i + 1 < tiles.size && tiles[i] == tiles[i + 1]) {
                val merged = tiles[i] * 2
                result[w] = merged
                mergedOut[w] = true
                gained += merged
                i += 2
            } else {
                result[w] = tiles[i]
                i += 1
            }
            w += 1
        }
        for (k in 0 until SIZE) {
            values[k] = result[k]
        }
        return gained
    }

    private fun move(dir: Int) {
        if (gameOver) return

        var moved = false
        var gained = 0
        val mergedCells = Array(SIZE) { BooleanArray(SIZE) }

        for (line in 0 until SIZE) {
            val values = IntArray(SIZE)
            for (k in 0 until SIZE) {
                val (r, c) = position(dir, line, k)
                values[k] = grid[r][c]
            }
            val before = values.copyOf()
            val mergedLine = BooleanArray(SIZE)
            gained += slide(values, mergedLine)
            for (k in 0 until SIZE) {
                val (r, c) = position(dir, line, k)
                grid[r][c] = values[k]
                mergedCells[r][c] = mergedLine[k]
                if (values[k] != before[k]) moved = true
            }
        }

        if (!moved) return

        clearPop()
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                if (mergedCells[r][c]) pop[r][c] = true
            }
        }
        spawnTile()

        score += gained
        if (score > best) best = score
        onScoreChanged?.invoke(score, best)

        startPop()

        if (!canMove()) {
            gameOver = true
            postDelayed(restart, 2200L)
        }
        invalidate()
    }

    private fun canMove(): Boolean {
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                val v = grid[r][c]
                if (v == 0) return true
                if (c + 1 < SIZE && grid[r][c + 1] == v) return true
                if (r + 1 < SIZE && grid[r + 1][c] == v) return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep the page from scrolling while the user plays.
                parent?.requestDisallowInterceptTouchEvent(true)
                touchStartX = event.x
                touchStartY = event.y
                swipeHandled = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!swipeHandled) {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (abs(dx) > swipeThreshold || abs(dy) > swipeThreshold) {
                        val dir = if (abs(dx) > abs(dy)) {
                            if (dx > 0) DIR_RIGHT else DIR_LEFT
                        } else {
                            if (dy > 0) DIR_DOWN else DIR_UP
                        }
                        move(dir)
                        swipeHandled = true
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun tileColor(value: Int): Int = when (value) {
        2 -> Color.parseColor("#EEE4DA")
        4 -> Color.parseColor("#EDE0C8")
        8 -> Color.parseColor("#F2B179")
        16 -> Color.parseColor("#F59563")
        32 -> Color.parseColor("#F67C5F")
        64 -> Color.parseColor("#F65E3B")
        128 -> Color.parseColor("#EDCF72")
        256 -> Color.parseColor("#EDCC61")
        512 -> Color.parseColor("#EDC850")
        1024 -> Color.parseColor("#EDC53F")
        2048 -> Color.parseColor("#EDC22E")
        else -> Color.parseColor("#3C3A32")
    }

    private fun textColor(value: Int): Int =
        if (value <= 4) Color.parseColor("#776E65") else Color.WHITE

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize == 0f) return

        val boardSide = cellSize * SIZE

        // Board background
        rect.set(boardLeft, boardTop, boardLeft + boardSide, boardTop + boardSide)
        canvas.drawRoundRect(rect, radius * 1.3f, radius * 1.3f, boardPaint)

        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                val left = boardLeft + c * cellSize
                val top = boardTop + r * cellSize

                // Empty slot
                rect.set(
                    left + gap / 2f,
                    top + gap / 2f,
                    left + cellSize - gap / 2f,
                    top + cellSize - gap / 2f
                )
                canvas.drawRoundRect(rect, radius, radius, emptyPaint)

                val value = grid[r][c]
                if (value == 0) continue

                val scale = if (pop[r][c]) 0.6f + 0.4f * popProgress else 1f
                val cx = left + cellSize / 2f
                val cy = top + cellSize / 2f
                val half = (cellSize - gap) / 2f * scale

                rect.set(cx - half, cy - half, cx + half, cy + half)
                tilePaint.color = tileColor(value)
                canvas.drawRoundRect(rect, radius, radius, tilePaint)

                val sizeFactor = when {
                    value < 100 -> 0.46f
                    value < 1000 -> 0.38f
                    else -> 0.30f
                }
                textPaint.color = textColor(value)
                textPaint.textSize = cellSize * sizeFactor * scale
                val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(value.toString(), cx, textY, textPaint)
            }
        }

        if (gameOver) {
            rect.set(boardLeft, boardTop, boardLeft + boardSide, boardTop + boardSide)
            canvas.drawRoundRect(rect, radius * 1.3f, radius * 1.3f, overlayPaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = cellSize * 0.40f
            canvas.drawText(
                "No moves left",
                boardLeft + boardSide / 2f,
                boardTop + boardSide / 2f - cellSize * 0.1f,
                textPaint
            )
            textPaint.textSize = cellSize * 0.30f
            canvas.drawText(
                "Score $score",
                boardLeft + boardSide / 2f,
                boardTop + boardSide / 2f + cellSize * 0.35f,
                textPaint
            )
        }
    }

    companion object {
        private const val SIZE = 4
        private const val DIR_LEFT = 0
        private const val DIR_RIGHT = 1
        private const val DIR_UP = 2
        private const val DIR_DOWN = 3

        // Best score for this app session.
        private var best = 0
    }
}