package app.olauncher.helper

import java.util.Locale

object AppSearchAlias {
    fun normalize(value: String) = value.trim().lowercase(Locale.ROOT)
    fun valid(value: String, otherCodes: Collection<String>) =
        value.matches(Regex("[a-z0-9]+")) && otherCodes.none { it.equals(value, true) }

    // A code is confirmed only by a space; preserve the search text after it.
    fun confirmed(text: String): Pair<String, String>? {
        val separator = text.indexOf(' ')
        if (separator <= 0) return null
        return normalize(text.substring(0, separator)) to text.substring(separator + 1)
    }
}
