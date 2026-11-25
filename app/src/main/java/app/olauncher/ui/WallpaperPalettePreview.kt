package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import app.olauncher.R

/** Shows the regular search dock and normal and selected results with the proposed colors. */
internal class WallpaperPalettePreview(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons = listOf(R.drawable.contact_call, R.drawable.contact_message, R.drawable.dock_web)
        .map { AppCompatResources.getDrawable(context, it)?.mutate() }
    private val normalResult = context.getString(R.string.bolt_palette_preview_normal_result)
    private val selectedResult = context.getString(R.string.bolt_palette_preview_selected_result)
    private val search = context.getString(R.string.bolt_palette_preview_search)
    private var colors: WallpaperThemeColors? = null
    private var gradient: LinearGradient? = null
    private var resultText = Color.WHITE
    private var selectedText = Color.BLACK

    fun bind(colors: WallpaperThemeColors, resultText: Int, selectedText: Int) {
        this.colors = colors
        this.resultText = resultText
        this.selectedText = selectedText
        gradient = LinearGradient(0f, 0f, 280f, 0f, colors.panel, colors.panelEnd ?: colors.panel, Shader.TileMode.CLAMP)
        icons.forEach { it?.setTint(colors.accent) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val palette = colors ?: return
        val scale = minOf(width / 280f, height / 152f)
        val save = canvas.save()
        canvas.translate((width - 280f * scale) / 2f, (height - 152f * scale) / 2f)
        canvas.scale(scale, scale)

        paint.shader = gradient
        canvas.drawRoundRect(0f, 0f, 280f, 152f, 13f, 13f, paint)
        paint.shader = null
        paint.color = palette.outline
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(0.5f, 0.5f, 279.5f, 151.5f, 13f, 13f, paint)
        paint.style = Paint.Style.FILL

        fill(canvas, 8f, 9f, 272f, 44f, palette.row)
        fill(canvas, 8f, 50f, 272f, 89f, palette.accent)
        label(canvas, normalResult, 20f, 32f, resultText)
        label(canvas, selectedResult, 20f, 75f, selectedText)

        paint.color = palette.outline
        canvas.drawLine(12f, 98f, 268f, 98f, paint)
        fill(canvas, 8f, 105f, 272f, 144f, palette.dock)
        label(canvas, search, 20f, 130f, Color.WHITE)
        paint.color = palette.accent
        val cursorLeft = 24f + paint.measureText(search)
        canvas.drawRect(cursorLeft, 116f, cursorLeft + 1.5f, 132f, paint)
        icons.forEachIndexed { index, icon ->
            val left = 181 + index * 27
            icon?.setBounds(left, 115, left + 20, 135)
            icon?.draw(canvas)
        }
        canvas.restoreToCount(save)
    }

    private fun fill(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        paint.color = color
        canvas.drawRoundRect(left, top, right, bottom, 9f, 9f, paint)
    }

    private fun label(canvas: Canvas, text: String, x: Float, baseline: Float, color: Int) {
        paint.color = color
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 15f
        canvas.drawText(text, x, baseline, paint)
    }
}
