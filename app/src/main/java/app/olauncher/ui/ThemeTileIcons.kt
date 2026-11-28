package app.olauncher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.drawable.DrawableCompat
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.getColorFromAttr
import kotlin.math.roundToInt

internal fun AppCompatImageButton.setThemeTileIcon(@DrawableRes icon: Int, compact: Boolean = false) {
    val glassShortcut = compact && Prefs(context).launcherTheme == Prefs.LauncherTheme.LIQUID_GLASS
    val smallTile = compact && !glassShortcut
    val tile = context.themeTileIcon(icon, if (smallTile) 24 else 32, glassShortcut) ?: return
    val density = resources.displayMetrics.density
    val padding = ((if (smallTile) 8f else 4f) * density).roundToInt()
    setPadding(padding, 0, padding, 0)
    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    setImageDrawable(tile)
}

internal fun AppCompatImageButton.setAppActionTileIcon(@DrawableRes icon: Int) {
    val tile = context.themeTileIcon(icon, sizeDp = 36, appAction = true) ?: return
    val padding = (2 * resources.displayMetrics.density).roundToInt()
    setPadding(padding, 0, padding, 0)
    setImageDrawable(tile)
}

internal fun AppCompatButton.setThemeTileIcon(@DrawableRes icon: Int) {
    val tile = context.themeTileIcon(icon) ?: return
    setCompoundDrawablesRelativeWithIntrinsicBounds(tile, null, null, null)
}

private fun Context.themeTileIcon(
    @DrawableRes icon: Int,
    sizeDp: Int = 32,
    glassShortcut: Boolean = false,
    appAction: Boolean = false,
): Drawable? {
    val prefs = Prefs(this)
    val density = resources.displayMetrics.density
    val size = (sizeDp * density).roundToInt()
    val glyph = AppCompatResources.getDrawable(this, icon)?.mutate() ?: return null
    val inset = ((if (appAction) 3f else sizeDp / 8f) * density).roundToInt()
    val glassFrame = if (prefs.launcherTheme == Prefs.LauncherTheme.LIQUID_GLASS) {
        val frame = if (glassShortcut) R.drawable.liquid_glass_shortcut_frame else R.drawable.liquid_glass_icon_frame
        AppCompatResources.getDrawable(this, frame)?.mutate() as? GradientDrawable
    } else null
    val tile = (glassFrame ?: GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            getColorFromAttr(R.attr.boltIconTileTopColor),
            getColorFromAttr(R.attr.boltIconTileBottomColor),
        ),
    ).apply {
        cornerRadius = 9f * density
        setStroke(density.roundToInt().coerceAtLeast(1), getColorFromAttr(R.attr.boltIconTileOutlineColor))
    }).apply { setSize(size, size) }
    // Glyph tint stays on the vector when AppCompat clears the image's color filter.
    DrawableCompat.setTint(glyph, getColorFromAttr(R.attr.boltIconTileGlyphColor))
    return LayerDrawable(arrayOf(tile, glyph)).apply {
        setLayerInset(1, inset, inset, inset, inset)
        setLayerSize(1, size - 2 * inset, size - 2 * inset)
        if (appAction) setLayerGravity(1, Gravity.CENTER)
    }
}
