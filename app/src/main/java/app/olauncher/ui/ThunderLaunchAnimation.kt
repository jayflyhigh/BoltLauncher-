package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import app.olauncher.R
import kotlin.math.roundToInt

internal class ThunderLaunchAnimation(
    context: Context,
    private val icon: Drawable,
    private val rowBounds: RectF,
    private val iconBounds: RectF,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val yellow = ContextCompat.getColor(context, R.color.bolt_search_yellow)
    private val bolt = requireNotNull(ContextCompat.getDrawable(context, R.drawable.bolt_search_lightning))
        .mutate().apply { setTint(yellow) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()

    var progress = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val travel = ((progress - 0.16f) / 0.84f).coerceIn(0f, 1f)
        val rowAlpha = (1f - travel) * 0.85f + 0.15f
        val corner = 12 * density
        frame.set(rowBounds)
        frame.inset(density, density)

        paint.color = 0xFF0B0B0D.toInt()
        paint.alpha = (travel * 95).roundToInt()
        canvas.drawRect(bounds, paint)

        paint.color = yellow
        paint.alpha = (rowAlpha * 68).roundToInt()
        canvas.drawRoundRect(frame, corner, corner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.alpha = (rowAlpha * 255).roundToInt()
        canvas.drawRoundRect(frame, corner, corner, paint)
        paint.style = Paint.Style.FILL

        val x = iconBounds.centerX() + (bounds.centerX() - iconBounds.centerX()) * travel
        val y = iconBounds.centerY() + (bounds.centerY() - iconBounds.centerY()) * travel
        val radius = iconBounds.width() / 2f + 24 * density * travel

        val trail = (travel - 0.32f).coerceAtLeast(0f)
        val trailX = iconBounds.centerX() + (bounds.centerX() - iconBounds.centerX()) * trail
        val trailY = iconBounds.centerY() + (bounds.centerY() - iconBounds.centerY()) * trail
        paint.color = yellow
        paint.alpha = (180 * travel * (1f - travel)).roundToInt()
        paint.strokeWidth = 3 * density
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(trailX, trailY, x, y, paint)

        paint.color = 0xFF131316.toInt()
        paint.alpha = (travel * 255).roundToInt()
        canvas.drawCircle(x, y, radius + 8 * density, paint)
        paint.color = yellow
        paint.alpha = (travel * 220).roundToInt()
        paint.strokeWidth = 2 * density
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(x, y, radius + 8 * density, paint)
        paint.style = Paint.Style.FILL

        icon.setBounds((x - radius).roundToInt(), (y - radius).roundToInt(),
            (x + radius).roundToInt(), (y + radius).roundToInt())
        icon.draw(canvas)

        val boltRadius = (11 * density).roundToInt()
        val boltX = (x - radius * 0.72f).roundToInt()
        val boltY = (y + radius * 0.82f).roundToInt()
        bolt.alpha = (255 * (1f - travel * 0.35f)).roundToInt()
        bolt.setBounds(boltX - boltRadius, boltY - boltRadius,
            boltX + boltRadius, boltY + boltRadius)
        bolt.draw(canvas)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
