package app.olauncher.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StyleRes
import androidx.core.graphics.ColorUtils
import app.olauncher.R

/** Selects a palette from the home wallpaper or a user-selected image crop. */
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

    data class GeneratedTheme(val sample: Sample, val supporting: Int) {
        fun inverted(): GeneratedTheme = GeneratedTheme(
            sample = Sample(invertRgb(sample.base), invertRgb(sample.accent)),
            supporting = invertRgb(supporting),
        )

        private fun invertRgb(color: Int): Int = color xor 0x00FFFFFF
    }

    companion object {
        private const val MIN_COLORFUL_CHROMA = 26
        private data class Swatch(val color: Int, val weight: Float)

        private fun chroma(color: Int): Int {
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            return maxOf(red, green, blue) - minOf(red, green, blue)
        }

        private fun select(swatches: List<Swatch>): Sample? {
            val ranked = swatches.sortedByDescending { it.weight }
            val dominant = ranked.firstOrNull() ?: return null
            val colorful = ranked.filter { chroma(it.color) >= MIN_COLORFUL_CHROMA && it.weight >= 0.08f }
            val base = if (chroma(dominant.color) < MIN_COLORFUL_CHROMA) {
                colorful.maxByOrNull { it.weight * chroma(it.color) } ?: dominant
            } else dominant
            val accent = colorful.firstOrNull {
                it != base && hueDistance(it.color, base.color) >= 28f
            } ?: base
            return Sample(base.color, accent.color)
        }

        private fun hueDistance(first: Int, second: Int): Float {
            val hsv = FloatArray(3)
            Color.colorToHSV(first, hsv)
            val hue = hsv[0]
            Color.colorToHSV(second, hsv)
            val difference = kotlin.math.abs(hue - hsv[0])
            return minOf(difference, 360f - difference)
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

        fun decodeImage(context: Context, uri: Uri): Bitmap? = readWallpaper {
            val resolver = context.contentResolver
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val largestSide = maxOf(options.outWidth, options.outHeight)
            if (largestSide <= 0) return@readWallpaper null
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (largestSide / options.inSampleSize > 2048) options.inSampleSize *= 2
            val orientation = try {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: java.io.IOException) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@readWallpaper null
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        setRotate(90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        setRotate(-90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (transform.isIdentity) bitmap else {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
                    .also { if (it !== bitmap) bitmap.recycle() }
            }
        }

        fun scanImage(bitmap: Bitmap, crop: Rect): Sample? {
            // Group nearby shades so gradients do not split prominent colors across tiny swatches.
            val neutralBuckets = 8
            val counts = IntArray(neutralBuckets + 12 * 4)
            val reds = IntArray(counts.size)
            val greens = IntArray(counts.size)
            val blues = IntArray(counts.size)
            val hsv = FloatArray(3)
            val width = crop.width().coerceAtMost(128)
            val height = crop.height().coerceAtMost(128)
            var total = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val color = bitmap.getPixel(
                        crop.left + ((2 * x + 1) * crop.width()) / (2 * width),
                        crop.top + ((2 * y + 1) * crop.height()) / (2 * height),
                    )
                    if (Color.alpha(color) < 128) continue
                    val red = Color.red(color)
                    val green = Color.green(color)
                    val blue = Color.blue(color)
                    val value = maxOf(red, green, blue)
                    val key = if (value - minOf(red, green, blue) < MIN_COLORFUL_CHROMA) {
                        value / 32
                    } else {
                        Color.colorToHSV(color, hsv)
                        val hue = ((hsv[0] + 15f) / 30f).toInt() % 12
                        neutralBuckets + hue * 4 + value / 64
                    }
                    counts[key]++
                    reds[key] += red
                    greens[key] += green
                    blues[key] += blue
                    total++
                }
            }
            return select(counts.indices.mapNotNull { key ->
                val count = counts[key]
                if (count == 0) null else Swatch(
                    Color.rgb(reds[key] / count, greens[key] / count, blues[key] / count),
                    count.toFloat() / total,
                )
            })
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        fun scan(context: Context): Sample? = readWallpaper {
            select(homeSwatches(context))
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        fun paletteChoices(context: Context, saved: Sample?): List<GeneratedTheme> {
            val wallpaper = readWallpaper { homeSwatches(context) }.orEmpty()
            val colors = (wallpaper.map { it.color } + listOfNotNull(saved?.base, saved?.accent)).distinct()
            val first = colors.firstOrNull() ?: return emptyList()
            val recommended = select(wallpaper) ?: saved ?: Sample(first, first)
            val candidates = buildList {
                val supporting = colors.firstOrNull { it != recommended.base && it != recommended.accent }
                add(GeneratedTheme(recommended, supporting ?: recommended.accent))
                for (base in colors) {
                    for (accent in colors) {
                        if (base == accent) continue
                        val third = colors.firstOrNull { it != base && it != accent }
                        add(GeneratedTheme(Sample(base, accent), third ?: accent))
                    }
                    val softer = ColorUtils.blendARGB(base, Color.WHITE, 0.40f)
                    val deeper = ColorUtils.blendARGB(base, Color.BLACK, 0.45f)
                    add(GeneratedTheme(Sample(softer, base), base))
                    add(GeneratedTheme(Sample(deeper, softer), deeper))
                }
            }
            return candidates.distinct()
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        private fun homeSwatches(context: Context): List<Swatch> {
            val colors = WallpaperManager.getInstance(context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return emptyList()
            // Android reports swatches in order of prominence without pixel counts.
            return listOfNotNull(
                Swatch(colors.primaryColor.toArgb(), 1f),
                colors.secondaryColor?.let { Swatch(it.toArgb(), 0.5f) },
                colors.tertiaryColor?.let { Swatch(it.toArgb(), 0.25f) },
            )
        }

        private inline fun <T> readWallpaper(read: () -> T?): T? = try {
            read()
        } catch (_: java.io.IOException) {
            null
        } catch (_: RuntimeException) {
            // Image providers and the wallpaper service can reject or lose access.
            null
        }
    }
}
