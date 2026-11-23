package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.TypedValue
import app.olauncher.R
import app.olauncher.data.Prefs
import kotlin.math.roundToInt

/** Miniature of the launcher panel using the candidate theme's actual colors. */
internal class ThemePreviewDrawable(context: Context, candidate: Prefs.LauncherTheme) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val theme = context.resources.newTheme().apply {
        applyStyle(R.style.AppTheme, true)
        if (candidate.overlay != 0) applyStyle(candidate.overlay, true)
    }
    private val panel = color(R.attr.boltPanelColor)
    private val panelEnd = color(R.attr.boltPanelEndColor)
    private val rim = color(R.attr.boltPanelOutlineColor)
    private val row = color(R.attr.boltSelectedRowColor)
    private val rowEnd = color(R.attr.boltSelectedRowEndColor)
    private val rowRim = color(R.attr.boltSelectedRowOutlineColor)
    private val rowText = color(R.attr.boltSelectedRowTextColor)
    private val dock = color(R.attr.boltDockColor)
    private val dockTop = color(R.attr.boltDockTopColor)
    private val accent = color(R.attr.boltThunderDockColor)
    private val panelFill = gradient(panel, panelEnd, 0f, 0f, 64f, 0f)
    private val rowFill = gradient(row, rowEnd, 5f, 0f, 59f, 0f)
    private val dockFill = gradient(dock, dockTop, 0f, 31f, 0f, 23f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getIntrinsicWidth() = (64 * density).roundToInt()
    override fun getIntrinsicHeight() = (36 * density).roundToInt()

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 64f, bounds.height() / 36f)
        fill(canvas, 0f, 0f, 64f, 36f, 8f, 0xFF152237.toInt())
        fill(canvas, 1f, 1f, 63f, 35f, 7f, rim)
        fill(canvas, 2f, 2f, 62f, 34f, 6f, panel, panelFill)
        fill(canvas, 6f, 6f, 58f, 20f, 4f, rowRim)
        fill(canvas, 7f, 7f, 57f, 19f, 3f, row, rowFill)
        fill(canvas, 10f, 10f, 16f, 16f, 2f, rowText)
        fill(canvas, 20f, 12f, 46f, 14f, 1f, rowText)
        fill(canvas, 6f, 23f, 58f, 31f, 4f, dock, dockFill)
        fill(canvas, 11f, 26f, 29f, 28f, 1f, 0xFFD8E6F0.toInt())
        fill(canvas, 50f, 25f, 54f, 29f, 2f, accent)
        canvas.restoreToCount(save)
    }

    private fun fill(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, color: Int, shader: Shader? = null,
    ) {
        paint.color = if (shader == null) color else Color.WHITE
        paint.shader = shader
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        paint.shader = null
    }

    private fun color(attr: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) value.data else Color.TRANSPARENT
    }

    private fun gradient(start: Int, end: Int, x1: Float, y1: Float, x2: Float, y2: Float): Shader? =
        if (end == 0 || end == start) null else LinearGradient(x1, y1, x2, y2, start, end, Shader.TileMode.CLAMP)

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
