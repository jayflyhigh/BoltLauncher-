package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import app.olauncher.data.AppModel
import org.json.JSONArray
import org.json.JSONObject

/** User-configured search commands, always restricted to the chosen app. */
class InAppSearch(private val context: Context) {
    data class Target(val app: AppModel.App, val template: String, val color: Int, val code: String)
    private val prefs = context.getSharedPreferences("in_app_search", Context.MODE_PRIVATE)
    var targets: Map<String, Target> = emptyMap()
        private set

    private fun configurations(): List<JSONObject> = try {
        val array = JSONArray(prefs.getString("custom_commands", "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    } catch (_: Exception) { emptyList() }

    fun load(apps: List<AppModel>) {
        val installed = apps.filterIsInstance<AppModel.App>()
            .filter { it.user == android.os.Process.myUserHandle() }.associateBy { it.appPackage }
        targets = configurations().mapNotNull { config ->
            val app = installed[config.optString("package")] ?: return@mapNotNull null
            val url = config.optString("url")
            val code = config.optString("alias")
            if (!SearchCommandUrl.valid(url) || code.isBlank()) return@mapNotNull null
            app.appPackage to Target(app, url, config.optInt("color", Color.DKGRAY), code)
        }.toMap()
    }

    fun alias(target: Target) = target.code
    fun find(code: String) = targets.values.firstOrNull { it.code.equals(code, true) }
    fun confirmed(text: String): Pair<Target, String>? {
        val result = SearchCommandUrl.confirmed(text, targets.values.map { it.code }) ?: return null
        return find(result.first)?.let { it to result.second }
    }

    fun save(app: AppModel.App, template: String, color: Int, code: String): Target {
        require(SearchCommandUrl.valid(template)) { "Enter a valid search URL" }
        val others = configurations().filter { it.optString("package") != app.appPackage }.map { it.optString("alias") }
        require(SearchCommandUrl.validAlias(code, others)) { "Enter a unique, non-empty alias" }
        require(resolve(app.appPackage, SearchCommandUrl.build(template, "test")) != null) {
            "This app cannot open that URL. Check its search URL or URL scheme."
        }
        val target = Target(app, template.trim(), color, code.trim())
        val configs = configurations().filter { it.optString("package") != app.appPackage }.toMutableList()
        configs.add(JSONObject().put("package", app.appPackage).put("url", target.template).put("color", color).put("alias", target.code))
        prefs.edit().putString("custom_commands", JSONArray(configs).toString()).apply()
        targets = targets + (app.appPackage to target)
        return target
    }

    fun remove(packageName: String) {
        val configs = configurations().filter { it.optString("package") != packageName }
        prefs.edit().putString("custom_commands", JSONArray(configs).toString()).apply()
        targets = targets - packageName
    }

    private fun resolve(pkg: String, url: String): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
        val activity = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull {
                val info = it.activityInfo
                info.packageName == pkg && info.exported && info.enabled && info.applicationInfo.enabled &&
                    (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
            }?.activityInfo ?: return null
        return intent.setComponent(ComponentName(activity.packageName, activity.name))
    }

    fun launch(target: Target, query: String): Boolean = try {
        val intent = resolve(target.app.appPackage, SearchCommandUrl.build(target.template, query))
        if (intent == null) false else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (_: android.content.ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }

    companion object {
        fun background(color: Int, density: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18f * density
        }
        fun foreground(color: Int): Int = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > .45) Color.BLACK else Color.WHITE
    }
}
