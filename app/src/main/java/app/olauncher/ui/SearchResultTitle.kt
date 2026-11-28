package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.data.Prefs

/** Draws the notebook marker with the same line geometry as the visible title. */
class SearchResultTitle(context: Context, attrs: AttributeSet?) : AppCompatTextView(context, attrs) {
    private val notebook = Prefs(context).launcherTheme == Prefs.LauncherTheme.PAPER_NOTEBOOK
    private val density = resources.displayMetrics.density
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.bolt_notebook_marker)
    }
    private val stroke = Path()

    var markerSelected = false
        set(value) {
            if (field == value) return
            field = value
            if (notebook) invalidate()
        }

    var markerSweep = 0f
        set(value) {
            if (field == value) return
            field = value
            if (notebook) invalidate()
        }

    var trailingHintWidth = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (trailingHintWidth == 0) return
        // Reserve horizontal room for the hint without changing the row's original height.
        val width = (measuredWidth - trailingHintWidth).coerceAtLeast(0)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val lines = layout
        val coverage = if (markerSelected) 1f else markerSweep
        if (notebook && lines != null && coverage > 0f) {
            val leftInset = compoundPaddingLeft.toFloat()
            val topInset = baseline - lines.getLineBaseline(0)
            val save = canvas.save()
            val bleed = 3f * density
            if (coverage < 1f) {
                var start = width.toFloat()
                var end = 0f
                for (line in 0 until lines.lineCount) {
                    start = minOf(start, leftInset + lines.getLineLeft(line) - bleed)
                    end = maxOf(end, leftInset + lines.getLineRight(line) + bleed)
                }
                if (lines.getParagraphDirection(0) < 0) {
                    canvas.clipRect(end - (end - start) * coverage, 0f, width.toFloat(), height.toFloat())
                } else {
                    canvas.clipRect(0f, 0f, start + (end - start) * coverage, height.toFloat())
                }
            }
            for (line in 0 until lines.lineCount) {
                if (lines.getLineStart(line) == lines.getLineEnd(line)) continue
                val left = leftInset + lines.getLineLeft(line) - bleed
                val right = leftInset + lines.getLineRight(line) + bleed
                val top = (topInset + lines.getLineTop(line) + density).coerceAtLeast(0f)
                val bottom = (topInset + lines.getLineBottom(line) - density).coerceAtMost(height.toFloat())
                val tip = minOf(4f * density, (right - left) / 2f, (bottom - top) / 2f)
                if (tip <= 0f) continue
                stroke.rewind()
                stroke.moveTo(left + tip, top + 0.5f * density)
                stroke.lineTo(right - tip, top)
                stroke.quadTo(right, top, right, top + tip)
                stroke.lineTo(right - 0.5f * density, bottom - tip)
                stroke.quadTo(right - density, bottom, right - tip, bottom)
                stroke.lineTo(left + tip, bottom - 0.5f * density)
                stroke.quadTo(left, bottom, left, bottom - tip)
                stroke.lineTo(left + 0.5f * density, top + tip)
                stroke.quadTo(left + density, top, left + tip, top + 0.5f * density)
                stroke.close()
                canvas.drawPath(stroke, marker)
            }
            canvas.restoreToCount(save)
        }
        super.onDraw(canvas)
    }
}
