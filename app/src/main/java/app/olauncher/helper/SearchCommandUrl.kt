package app.olauncher.helper

import java.net.URI
import java.net.URLEncoder

object SearchCommandUrl {
    fun build(template: String, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = template.trim()
        return if (url.contains("{query}") || url.contains("%s"))
            url.replace("{query}", encoded).replace("%s", encoded)
        else url + encoded
    }

    fun valid(template: String): Boolean = try {
        val uri = URI(build(template, "test search"))
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        scheme != null && scheme !in setOf("file", "content", "javascript", "data", "intent", "tel", "sms", "mailto") &&
            (scheme !in setOf("http", "https") || !uri.host.isNullOrBlank())
    } catch (_: Exception) { false }

    fun validAlias(alias: String, otherAliases: Collection<String>): Boolean =
        alias.isNotBlank() && alias.none { it.isISOControl() } && otherAliases.none { it.equals(alias.trim(), true) }

    fun confirmed(text: String, aliases: Collection<String>): Pair<String, String>? =
        aliases.sortedByDescending { it.length }.firstOrNull { text.startsWith("$it.", true) }
            ?.let { it to text.substring(it.length + 1) }
}
