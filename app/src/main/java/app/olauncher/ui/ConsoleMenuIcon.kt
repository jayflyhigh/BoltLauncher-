package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import app.olauncher.R
import kotlin.math.roundToInt

internal fun AppCompatButton.setConsoleMenuIcon(@DrawableRes icon: Int) {
    val badge = context.consoleIcon(icon, accent = false) ?: return
    compoundDrawableTintList = null
    supportCompoundDrawablesTintList = null
    setCompoundDrawablesRelativeWithIntrinsicBounds(badge, null, null, null)
}

internal fun AppCompatImageButton.setConsoleIcon(@DrawableRes icon: Int, accent: Boolean = false) {
    val badge = context.consoleIcon(icon, accent) ?: return
    imageTintList = null
    setImageDrawable(badge)
}

private fun Context.consoleIcon(@DrawableRes icon: Int, accent: Boolean): Drawable? {
    val glyph = AppCompatResources.getDrawable(this, icon)?.mutate() ?: return null
    val density = resources.displayMetrics.density
    val diameter = (24f * density).roundToInt()
    val inset = (5f * density).roundToInt()
    val outline = if (accent) ContextCompat.getColor(this, R.color.bolt_console_cyan) else 0xFF626262.toInt()
    val background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(diameter, diameter)
        setColor(0xFF1D1D1D.toInt())
        setStroke(density.roundToInt().coerceAtLeast(1), outline)
    }
    glyph.colorFilter = PorterDuffColorFilter(if (accent) outline else Color.WHITE, PorterDuff.Mode.SRC_IN)
    return LayerDrawable(arrayOf(background, glyph)).apply {
        setLayerSize(0, diameter, diameter)
        setLayerSize(1, diameter - inset * 2, diameter - inset * 2)
        setLayerInset(1, inset, inset, inset, inset)
    }
}
