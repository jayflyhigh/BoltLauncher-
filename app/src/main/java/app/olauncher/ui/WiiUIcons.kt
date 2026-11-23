package app.olauncher.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import kotlin.math.roundToInt

internal fun AppCompatImageButton.setWiiUIcon(@DrawableRes icon: Int, compact: Boolean = false) {
    val tile = context.wiiUIcon(icon, if (compact) 24 else 32) ?: return
    imageTintList = null
    if (!compact) {
        val padding = (4f * resources.displayMetrics.density).roundToInt()
        setPadding(padding, padding, padding, padding)
    }
    setImageDrawable(tile)
}

internal fun AppCompatButton.setWiiUIcon(@DrawableRes icon: Int) {
    val tile = context.wiiUIcon(icon) ?: return
    compoundDrawableTintList = null
    supportCompoundDrawablesTintList = null
    setCompoundDrawablesRelativeWithIntrinsicBounds(tile, null, null, null)
}

private fun Context.wiiUIcon(@DrawableRes icon: Int, sizeDp: Int = 32): Drawable? {
    val glyph = AppCompatResources.getDrawable(this, icon)?.mutate() ?: return null
    val density = resources.displayMetrics.density
    val size = (sizeDp * density).roundToInt()
    val inset = (sizeDp / 8f * density).roundToInt()
    val tile = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF147FA7.toInt(), 0xFF004965.toInt()),
    ).apply {
        cornerRadius = 9f * density
        setSize(size, size)
        setStroke(density.roundToInt().coerceAtLeast(1), 0xFF003E58.toInt())
    }
    glyph.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
    return LayerDrawable(arrayOf(tile, glyph)).apply {
        setLayerInset(1, inset, inset, inset, inset)
        setLayerSize(1, size - 2 * inset, size - 2 * inset)
    }
}
