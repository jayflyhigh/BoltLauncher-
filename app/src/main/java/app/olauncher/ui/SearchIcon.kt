package app.olauncher.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt

/** Trim transparent margins at display resolution without reshaping or tinting the icon. */
internal fun normalizeSearchIcon(icon: Drawable, resources: Resources, slot: Int): Drawable {
    if (slot <= 0) return icon
    val width = icon.intrinsicWidth.coerceAtLeast(1)
    val height = icon.intrinsicHeight.coerceAtLeast(1)
    val scale = (slot * 2f) / maxOf(width, height)
    val bitmap = icon.toBitmap(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
        config = Bitmap.Config.ARGB_8888
    )
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var left = bitmap.width
    var top = bitmap.height
    var right = -1
    var bottom = -1
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            if (pixels[y * bitmap.width + x] ushr 24 == 0) continue
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
        }
    }
    if (right < left) return icon
    val trimmed = Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    return BitmapDrawable(resources, trimmed).apply { isFilterBitmap = true }
}
