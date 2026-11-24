package app.olauncher.ui

import android.app.ActivityOptions
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import app.olauncher.helper.getColorFromAttr
import app.olauncher.R
import kotlin.math.roundToInt

internal class ThunderLaunchAnimation(
    private val source: View,
    private val icon: Drawable,
    private val iconBounds: RectF,
    private val markerTitle: SearchResultTitle?,
) : Drawable() {
    private val density = source.resources.displayMetrics.density
    private val lift = 4f * density
    private val originalAlpha = source.alpha
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = source.context.getColorFromAttr(R.attr.boltThunderPulseColor)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    init {
        source.alpha = 0f
        val width = icon.intrinsicWidth.takeIf { it > 0 } ?: source.width
        val height = icon.intrinsicHeight.takeIf { it > 0 } ?: source.height
        val scale = minOf(iconBounds.width() / width, iconBounds.height() / height)
        val halfWidth = width * scale / 2f
        val halfHeight = height * scale / 2f
        icon.setBounds(
            (iconBounds.centerX() - halfWidth).roundToInt(),
            (iconBounds.centerY() - halfHeight).roundToInt(),
            (iconBounds.centerX() + halfWidth).roundToInt(),
            (iconBounds.centerY() + halfHeight).roundToInt(),
        )
    }

    var progress = 0.25f
        set(value) {
            field = value
            markerTitle?.markerSweep = ((value - 0.25f) / 0.75f).coerceIn(0f, 1f)
            invalidateSelf()
        }

    fun launchOptions() = ActivityOptions.makeScaleUpAnimation(
        source, 0, -lift.roundToInt(), source.width, source.height
    ).toBundle()

    fun restoreIcon() {
        source.alpha = originalAlpha
        markerTitle?.markerSweep = 0f
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, -lift * progress)
        pulsePaint.alpha = (200f * (1f - progress)).roundToInt()
        canvas.drawCircle(iconBounds.centerX(), iconBounds.centerY(),
            iconBounds.width() / 2f + density * (1f + 4f * progress), pulsePaint)
        icon.draw(canvas)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
