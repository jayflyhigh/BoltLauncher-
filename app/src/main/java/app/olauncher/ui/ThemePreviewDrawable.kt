package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.data.Prefs
import kotlin.math.roundToInt

/** A search preview rendered with the candidate theme's surfaces, text and launcher icons. */
internal class ThemePreviewDrawable(context: Context, candidate: Prefs.LauncherTheme) : Drawable() {
    private val prefs = Prefs(context)
    private val theme = context.resources.newTheme().apply {
        applyStyle(R.style.AppTheme, true)
        val overlay = prefs.overlayFor(candidate)
        if (overlay != 0) applyStyle(overlay, true)
    }
    private val wallpaper = WallpaperThemeColors.forTheme(prefs, candidate)
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panel = wallpaper?.panel ?: color(R.attr.boltPanelColor)
    private val panelEnd = wallpaper?.panelEnd ?: color(R.attr.boltPanelEndColor, panel)
    private val outline = wallpaper?.outline ?: color(R.attr.boltPanelOutlineColor)
    private val row = wallpaper?.row ?: color(R.attr.boltRowColor)
    private val rowEnd = wallpaper?.row ?: color(R.attr.boltRowBottomColor, row)
    private val selected = wallpaper?.accent ?: color(R.attr.boltSelectedRowColor)
    private val selectedEnd = wallpaper?.accent ?: color(R.attr.boltSelectedRowEndColor, selected)
    private val selectedOutline = wallpaper?.accent ?: color(R.attr.boltSelectedRowOutlineColor)
    private val dock = wallpaper?.dock ?: color(R.attr.boltDockColor)
    private val dockTop = ColorUtils.compositeColors(color(R.attr.boltDockTopColor), dock)
    private val cursor = wallpaper?.accent ?: color(R.attr.boltSearchCursorColor)
    private val text = color(R.attr.boltResultTextColor)
    private val selectedText = if (wallpaper != null) Color.BLACK else color(R.attr.boltSelectedRowTextColor)
    private val panelRadius = dimension(R.attr.boltPanelCornerRadius, 13f)
    private val selectedRadius = dimension(R.attr.boltSelectedRowCornerRadius, 9f)
    private val panelFill = gradient(panel, panelEnd, 0f, 280f, horizontal = true)
    private val rowFill = gradient(row, rowEnd, 9f, 44f)
    private val selectionFill = gradient(selected, selectedEnd, 8f, 272f, horizontal = true)
    private val dockFill = gradient(dockTop, dock, 105f, 144f)
    private val paperRule = ContextCompat.getColor(context, R.color.bolt_notebook_rule)
    private val paperNotebook = candidate == Prefs.LauncherTheme.PAPER_NOTEBOOK
    private val orchard = if (candidate == Prefs.LauncherTheme.COZY_QUEST) {
        ContextCompat.getDrawable(context, R.drawable.bolt_cozy_orchard)?.apply { setBounds(0, 0, 280, 152) }
    } else null
    private val panelClip = Path().apply {
        addRoundRect(0f, 0f, 280f, 152f, panelRadius, panelRadius, Path.Direction.CW)
    }
    private val web = icon(
        context,
        when (candidate) {
            Prefs.LauncherTheme.LIQUID_GLASS -> R.drawable.liquid_glass_web
            Prefs.LauncherTheme.WINDOWS_XP -> R.drawable.xp_web
            else -> R.drawable.dock_web
        },
        201, 116, 222, 137,
    ).apply {
        if (candidate != Prefs.LauncherTheme.LIQUID_GLASS && candidate != Prefs.LauncherTheme.WINDOWS_XP) {
            this?.setTint(wallpaper?.accent ?: color(R.attr.boltResultActionColor))
        }
    }
    private val thunder = icon(
        context,
        when (candidate) {
            Prefs.LauncherTheme.COZY_QUEST -> R.drawable.bolt_cozy_lightning
            Prefs.LauncherTheme.E_INK -> R.drawable.bolt_eink_lightning
            Prefs.LauncherTheme.LIQUID_GLASS -> R.drawable.liquid_glass_bolt
            Prefs.LauncherTheme.SEATTLE_SEAHAWKS -> R.drawable.seahawks_logo
            Prefs.LauncherTheme.WINDOWS_XP -> R.drawable.xp_lightning
            else -> R.drawable.bolt_search_lightning
        },
        236, 113, 263, 140,
    ).apply {
        val tint = when (candidate) {
            Prefs.LauncherTheme.DEFAULT, Prefs.LauncherTheme.WALLPAPER -> wallpaper?.accent ?: Color.WHITE
            Prefs.LauncherTheme.E_INK, Prefs.LauncherTheme.PAPER_NOTEBOOK -> text
            Prefs.LauncherTheme.WII_U -> color(R.attr.boltIconTileGlyphColor)
            else -> null
        }
        if (tint != null) this?.setTint(tint)
    }
    private val normalLabel = previewLabel(context.getString(R.string.bolt_palette_preview_normal_result), 252f)
    private val selectedLabel = previewLabel(
        context.getString(R.string.bolt_theme_preview_selected_result), 252f, Typeface.DEFAULT_BOLD,
    )
    private val searchLabel = previewLabel(context.getString(R.string.bolt_palette_preview_search), 168f)

    override fun getIntrinsicWidth() = (280 * density).roundToInt()
    override fun getIntrinsicHeight() = (152 * density).roundToInt()

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 280f, bounds.height() / 152f)
        canvas.clipPath(panelClip)
        if (orchard == null) {
            fill(canvas, 0f, 0f, 280f, 152f, panelRadius, 0xFF18212B.toInt())
            fill(canvas, 0f, 0f, 280f, 152f, panelRadius, panel, panelFill)
        } else {
            orchard.draw(canvas)
            fill(canvas, 4f, 5f, 276f, 147f, panelRadius, panel, panelFill)
        }
        if (paperNotebook) {
            paint.color = paperRule
            for (y in 16..96 step 10) canvas.drawLine(8f, y.toFloat(), 272f, y.toFloat(), paint)
        }
        fill(canvas, 8f, 9f, 272f, 44f, 9f, row, rowFill)
        fill(canvas, 8f, 50f, 272f, 89f, selectedRadius, selected, selectionFill)
        outline(canvas, 8.5f, 50.5f, 271.5f, 88.5f, selectedRadius, selectedOutline)
        label(canvas, normalLabel, 20f, 32f, text)
        label(canvas, selectedLabel, 20f, 75f, selectedText, Typeface.DEFAULT_BOLD)

        paint.color = outline
        canvas.drawLine(12f, 98f, 268f, 98f, paint)
        fill(canvas, 8f, 105f, 272f, 144f, 9f, dock, dockFill)
        val cursorLeft = 24f + label(canvas, searchLabel, 20f, 131f, text)
        fill(canvas, cursorLeft, 117f, cursorLeft + 1.5f, 134f, 0f, cursor)
        web?.draw(canvas)
        thunder?.draw(canvas)
        outline(canvas, 0.5f, 0.5f, 279.5f, 151.5f, panelRadius, outline)
        canvas.restoreToCount(save)
    }

    private fun label(
        canvas: Canvas, label: String, x: Float, baseline: Float, color: Int, typeface: Typeface = Typeface.DEFAULT,
    ): Float {
        paint.color = color
        paint.typeface = typeface
        paint.textSize = 15f
        canvas.drawText(label, x, baseline, paint)
        return paint.measureText(label)
    }

    private fun previewLabel(label: String, maxWidth: Float, typeface: Typeface = Typeface.DEFAULT): String {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 15f
        }
        return TextUtils.ellipsize(label, textPaint, maxWidth, TextUtils.TruncateAt.END).toString()
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

    private fun outline(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        fill(canvas, left, top, right, bottom, radius, color)
        paint.style = Paint.Style.FILL
    }

    private fun color(attr: Int, fallback: Int = Color.TRANSPARENT): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true) && value.data != 0) value.data else fallback
    }

    private fun dimension(attr: Int, fallback: Float): Float {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) {
            value.getDimension(theme.resources.displayMetrics) / density
        } else fallback
    }

    private fun gradient(start: Int, end: Int, from: Float, to: Float, horizontal: Boolean = false): Shader? =
        when {
            end == start -> null
            horizontal -> LinearGradient(from, 0f, to, 0f, start, end, Shader.TileMode.CLAMP)
            else -> LinearGradient(0f, from, 0f, to, start, end, Shader.TileMode.CLAMP)
        }

    private fun icon(context: Context, resource: Int, left: Int, top: Int, right: Int, bottom: Int) =
        ContextCompat.getDrawable(context, resource)?.mutate()?.apply { setBounds(left, top, right, bottom) }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
