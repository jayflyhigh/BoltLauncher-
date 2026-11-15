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

/** Built-in and user-configured search commands, restricted to the chosen app. */
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
        seedDefaults(installed.keys)
        val browsers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0
        ).map { it.activityInfo.packageName }.toSet() + "com.sec.android.app.sbrowser"
        targets = configurations().mapNotNull { config ->
            val app = installed[config.optString("package")] ?: return@mapNotNull null
            if (app.appPackage in browsers) return@mapNotNull null
            val url = config.optString("url")
            val code = config.optString("alias")
            if (!SearchCommandUrl.valid(url) || code.isBlank()) return@mapNotNull null
            app.appPackage to Target(app, url, config.optInt("color", Color.DKGRAY), code)
        }.toMap()
    }

    /** Seed each installed app once; edits and removals remain the user's choice. */
    private fun seedDefaults(installedPackages: Set<String>) {
        val seeded = prefs.getStringSet("seeded_defaults", emptySet()).orEmpty().toMutableSet()
        val configs = configurations().toMutableList()
        var changed = false
        for (preset in defaults) {
            if (preset.packageName !in installedPackages || preset.packageName in seeded) continue
            if (configs.none { it.optString("package") == preset.packageName } &&
                configs.none { it.optString("alias").equals(preset.code, true) }) {
                configs.add(JSONObject().put("package", preset.packageName)
                    .put("url", preset.url).put("color", Color.parseColor(preset.color))
                    .put("alias", preset.code))
            }
            seeded.add(preset.packageName)
            changed = true
        }
        if (changed) prefs.edit()
            .putString("custom_commands", JSONArray(configs).toString())
            .putStringSet("seeded_defaults", seeded).apply()
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

    fun launchBrowser(query: String): Boolean {
        if (query.isBlank()) return true
        return try {
            val browser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
                .resolveActivity(context.packageManager)?.packageName
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse(SearchCommandUrl.build("https://www.google.com/search?q={query}", query)))
            if (browser != null && browser != "android") intent.setPackage(browser)
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: android.content.ActivityNotFoundException) { false }
          catch (_: SecurityException) { false }
    }

    fun launch(target: Target, query: String): Boolean = try {
        if (target.app.appPackage == "com.google.android.apps.bard" &&
            target.template == "https://gemini.google.com/app") {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, query).setPackage(target.app.appPackage)
            if (share.resolveActivity(context.packageManager) != null) {
                context.startActivity(share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } else {
                val launch = context.packageManager.getLaunchIntentForPackage(target.app.appPackage)
                if (launch == null) false else {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Gemini prompt", query))
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    android.widget.Toast.makeText(context, "Prompt copied. Paste it into Gemini.", android.widget.Toast.LENGTH_LONG).show()
                    true
                }
            }
        } else {
            val intent = resolve(target.app.appPackage, SearchCommandUrl.build(target.template, query))
            if (intent == null) false else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }
        }
    } catch (_: android.content.ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }

    private data class Preset(val packageName: String, val url: String, val color: String, val code: String)

    companion object {
        private val defaults = listOf(
            Preset("com.android.vending", "https://play.google.com/store/search?q={query}", "#34A853", "PS"),
            Preset("com.google.android.apps.maps", "https://www.google.com/maps/search/?api=1&query={query}", "#4285F4", "MAP"),
            Preset("com.google.android.apps.bard", "https://gemini.google.com/app", "#8E75FF", "GEM"),
            Preset("com.reddit.frontpage", "https://www.reddit.com/search/?q={query}", "#FF4500", "RD"),
            Preset("com.pinterest", "https://www.pinterest.com/search/pins/?q={query}", "#E60023", "PIN"),
            Preset("com.zhiliaoapp.musically", "https://www.tiktok.com/search?q={query}", "#000000", "Tik")
        )

        fun background(color: Int, density: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18f * density
        }
        fun foreground(color: Int): Int = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > .45) Color.BLACK else Color.WHITE
    }
}
