package app.olauncher.data

import android.app.WallpaperManager
import android.content.Context
import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.annotation.StyleRes
import app.olauncher.R

/** Selects a legible palette from the system's home wallpaper colors. */
internal enum class WallpaperPalette(@StyleRes val overlay: Int) {
    ROSE(R.style.WallpaperPaletteRose),
    AMBER(R.style.WallpaperPaletteAmber),
    OLIVE(R.style.WallpaperPaletteOlive),
    GREEN(R.style.WallpaperPaletteGreen),
    TEAL(R.style.WallpaperPaletteTeal),
    BLUE(R.style.WallpaperPaletteBlue),
    VIOLET(R.style.WallpaperPaletteViolet),
    SLATE(R.style.WallpaperPaletteSlate);

    data class Sample(val base: Int, val accent: Int)

    companion object {
        private fun sample(colors: WallpaperColors): Sample {
            val base = colors.primaryColor.toArgb()
            val hsv = FloatArray(3)
            fun saturation(color: Int): Float {
                Color.colorToHSV(color, hsv)
                return hsv[1]
            }
            val accent = if (saturation(base) >= 0.25f) base else
                listOfNotNull(colors.secondaryColor?.toArgb(), colors.tertiaryColor?.toArgb())
                    .maxByOrNull(::saturation)?.takeIf { saturation(it) > saturation(base) } ?: base
            return Sample(base, accent)
        }

        fun forColor(color: Int): WallpaperPalette {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            if (hsv[1] < 0.15f) return SLATE
            return when (hsv[0]) {
                in 18f..<52f -> AMBER
                in 52f..<92f -> OLIVE
                in 92f..<158f -> GREEN
                in 158f..<198f -> TEAL
                in 198f..<252f -> BLUE
                in 252f..<320f -> VIOLET
                else -> ROSE
            }
        }

        fun scanImage(context: Context, uri: Uri): Sample? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
            val resolver = context.contentResolver
            return try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                val largestSide = maxOf(options.outWidth, options.outHeight)
                if (largestSide <= 0) return null
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while (largestSide / options.inSampleSize > 256) options.inSampleSize *= 2
                val bitmap = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return null
                try {
                    val config = context.resources.configuration
                    val aspect = config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)
                    val width = (bitmap.height * aspect).toInt().coerceIn(1, bitmap.width)
                    val height = (bitmap.width / aspect).toInt().coerceIn(1, bitmap.height)
                    val crop = Bitmap.createBitmap(bitmap, (bitmap.width - width) / 2,
                        (bitmap.height - height) / 2, width, height)
                    try {
                        sample(WallpaperColors.fromBitmap(crop))
                    } finally {
                        if (crop !== bitmap) crop.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
            } catch (_: java.io.IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

        fun scan(context: Context): Sample? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
            return try {
                WallpaperManager.getInstance(context)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.let(::sample)
            } catch (_: SecurityException) {
                null
            }
        }
    }
}
