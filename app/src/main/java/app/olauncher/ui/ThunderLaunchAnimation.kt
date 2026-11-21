package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import app.olauncher.R
import kotlin.math.roundToInt

internal class ThunderLaunchAnimation(
    context: Context,
    private val icon: Drawable?,
    private val rowBounds: RectF,
    private val iconBounds: RectF,
    label: CharSequence,
    labelPaint: TextPaint,
    textDirection: TextDirectionHeuristic,
    private val labelX: Float,
    width: Int,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val yellow = ContextCompat.getColor(context, R.color.bolt_search_yellow)
    private val charcoal = ContextCompat.getColor(context, R.color.bolt_search_dock)
    private val corner = context.resources.getDimension(R.dimen.bolt_search_corner_radius)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelLayout = StaticLayout.Builder.obtain(
        label, 0, label.length, TextPaint(labelPaint).apply { color = Color.BLACK }, width.coerceAtLeast(1)
    ).setMaxLines(1)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setTextDirection(textDirection)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .build()

    var progress = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        paint.color = yellow
        canvas.drawRoundRect(rowBounds, corner, corner, paint)

        canvas.save()
        canvas.translate(labelX, rowBounds.centerY() - labelLayout.height / 2f)
        labelLayout.draw(canvas)
        canvas.restore()

        val image = icon ?: return
        val x = iconBounds.centerX()
        val y = iconBounds.centerY() - 25 * density * progress
        val radius = iconBounds.width() / 2f
        paint.color = charcoal
        paint.alpha = (255 * progress).roundToInt()
        canvas.drawCircle(x, y, radius + 4 * density, paint)
        paint.color = yellow
        paint.alpha = (255 * progress).roundToInt()
        paint.strokeWidth = 2 * density
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(x, y, radius + 4 * density, paint)
        paint.style = Paint.Style.FILL

        val width = image.intrinsicWidth.coerceAtLeast(1)
        val height = image.intrinsicHeight.coerceAtLeast(1)
        val scale = (2 * radius) / maxOf(width, height)
        val halfWidth = width * scale / 2f
        val halfHeight = height * scale / 2f
        image.setBounds((x - halfWidth).roundToInt(), (y - halfHeight).roundToInt(),
            (x + halfWidth).roundToInt(), (y + halfHeight).roundToInt())
        image.draw(canvas)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
