package app.olauncher.helper

private val appWordSeparator = Regex("\\s+")

/** Existing bottom-first app ranking, computed once per matching app. */
fun appSearchRank(label: String, query: String): Int = when {
    label.equals(query, true) -> 0
    label.startsWith(query, true) -> 1
    label.split(appWordSeparator).any { it.startsWith(query, true) } -> 2
    else -> 3
}
