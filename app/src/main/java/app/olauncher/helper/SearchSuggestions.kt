package app.olauncher.helper

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Autocomplete for a selected search destination or app searches with no matches. */
object SearchSuggestions {
    private val cache = android.util.LruCache<String, List<String>>(64)
    fun cached(query: String): List<String>? = cache.get(query.trim())

    fun fetch(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        cached(query)?.let { return it }
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        var connection: HttpURLConnection? = null
        return try {
            connection = URI("https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded")
                .toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val values = JSONArray(response).getJSONArray(1)
            (0 until values.length()).map { values.getString(it) }
                .filter { it.isNotBlank() && !it.equals(query.trim(), true) }
                .distinct().take(6).also { cache.put(query.trim(), it) }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }
}
