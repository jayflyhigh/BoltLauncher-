package app.olauncher.helper

/** Search URLs only, never home pages or a guessed custom URI scheme. */
object InAppSearchLinks {
    private val prefixes = mapOf(
        "com.google.android.youtube" to "https://www.youtube.com/results?search_query=",
        "com.google.android.apps.maps" to "https://www.google.com/maps/search/?api=1&query=",
        "com.android.vending" to "https://play.google.com/store/search?q=",
        "com.reddit.frontpage" to "https://www.reddit.com/search/?q=",
        "com.pinterest" to "https://www.pinterest.com/search/pins/?q="
    )

    fun template(packageName: String): String? = prefixes[packageName]?.plus("{query}")

    fun url(packageName: String, query: String): String? =
        template(packageName)?.let { SearchCommandUrl.build(it, query) }

    fun appTemplate(packageName: String): String? = when (packageName) {
        "com.android.vending" -> "market://search?q={query}&c=apps"
        "com.google.android.apps.maps" -> "geo:0,0?q={query}"
        else -> null
    }
}
