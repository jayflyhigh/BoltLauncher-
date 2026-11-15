package app.olauncher.helper

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Autocomplete is requested only after a search destination has been selected. */
object SearchSuggestions {
    fun fetch(query: String): List<String> {
        if (query.isBlank()) return emptyList()
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
                .distinct().take(6)
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }
}
