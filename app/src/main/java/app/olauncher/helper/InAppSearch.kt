package app.olauncher.helper

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import app.olauncher.data.AppModel
import java.util.Locale

/** Only advertises exported search activities or package-resolved search links. */
class InAppSearch(private val context: Context) {
    data class Target(val app: AppModel, val intent: Intent, val url: String?, val color: Int)
    private val prefs = context.getSharedPreferences("in_app_search", Context.MODE_PRIVATE)
    @Volatile var targets: Map<String, Target> = emptyMap()
        private set

    fun load(apps: List<AppModel>) {
        val links = mapOf(
            "com.google.android.youtube" to "https://www.youtube.com/results?search_query=",
            "com.google.android.apps.maps" to "https://www.google.com/maps/search/?api=1&query=",
            "com.android.vending" to "market://search?q=",
            "com.reddit.frontpage" to "https://www.reddit.com/search/?q=",
            "com.spotify.music" to "spotify:search:",
            "com.pinterest" to "https://www.pinterest.com/search/pins/?q="
        )
        targets = apps.filterIsInstance<AppModel.App>()
            .filter { it.user == android.os.Process.myUserHandle() }
            .sortedBy { it.appPackage }.mapNotNull { app ->
                try {
                    val url = links[app.appPackage]
                    val search = Intent(Intent.ACTION_SEARCH).setPackage(app.appPackage)
                    val activity = context.packageManager.queryIntentActivities(search, 0).firstOrNull {
                        it.activityInfo.exported && it.activityInfo.enabled &&
                            (it.activityInfo.permission == null || context.checkSelfPermission(it.activityInfo.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    }
                    val intent = if (activity != null) search.setClassName(app.appPackage, activity.activityInfo.name)
                        else if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url + "test")).setPackage(app.appPackage)
                        else return@mapNotNull null
                    if (activity == null && intent.resolveActivity(context.packageManager) == null) return@mapNotNull null
                    app.appPackage to Target(app, intent, if (activity == null) url else null, iconColor(app.appPackage))
                } catch (_: Exception) { null }
            }.toMap()
        val used = targets.keys.mapNotNull { prefs.getString(it, null) }.toMutableSet()
        val editor = prefs.edit()
        targets.forEach { (pkg, target) ->
            if (!prefs.contains(pkg)) {
                val base = target.app.appLabel.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }.take(2).ifBlank { "app" }
                var alias = base
                var suffix = 2
                while (alias in used) alias = base + suffix++
                used.add(alias)
                editor.putString(pkg, alias)
            }
        }
        editor.apply()
    }

    fun alias(target: Target): String = prefs.getString(target.app.appPackage, "") ?: ""
    fun find(alias: String): Target? = targets.values.firstOrNull { alias(it).equals(alias, true) }
    fun saveAlias(target: Target, value: String): Boolean {
        if (!AppSearchAlias.valid(value, targets.values.filter {
                it.app.appPackage != target.app.appPackage
            }.map { alias(it) })) return false
        prefs.edit().putString(target.app.appPackage, value).apply()
        return true
    }
    fun launch(target: Target, query: String) {
        val intent = Intent(target.intent)
        if (target.url != null) intent.data = Uri.parse(target.url + Uri.encode(query))
        else intent.putExtra(SearchManager.QUERY, query)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun iconColor(pkg: String): Int {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val icon = context.packageManager.getApplicationIcon(pkg)
        icon.setBounds(0, 0, 24, 24)
        icon.draw(Canvas(bitmap))
        val counts = mutableMapOf<Int, Int>()
        for (x in 0 until 24) for (y in 0 until 24) {
            val c = bitmap.getPixel(x, y)
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            if (Color.alpha(c) > 128 && hsv[1] > .25f && hsv[2] > .2f) {
                val key = Color.rgb(Color.red(c) / 32 * 32, Color.green(c) / 32 * 32, Color.blue(c) / 32 * 32)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        bitmap.recycle()
        return counts.maxByOrNull { it.value }?.key ?: Color.rgb(80, 85, 95)
    }
    companion object {
        fun background(color: Int, density: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18f * density
        }
        fun foreground(color: Int): Int = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > .45) Color.BLACK else Color.WHITE
    }
}
