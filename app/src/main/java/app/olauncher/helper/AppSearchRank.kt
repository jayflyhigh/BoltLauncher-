package app.olauncher.helper

private val appWordSeparator = Regex("\\s+")

/** Existing bottom-first app ranking, computed once per matching app. */
fun appSearchRank(label: String, query: String): Int = when {
    label.equals(query, true) -> 0
    label.startsWith(query, true) -> 1
    label.split(appWordSeparator).any { it.startsWith(query, true) } -> 2
    else -> 3
}

private val displayedNameWhitespace = Regex("[\\s\\p{Z}]+")

internal fun String.normalizeDisplayedName(): String =
    replace(displayedNameWhitespace, " ").trim()

/** The existing app-first priority also breaks ties between two exact names. */
internal fun contactWinsBestMatch(query: String, appName: String?, contactName: String?): Boolean {
    if (contactName == null) return false
    if (appName == null) return true
    val name = query.normalizeDisplayedName()
    return contactName.normalizeDisplayedName().equals(name, ignoreCase = true) &&
        !appName.normalizeDisplayedName().equals(name, ignoreCase = true)
}
