package app.olauncher.helper

import java.net.URLEncoder

/** Search URLs only, never home pages or a guessed custom URI scheme. */
object InAppSearchLinks {
    private val prefixes = mapOf(
        "com.google.android.youtube" to "https://www.youtube.com/results?search_query=",
        "com.google.android.apps.maps" to "https://www.google.com/maps/search/?api=1&query=",
        "com.android.vending" to "https://play.google.com/store/search?q=",
        "com.reddit.frontpage" to "https://www.reddit.com/search/?q=",
        "com.pinterest" to "https://www.pinterest.com/search/pins/?q="
    )

    fun url(packageName: String, query: String): String? = prefixes[packageName]?.let {
        it + URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    }
}
