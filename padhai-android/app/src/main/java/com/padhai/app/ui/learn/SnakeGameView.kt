package com.padhai.app.ui.learn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Small Snake game shown on the Learn screen while the AI is working.
 * - Swipe anywhere on the game area to steer the snake.
 * - Walls wrap around, so you only lose by biting yourself.
 * - After "Game over" the game restarts by itself, so it never gets stuck.
 */
class SnakeGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Cell(val x: Int, val y: Int)

    /** Called whenever the score (or best score) changes. */
    var onScoreChanged: ((score: Int, best: Int) -> Unit)? = null

    private val snake = ArrayDeque<Cell>()
    private var food = Cell(0, 0)

    private val cols = 16
    private var rows = 10
    private var cellSize = 0f
    private var offsetY = 0f

    private var dirX = 1
    private var dirY = 0
    private var nextDirX = 1
    private var nextDirY = 0

    private var score = 0
    private var tickMs = START_TICK_MS
    private var running = false
    private var gameOver = false

    private var touchStartX = 0f
    private var touchStartY = 0f
    private val swipeThreshold = 20f * resources.displayMetrics.density

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A7F3D0")
    }
    private val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F87171")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 18f * resources.displayMetrics.density
        isFakeBoldText = true
    }
    private val rect = RectF()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            invalidate()
            postDelayed(this, tickMs)
        }
    }

    private val restart = Runnable {
        resetGame()
    }

    /** Start (or restart) the game. Call when the loading card appears. */
    fun start() {
        running = true
        removeCallbacks(tick)
        resetGame()
        postDelayed(tick, tickMs)
    }

    /** Stop the game loop. Call when loading finishes. */
    fun stop() {
        running = false
        removeCallbacks(tick)
        removeCallbacks(restart)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cellSize = w.toFloat() / cols
        rows = max(6, (h / cellSize).toInt())
        offsetY = (h - rows * cellSize) / 2f
        resetGame()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun resetGame() {
        if (cellSize == 0f) return
        removeCallbacks(restart)
        gameOver = false
        score = 0
        tickMs = START_TICK_MS
        dirX = 1
        dirY = 0
        nextDirX = 1
        nextDirY = 0
        snake.clear()
        val startY = rows / 2
        snake.addLast(Cell(3, startY))
        snake.addLast(Cell(2, startY))
        snake.addLast(Cell(1, startY))
        spawnFood()
        onScoreChanged?.invoke(score, best)
        invalidate()
    }

    private fun spawnFood() {
        val free = ArrayList<Cell>()
        for (x in 0 until cols) {
            for (y in 0 until rows) {
                val c = Cell(x, y)
                if (!snake.contains(c)) free.add(c)
            }
        }
        food = if (free.isEmpty()) Cell(0, 0) else free[Random.nextInt(free.size)]
    }

    private fun step() {
        if (cellSize == 0f || gameOver || snake.isEmpty()) return

        dirX = nextDirX
        dirY = nextDirY

        val head = snake.first()
        val newHead = Cell(
            (head.x + dirX + cols) % cols,
            (head.y + dirY + rows) % rows
        )
        val willEat = newHead == food

        // The tail moves away unless the snake is growing.
        if (!willEat) snake.removeLast()

        if (snake.contains(newHead)) {
            gameOver = true
            postDelayed(restart, 900L)
            return
        }

        snake.addFirst(newHead)

        if (willEat) {
            score++
            if (score > best) best = score
            tickMs = max(MIN_TICK_MS, tickMs - 5L)
            spawnFood()
            onScoreChanged?.invoke(score, best)
        }
    }

    private fun steer(x: Int, y: Int) {
        // Do not allow an instant 180 degree turn into yourself.
        if (x == -dirX && y == -dirY) return
        nextDirX = x
        nextDirY = y
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep the page from scrolling while the user plays.
                parent?.requestDisallowInterceptTouchEvent(true)
                touchStartX = event.x
                touchStartY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (abs(dx) > swipeThreshold || abs(dy) > swipeThreshold) {
                    if (abs(dx) > abs(dy)) {
                        steer(if (dx > 0) 1 else -1, 0)
                    } else {
                        steer(0, if (dy > 0) 1 else -1)
                    }
                    touchStartX = event.x
                    touchStartY = event.y
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize == 0f) return

        val pad = cellSize * 0.08f

        // Food
        canvas.drawCircle(
            food.x * cellSize + cellSize / 2f,
            offsetY + food.y * cellSize + cellSize / 2f,
            cellSize * 0.36f,
            foodPaint
        )

        // Snake (first cell is the head)
        var index = 0
        for (c in snake) {
            rect.set(
                c.x * cellSize + pad,
                offsetY + c.y * cellSize + pad,
                (c.x + 1) * cellSize - pad,
                offsetY + (c.y + 1) * cellSize - pad
            )
            canvas.drawRoundRect(
                rect,
                cellSize * 0.3f,
                cellSize * 0.3f,
                if (index == 0) headPaint else bodyPaint
            )
            index++
        }

        if (gameOver) {
            canvas.drawText(
                "Game over - Score $score",
                width / 2f,
                height / 2f,
                textPaint
            )
        }
    }

    companion object {
        private const val START_TICK_MS = 180L
        private const val MIN_TICK_MS = 90L

        // Best score for this app session.
        private var best = 0
    }
}