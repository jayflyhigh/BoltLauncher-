package app.olauncher.ui.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr
import kotlin.math.min

internal class ThunderBoardView(context: Context, private val preview: Boolean = false) : View(context) {
    var game = ThunderBlocks()
    private var strikeRows = 0
    private var strikeStart = 0L
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
        if (!game.gameOver) {
            val landing = game.landingY
            game.active.cells.forEach {
                drawCell(canvas, left, top, game.x + it.x, landing + it.y, game.active.color, ghost = true)
                drawCell(canvas, left, top, game.x + it.x, game.y + it.y, game.active.color)
            }
        }
        drawStrike(canvas, left, right, top)
    }

    fun strike(rows: Int) {
        strikeRows = rows
        strikeStart = SystemClock.uptimeMillis()
        invalidate()
    }

    fun resetStrike() {
        strikeRows = 0
    }

    private fun drawStrike(canvas: Canvas, left: Float, right: Float, top: Float) {
        if (strikeRows == 0) return
        val elapsed = SystemClock.uptimeMillis() - strikeStart
        val duration = 360f
        if (elapsed >= duration) {
            strikeRows = 0
            return
        }
        val reach = ((elapsed + 16f) / 100f).coerceAtMost(1f)
        val opacity = 1f - elapsed / duration
        val save = canvas.save()
        canvas.clipRect(left, top, left + (right - left) * reach, top + cellSize * ThunderBlocks.ROWS)
        for (row in 0 until ThunderBlocks.ROWS) {
            if (strikeRows and (1 shl row) == 0) continue
            val center = top + (row + 0.5f) * cellSize
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
            paint.strokeWidth = spacing * 5
            paint.alpha = (opacity * 160).toInt()
            canvas.drawPath(strikePath, paint)
            paint.color = Color.WHITE
            paint.alpha = (opacity * 255).toInt()
            paint.strokeWidth = spacing * 1.6f
            canvas.drawPath(strikePath, paint)
            paint.style = Paint.Style.FILL
        }
        paint.strokeWidth = 0f
        canvas.restoreToCount(save)
        postInvalidateOnAnimation()
    }

    private fun drawCell(canvas: Canvas, left: Float, top: Float, x: Int, y: Int, color: Int, ghost: Boolean = false) {
        val inset = spacing * 0.9f
        bounds.set(left + x * cellSize + inset, top + y * cellSize + inset,
            left + (x + 1) * cellSize - inset, top + (y + 1) * cellSize - inset)
        paint.color = palette[color - 1]
        if (ghost) {
            paint.alpha = 115
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = spacing
        }
        val radius = spacing * 2.5f
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }
}
