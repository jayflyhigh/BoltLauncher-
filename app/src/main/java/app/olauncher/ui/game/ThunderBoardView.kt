package app.olauncher.ui.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr
import app.olauncher.ui.LauncherMotion
import kotlin.math.min

internal class ThunderBoardView(context: Context, private val preview: Boolean = false) : View(context) {
    private companion object {
        const val STRIKE_DURATION = 360L
        const val STRIKE_SPACING = 55L
        const val STRIKE_SWEEP = 100L
        const val POP_START = 3 * STRIKE_SPACING + STRIKE_SWEEP
        const val CELEBRATION_DURATION = POP_START + 780L
        const val STATIC_DURATION = 900L
    }

    var game = ThunderBlocks()
    private var strikeRows = 0
    private var strikeStart = 0L
    private data class Celebration(val rows: Int, val animated: Boolean)

    private val celebrations = ArrayDeque<Celebration>()
    private var celebrationStart = 0L
    private val celebrationText = context.getString(R.string.thunder_game_tetris)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        strokeJoin = Paint.Join.ROUND
    }
    private val strikePath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val dock = context.getColorFromAttr(R.attr.boltDockColor).let {
        (it and 0x00FFFFFF) or (Color.alpha(it).coerceAtMost(225) shl 24)
    }
    private val edge = context.getColorFromAttr(R.attr.boltPanelOutlineColor)
    private val reflection = context.getColorFromAttr(R.attr.boltGlassReflectionColor)
    private val accent = context.getColor(R.color.bolt_search_yellow)
    private val palette = intArrayOf(
        accent, 0xFF81D7ED.toInt(), 0xFFAA9EF1.toInt(), 0xFFF19D76.toInt(),
        0xFF8CD4AF.toInt(), 0xFFEEA6C0.toInt(), 0xFFADBCEA.toInt(), 0xFFC6D786.toInt(),
    )
    private val spacing = resources.displayMetrics.density
    var cellSize = 0f
        private set

    override fun onDraw(canvas: Canvas) {
        if (preview) {
            val piece = game.next
            val columns = piece.cells.maxOf { it.x } + 1
            val rows = piece.cells.maxOf { it.y } + 1
            cellSize = min(width / 4f, height / 4f)
            val left = (width - columns * cellSize) / 2
            val top = (height - rows * cellSize) / 2
            piece.cells.forEach { drawCell(canvas, left, top, it.x, it.y, piece.color) }
            return
        }

        val columns = ThunderBlocks.COLUMNS
        val rows = ThunderBlocks.ROWS
        cellSize = min((width - spacing * 2) / columns, (height - spacing * 2) / rows)
        if (cellSize <= 0f) return
        val left = (width - cellSize * columns) / 2
        val top = (height - cellSize * rows) / 2
        val right = width - left
        val bottom = height - top
        val corner = spacing * 5
        bounds.set(left, top, right, bottom)
        paint.color = dock
        canvas.drawRoundRect(bounds, corner, corner, paint)
        paint.color = reflection
        canvas.drawLine(left + corner, top + spacing, right - corner, top + spacing, paint)
        paint.color = 0x24FFFFFF
        for (col in 1 until columns) {
            val x = left + cellSize * col
            canvas.drawLine(x, top, x, bottom, paint)
        }
        for (row in 1 until rows) {
            val y = top + cellSize * row
            canvas.drawLine(left, y, right, y, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = spacing
        paint.color = edge
        canvas.drawRoundRect(bounds, corner, corner, paint)
        paint.style = Paint.Style.FILL

        game.board.forEachIndexed { index, color ->
            if (color != 0) drawCell(canvas, left, top, index % columns, index / columns, color)
        }
        drawStrike(canvas, left, right, top)
        drawCelebration(canvas, left, right, top)
        if (!game.gameOver) {
            val landing = game.landingY
            if (landing != game.y) {
                game.active.cells.forEach {
                    drawCell(canvas, left, top, game.x + it.x, landing + it.y, game.active.color, ghost = true)
                }
            }
            game.active.cells.forEach {
                drawCell(canvas, left, top, game.x + it.x, game.y + it.y, game.active.color)
            }
        }
    }

    fun strike(rows: Int, fourRowClear: Boolean) {
        val animated = LauncherMotion.isEnabled(context)
        if (fourRowClear) {
            if (celebrations.isEmpty()) celebrationStart = SystemClock.uptimeMillis()
            celebrations.addLast(Celebration(rows, animated))
        } else if (animated) {
            strikeRows = rows
            strikeStart = SystemClock.uptimeMillis()
        }
        invalidate()
    }

    fun resetStrike() {
        strikeRows = 0
        celebrations.clear()
    }

    private fun drawStrike(canvas: Canvas, left: Float, right: Float, top: Float) {
        if (strikeRows == 0) return
        val elapsed = SystemClock.uptimeMillis() - strikeStart
        if (elapsed >= STRIKE_DURATION) {
            strikeRows = 0
            return
        }
        for (row in 0 until ThunderBlocks.ROWS) {
            if (strikeRows and (1 shl row) != 0) {
                drawRowStrike(canvas, left, right, top, row, elapsed, bright = false)
            }
        }
        postInvalidateOnAnimation()
    }

    private fun drawCelebration(canvas: Canvas, left: Float, right: Float, top: Float) {
        var clear = celebrations.firstOrNull() ?: return
        val now = SystemClock.uptimeMillis()
        var elapsed = now - celebrationStart
        val duration = if (clear.animated) CELEBRATION_DURATION else STATIC_DURATION
        if (elapsed >= duration) {
            celebrations.removeFirst()
            clear = celebrations.firstOrNull() ?: return
            celebrationStart = now
            elapsed = 0
        }

        if (!clear.animated) {
            drawCelebrationText(canvas, left, right, top, scale = 1f, opacity = 1f)
            postInvalidateDelayed(STATIC_DURATION - elapsed)
            return
        }

        var delay = 0L
        for (row in 0 until ThunderBlocks.ROWS) {
            if (clear.rows and (1 shl row) == 0) continue
            val rowElapsed = elapsed - delay
            if (rowElapsed in 0 until STRIKE_DURATION) {
                drawRowStrike(canvas, left, right, top, row, rowElapsed, bright = true)
            }
            delay += STRIKE_SPACING
        }

        if (elapsed >= POP_START) {
            val messageElapsed = elapsed - POP_START
            val pop = LauncherMotion.settle.getInterpolation((messageElapsed / 150f).coerceAtMost(1f))
            val opacity = 1f - ((messageElapsed - 550L) / 230f).coerceIn(0f, 1f)
            drawCelebrationText(canvas, left, right, top, scale = 0.65f + 0.35f * pop, opacity)
        }
        postInvalidateOnAnimation()
    }

    private fun drawCelebrationText(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        scale: Float,
        opacity: Float,
    ) {
        val center = (left + right) / 2
        textPaint.textSize = min(cellSize * 1.6f, spacing * 32) * scale
        val baseline = top + cellSize * 3.5f - (textPaint.ascent() + textPaint.descent()) / 2
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = spacing * 3
        textPaint.color = 0xFF181200.toInt()
        textPaint.alpha = (opacity * 255).toInt()
        canvas.drawText(celebrationText, center, baseline, textPaint)
        textPaint.style = Paint.Style.FILL
        textPaint.color = accent
        textPaint.alpha = (opacity * 255).toInt()
        canvas.drawText(celebrationText, center, baseline, textPaint)
    }

    private fun drawRowStrike(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        row: Int,
        elapsed: Long,
        bright: Boolean,
    ) {
        val reach = ((elapsed + 16f) / STRIKE_SWEEP).coerceAtMost(1f)
        val opacity = 1f - elapsed.toFloat() / STRIKE_DURATION
        val center = top + (row + 0.5f) * cellSize
        val save = canvas.save()
        canvas.clipRect(left, center - cellSize / 2, left + (right - left) * reach, center + cellSize / 2)
        paint.color = accent
        paint.alpha = (opacity * 95).toInt()
        canvas.drawRect(left, center - cellSize / 2, right, center + cellSize / 2, paint)

        strikePath.rewind()
        strikePath.moveTo(left, center)
        for (step in 1 until ThunderBlocks.COLUMNS) {
            val offset = when (step % 4) {
                0 -> 0.12f
                1 -> -0.28f
                2 -> 0.29f
                else -> -0.15f
            }
            strikePath.lineTo(left + step * cellSize, center + offset * cellSize)
        }
        strikePath.lineTo(right, center)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = spacing * if (bright) 7f else 5f
        paint.alpha = (opacity * if (bright) 255 else 160).toInt()
        canvas.drawPath(strikePath, paint)
        paint.color = Color.WHITE
        paint.alpha = (opacity * 255).toInt()
        paint.strokeWidth = spacing * if (bright) 2.3f else 1.6f
        canvas.drawPath(strikePath, paint)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        canvas.restoreToCount(save)
    }

    private fun drawCell(canvas: Canvas, left: Float, top: Float, x: Int, y: Int, color: Int, ghost: Boolean = false) {
        val inset = spacing * 0.9f
        bounds.set(left + x * cellSize + inset, top + y * cellSize + inset,
            left + (x + 1) * cellSize - inset, top + (y + 1) * cellSize - inset)
        paint.color = palette[color - 1]
        val radius = spacing * 2.5f
        if (ghost) paint.alpha = 95
        canvas.drawRoundRect(bounds, radius, radius, paint)
        if (ghost) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = spacing * 2.4f
            paint.color = 0xFF171717.toInt()
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.strokeWidth = spacing * 1.1f
            paint.color = Color.WHITE
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
        }
    }
}
