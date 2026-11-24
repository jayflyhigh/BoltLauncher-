package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.data.WallpaperPalette
import androidx.appcompat.app.AlertDialog

/** Keeps the sampled hue in the surfaces while giving text and highlights room for contrast. */
internal class WallpaperThemeColors(sample: WallpaperPalette.Sample) {
    private val hsl = FloatArray(3).also { ColorUtils.colorToHSL(sample.base, it) }

    private val brightness = ColorUtils.calculateLuminance(sample.base).toFloat()
    private val saturation = hsl[1].coerceAtMost(chromaLimit(sample.base, 0.64f))

    private fun tone(lightness: Float) = ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, lightness))

    private val rowLightness = run {
        var lightness = 0.165f + 0.22f * brightness
        // Fit the brightest surface under the contrast limit so the three tones stay distinct.
        while (ColorUtils.calculateLuminance(tone(lightness)) > 0.09) lightness -= 0.01f
        lightness
    }

    val panel = tone(rowLightness - 0.075f)
    val dock = tone(rowLightness - 0.03f)
    val row = tone(rowLightness)
    val outline = ColorUtils.blendARGB(panel, Color.WHITE, 0.34f)
    val accent = FloatArray(3).let {
        ColorUtils.colorToHSL(sample.accent, it)
        it[1] = it[1].coerceAtMost(chromaLimit(sample.accent, 1f))
        it[2] = it[2].coerceIn(0.66f, 0.88f)
        var color = ColorUtils.HSLToColor(it)
        while (ColorUtils.calculateLuminance(color) < 0.58) {
            it[2] += 0.01f
            color = ColorUtils.HSLToColor(it)
        }
        color
    }

    private fun chromaLimit(color: Int, limit: Float): Float {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
        return (chroma / 64f).coerceAtMost(limit)
    }

    fun rowBackground(context: Context) = StateListDrawable().apply {
        val radius = context.resources.getDimension(R.dimen.bolt_search_corner_radius)
        addState(intArrayOf(android.R.attr.state_selected), surface(accent, radius))
        addState(intArrayOf(), surface(row, radius))
    }

    fun surface(fill: Int, radius: Float, strokeWidth: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(fill)
        if (strokeWidth > 0) setStroke(strokeWidth, outline)
    }

    companion object {
        fun from(context: Context): WallpaperThemeColors? {
            val prefs = Prefs(context)
            if (prefs.launcherTheme != Prefs.LauncherTheme.DEFAULT) return null
            return prefs.wallpaperSample?.let(::WallpaperThemeColors)
        }
    }
}

internal fun AlertDialog.applyWallpaperBackground(): AlertDialog = apply {
    WallpaperThemeColors.from(context)?.let { colors ->
        val radius = 18f * context.resources.displayMetrics.density
        window?.setBackgroundDrawable(colors.surface(colors.panel, radius))
    }
}
