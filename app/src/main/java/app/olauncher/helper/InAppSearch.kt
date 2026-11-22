package app.olauncher.helper

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import app.olauncher.data.AppModel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

class InAppSearch(private val context: Context) {
    data class Target(
        val packageName: String,
        val appLabel: String,
        val template: String,
        val color: Int,
        val code: String,
        val builtIn: Boolean,
        val hidden: Boolean,
    )

    private val prefs = context.getSharedPreferences("in_app_search", Context.MODE_PRIVATE)
    private val installedLabels = mutableMapOf<String, String>()
    private var configs = readConfigurations().toMutableList()
    private var cachedTargets: List<Target>? = null

    init {
        val seeded = prefs.getStringSet("seeded_defaults", emptySet()).orEmpty()
        val usedCodes = configs.map { it.optString("alias") }.toMutableList()
        var changed = false
        for (preset in defaults) {
            if (configs.any { it.optString("package") == preset.packageName }) continue
            val code = SearchCommandUrl.availableAlias(preset.code, usedCodes)
            // Older versions also marked a default as seeded when its code collided.
            val skipped = usedCodes.any { it.equals(preset.previousCode, ignoreCase = true) }
            usedCodes.add(code)
            configs.add(configuration(preset.packageName, preset.label, preset.template, preset.color,
                code, hidden = preset.packageName in seeded && !skipped))
            changed = true
        }
        if (changed) persist()
    }

    private fun readConfigurations(): List<JSONObject> = try {
        val array = JSONArray(prefs.getString("custom_commands", "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    } catch (_: JSONException) {
        emptyList()
    }

    fun reload() {
        configs = readConfigurations().toMutableList()
        cachedTargets = null
    }

    fun updateApps(apps: List<AppModel>) {
        cachedTargets = null
        installedLabels.clear()
        apps.filterIsInstance<AppModel.App>()
            .filter { it.user == android.os.Process.myUserHandle() }
            .associateTo(installedLabels) { it.appPackage to it.appLabel }
    }

    val targets: List<Target>
        get() = cachedTargets ?: buildTargets().also { cachedTargets = it }

    private fun buildTargets(): List<Target> = configs.mapNotNull { config ->
        val pkg = config.optString("package")
        val preset = defaults.firstOrNull { it.packageName == pkg }
        val label = preset?.label ?: installedLabels[pkg] ?: config.optString("label").ifBlank { null }
            ?: return@mapNotNull null
        val template = config.optString("url")
        val code = config.optString("alias")
        if (!SearchCommandUrl.valid(template) || code.isBlank()) return@mapNotNull null
        Target(pkg, label, template, config.optInt("color", Color.DKGRAY), code,
            preset != null, config.optBoolean("hidden"))
    }

    fun confirmed(text: String): Pair<Target, String>? {
        if (' ' !in text) return null
        val enabled = targets.filterNot { it.hidden }
        val (code, query) = SearchCommandUrl.confirmed(text, enabled.map { it.code }) ?: return null
        return enabled.first { it.code == code } to query
    }

    fun save(packageName: String, label: String, template: String, color: Int, code: String) {
        reload()
        val previous = configs.firstOrNull { it.optString("package") == packageName }
        val builtIn = defaults.any { it.packageName == packageName }
        require((builtIn || "{query}" in template) && SearchCommandUrl.valid(template)) {
            "Enter a valid search URL with {query}."
        }
        val otherCodes = configs.filter { it !== previous }.map { it.optString("alias") }
        require(SearchCommandUrl.validAlias(code, otherCodes)) {
            "Enter a unique alias of 1–16 characters with no spaces."
        }
        configs.remove(previous)
        configs.add(configuration(packageName, label, template.trim(), color, code, hidden = false))
        persist()
    }

    fun remove(target: Target) {
        reload()
        val config = configs.firstOrNull { it.optString("package") == target.packageName } ?: return
        if (target.builtIn) config.put("hidden", true) else configs.remove(config)
        persist()
    }

    private fun persist() {
        cachedTargets = null
        prefs.edit().putString("custom_commands", JSONArray(configs).toString()).apply()
    }

    fun launch(target: Target, query: String): Boolean {
        val url = SearchCommandUrl.build(target.template, query)
        val nativeTemplate = InAppSearchLinks.appTemplate(target.packageName)
            ?.takeIf { target.template == InAppSearchLinks.template(target.packageName) }
        if (nativeTemplate != null && open(SearchCommandUrl.build(nativeTemplate, query), target.packageName)) {
            return true
        }
        val webLink = Uri.parse(url).scheme?.lowercase(Locale.ROOT) in setOf("http", "https")
        return open(url, target.packageName) || (webLink && open(url))
    }

    private fun open(url: String, packageName: String? = null): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private data class Preset(
        val packageName: String,
        val label: String,
        val color: Int,
        val code: String,
        val previousCode: String = code,
    ) {
        val template = requireNotNull(InAppSearchLinks.template(packageName))
    }

    companion object {
        private val defaults = listOf(
            Preset("com.android.vending", "Google Play Store", 0xFF34A853.toInt(), "play", previousCode = "PS"),
            Preset("com.google.android.apps.maps", "Google Maps", 0xFF4285F4.toInt(), "maps", previousCode = "MAP"),
            Preset("com.pinterest", "Pinterest", 0xFFE60023.toInt(), "pin"),
            Preset("com.reddit.frontpage", "Reddit", 0xFFFF4500.toInt(), "rd"),
        )

        private fun configuration(pkg: String, label: String, url: String, color: Int, alias: String, hidden: Boolean) =
            JSONObject().put("package", pkg).put("label", label).put("url", url)
                .put("color", color).put("alias", alias).put("hidden", hidden)

        fun background(color: Int, density: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18f * density
        }

        fun foreground(color: Int): Int =
            if (androidx.core.graphics.ColorUtils.calculateLuminance(color) >= 0.179) Color.BLACK else Color.WHITE
    }
}
