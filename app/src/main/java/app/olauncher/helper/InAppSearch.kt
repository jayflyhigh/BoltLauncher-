package app.olauncher.helper

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import app.olauncher.data.AppModel
import java.util.Locale

/** Requires a search contract or a supported search URL handled by the installed app. */
class InAppSearch(private val context: Context) {
    data class Route(val intent: Intent, val link: Boolean)
    data class Target(val app: AppModel, val routes: List<Route>, val color: Int)
    private val prefs = context.getSharedPreferences("in_app_search", Context.MODE_PRIVATE)
    @Volatile var targets: Map<String, Target> = emptyMap()
        private set

    fun load(apps: List<AppModel>) {
        val manager = context.getSystemService(Context.SEARCH_SERVICE) as SearchManager
        targets = apps.filterIsInstance<AppModel.App>()
            .filter { it.user == android.os.Process.myUserHandle() }
            .sortedBy { it.appPackage }.mapNotNull { app ->
                try {
                    // A failed route is retried after an app update, not on every list refresh.
                    if (prefs.getLong("failed:${app.appPackage}", -1) == appVersion(app.appPackage))
                        return@mapNotNull null
                    val routes = mutableListOf<Route>()
                    // Prefer a query-bearing app link over a generic ACTION_SEARCH handler.
                    InAppSearchLinks.url(app.appPackage, "test")?.let { url ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.appPackage)
                        val activity = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                            .firstOrNull { usable(it.activityInfo, app.appPackage) }?.activityInfo
                        if (activity != null) {
                            intent.component = ComponentName(activity.packageName, activity.name)
                            routes.add(Route(intent, true))
                        }
                    }
                    val search = Intent(Intent.ACTION_SEARCH).setPackage(app.appPackage)
                    context.packageManager.queryIntentActivities(search, 0).forEach { result ->
                        val activity = result.activityInfo
                        val component = ComponentName(activity.packageName, activity.name)
                        // Resolving ACTION_SEARCH alone does not establish that QUERY is supported.
                        if (usable(activity, app.appPackage) &&
                            manager.getSearchableInfo(component)?.searchActivity == component) {
                            routes.add(Route(Intent(search).setComponent(component), false))
                        }
                    }
                    if (routes.isEmpty()) return@mapNotNull null
                    app.appPackage to Target(app, routes, iconColor(app.appPackage))
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
    private fun appVersion(pkg: String): Long = context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime

    private fun usable(info: android.content.pm.ActivityInfo, pkg: String): Boolean =
        info.packageName == pkg && info.exported && info.enabled && info.applicationInfo.enabled &&
            (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)

    /** Try each validated route. Never replace a failed search with an ordinary app launch. */
    fun launch(target: Target, query: String): Boolean {
        for (route in target.routes) {
            try {
                val intent = Intent(route.intent)
                if (route.link) intent.data = Uri.parse(InAppSearchLinks.url(target.app.appPackage, query) ?: continue)
                else intent.putExtra(SearchManager.QUERY, query)
                val component = intent.component ?: continue
                if (!usable(context.packageManager.getActivityInfo(component, 0), target.app.appPackage)) continue
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // The app may have been disabled, uninstalled or updated after discovery.
            } catch (_: SecurityException) {
                // An inaccessible search activity must not leave a dead code behind.
            } catch (_: PackageManager.NameNotFoundException) {
                // Continue to an alternate route if an activity changed.
            }
        }
        try {
            prefs.edit().putLong("failed:${target.app.appPackage}", appVersion(target.app.appPackage)).apply()
        } catch (_: PackageManager.NameNotFoundException) { }
        targets = targets - target.app.appPackage
        return false
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
