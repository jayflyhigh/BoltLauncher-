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
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.data.Prefs
import kotlin.math.roundToInt

/** Miniature of the launcher panel using the candidate theme's actual colors. */
internal class ThemePreviewDrawable(context: Context, candidate: Prefs.LauncherTheme) : Drawable() {
    private val paperNotebook = candidate == Prefs.LauncherTheme.PAPER_NOTEBOOK
    private val eInkBolt = if (candidate == Prefs.LauncherTheme.E_INK) {
        ContextCompat.getDrawable(context, R.drawable.bolt_eink_lightning)?.apply {
            setBounds(47, 25, 55, 33)
        }
    } else null
    private val cozyQuest = candidate == Prefs.LauncherTheme.COZY_QUEST
    private val orchard = if (cozyQuest) ContextCompat.getDrawable(context, R.drawable.bolt_cozy_orchard)
        ?.apply { setBounds(1, 1, 63, 35) } else null
    private val leafBolt = if (cozyQuest) ContextCompat.getDrawable(context, R.drawable.bolt_cozy_lightning)
        ?.apply { setBounds(48, 25, 56, 33) } else null
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
    private val cursor = color(R.attr.boltSearchCursorColor)
    private val accent = color(R.attr.boltThunderDockColor)
    private val panelFill = gradient(panel, panelEnd, 0f, 0f, 64f, 0f)
    private val rowFill = gradient(row, rowEnd, 5f, 0f, 59f, 0f)
    private val dockFill = gradient(
        dock, androidx.core.graphics.ColorUtils.compositeColors(dockTop, dock), 0f, 31f, 0f, 23f,
    )
    private val notebookMarker = androidx.core.content.ContextCompat.getColor(context, R.color.bolt_notebook_marker)
    private val notebookRule = androidx.core.content.ContextCompat.getColor(context, R.color.bolt_notebook_rule)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getIntrinsicWidth() = (64 * density).roundToInt()
    override fun getIntrinsicHeight() = (36 * density).roundToInt()

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 64f, bounds.height() / 36f)
        if (cozyQuest) {
            fill(canvas, 0f, 0f, 64f, 36f, 7f, rim)
            val clip = android.graphics.Path().apply {
                addRoundRect(1f, 1f, 63f, 35f, 6f, 6f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clip)
            orchard?.draw(canvas)
            fill(canvas, 4f, 17f, 60f, 34f, 4f, panel)
            fill(canvas, 6f, 19f, 58f, 25f, 2f, rowRim)
            fill(canvas, 7f, 20f, 57f, 24f, 1f, row)
            fill(canvas, 10f, 21f, 34f, 23f, 1f, rowText)
            fill(canvas, 9f, 28f, 27f, 30f, 1f, cursor)
            fill(canvas, 29f, 27f, 30f, 31f, 0.5f, cursor)
            leafBolt?.draw(canvas)
            canvas.restoreToCount(save)
            return
        }
        fill(canvas, 0f, 0f, 64f, 36f, 8f, 0xFF152237.toInt())
        fill(canvas, 1f, 1f, 63f, 35f, 7f, rim)
        fill(canvas, 2f, 2f, 62f, 34f, 6f, panel, panelFill)
        if (eInkBolt != null) {
            drawEInk(canvas, eInkBolt)
            canvas.restoreToCount(save)
            return
        }
        if (paperNotebook) {
            fill(canvas, 6f, 6f, 58f, 7f, 0f, notebookRule)
            fill(canvas, 6f, 9f, 58f, 10f, 0f, notebookRule)
            fill(canvas, 18f, 11f, 48f, 17f, 2f, notebookMarker)
            fill(canvas, 6f, 22f, 58f, 32f, 5f, rim)
        } else {
            fill(canvas, 6f, 6f, 58f, 20f, 4f, rowRim)
            fill(canvas, 7f, 7f, 57f, 19f, 3f, row, rowFill)
        }
        fill(canvas, 10f, 10f, 16f, 16f, 2f, rowText)
        fill(canvas, 20f, 12f, 46f, 14f, 1f, rowText)
        fill(canvas, 6f, 23f, 58f, 31f, 4f, dock, dockFill)
        fill(canvas, 11f, 26f, 29f, 28f, 1f, if (paperNotebook) rowText else 0xFFD8E6F0.toInt())
        fill(canvas, 30f, 25f, 31f, 29f, 0.5f, cursor)
        fill(canvas, 50f, 25f, 54f, 29f, 2f, accent)
        canvas.restoreToCount(save)
    }

    private fun drawEInk(canvas: Canvas, bolt: Drawable) {
        fill(canvas, 8f, 6f, 25f, 8f, 0.5f, cursor)
        fill(canvas, 8f, 11f, 33f, 13f, 1f, cursor)
        fill(canvas, 8f, 15f, 56f, 16f, 0f, rim)
        fill(canvas, 6f, 17f, 58f, 23f, 2f, row)
        fill(canvas, 9f, 19f, 35f, 21f, 1f, rowText)
        fill(canvas, 6f, 24f, 58f, 25f, 0f, rim)
        fill(canvas, 9f, 28f, 29f, 30f, 1f, cursor)
        fill(canvas, 31f, 27f, 32f, 31f, 0.5f, cursor)
        bolt.draw(canvas)
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
