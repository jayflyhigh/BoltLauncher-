package app.olauncher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import kotlin.math.roundToInt

internal fun AppCompatImageButton.setXpIcon(@DrawableRes icon: Int, compact: Boolean = false) {
    val drawable = context.largeXpIcon(icon, if (compact) 24 else 32) ?: return
    val paddingDp = if (compact) 7 else 4
    val padding = (paddingDp * resources.displayMetrics.density).roundToInt()
    setPadding(padding, padding, padding, padding)
    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    setImageDrawable(drawable)
}

internal fun AppCompatButton.setXpIcon(@DrawableRes icon: Int) {
    val drawable = context.largeXpIcon(icon) ?: return
    setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
}

private fun Context.largeXpIcon(@DrawableRes icon: Int, sizeDp: Int = 32): Drawable? {
    val glyph = AppCompatResources.getDrawable(this, icon)?.mutate() ?: return null
    val size = (sizeDp * resources.displayMetrics.density).roundToInt()
    return LayerDrawable(arrayOf(glyph)).apply { setLayerSize(0, size, size) }
}
