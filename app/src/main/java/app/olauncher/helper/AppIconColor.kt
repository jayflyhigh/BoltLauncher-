package app.olauncher.helper

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.olauncher.data.AppModel

/** Small icon sample; prefer the main colored region over a neutral icon background. */
object AppIconColor {
    private val fallback = Color.rgb(255, 213, 79)

    fun primary(context: Context, app: AppModel.App?): Int {
        if (app == null) return fallback
        return try {
            val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val icon = launcher.getActivityList(app.appPackage, app.user).firstOrNull()
                ?.getIcon(context.resources.displayMetrics.densityDpi)
                ?: context.packageManager.getApplicationIcon(app.appPackage)
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            try {
                val bounds = android.graphics.Rect(icon.bounds)
                try {
                    icon.setBounds(0, 0, 48, 48)
                    icon.draw(Canvas(bitmap))
                } finally {
                    icon.bounds = bounds
                }
                val counts = IntArray(4096)
                val reds = IntArray(4096)
                val greens = IntArray(4096)
                val blues = IntArray(4096)
                val hsv = FloatArray(3)
                var opaque = 0
                for (y in 0 until 48) for (x in 0 until 48) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) < 192) continue
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val bin = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                    counts[bin]++
                    reds[bin] += r; greens[bin] += g; blues[bin] += b
                    opaque++
                }
                var dominant = -1
                var colored = -1
                for (bin in counts.indices) {
                    val count = counts[bin]
                    if (count == 0) continue
                    if (dominant < 0 || count > counts[dominant]) dominant = bin
                    Color.RGBToHSV(reds[bin] / count, greens[bin] / count, blues[bin] / count, hsv)
                    if (hsv[1] >= .25f && hsv[2] >= .2f &&
                        (colored < 0 || count > counts[colored])) colored = bin
                }
                val selected = if (colored >= 0 && counts[colored] >= opaque * .03f) colored else dominant
                if (selected < 0) fallback else Color.rgb(
                    reds[selected] / counts[selected],
                    greens[selected] / counts[selected],
                    blues[selected] / counts[selected],
                )
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            fallback
        }
    }
}
