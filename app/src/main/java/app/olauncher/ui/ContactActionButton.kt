package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton

internal class ContactActionButton(context: Context) : ImageButton(context) {
    var shortcutLabel: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val edgeInset = 12 * density
    private val bottomInset = 3 * density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics
        ).coerceAtMost(13 * density)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val label = shortcutLabel ?: return
        val centerX = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) edgeInset else width - edgeInset
        val baseline = height - labelPaint.descent() - bottomInset
        canvas.drawText(label, centerX, baseline, labelPaint)
    }
}
