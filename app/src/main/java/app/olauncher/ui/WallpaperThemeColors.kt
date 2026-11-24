package app.olauncher.ui

import android.content.Context
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

    private fun tone(lightness: Float, saturation: Float = hsl[1]): Int =
        ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, lightness))

    val panel = tone(0.12f, hsl[1].coerceAtMost(0.55f))
    val dock = tone(0.17f, hsl[1].coerceAtMost(0.55f))
    val row = tone(0.20f, hsl[1].coerceAtMost(0.50f))
    val outline = tone(0.42f, hsl[1].coerceAtMost(0.55f))
    val accent = FloatArray(3).let {
        ColorUtils.colorToHSL(sample.accent, it)
        it[2] = it[2].coerceIn(0.75f, 0.88f)
        ColorUtils.HSLToColor(it)
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
