package app.olauncher.helper

import java.text.Normalizer

private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
private val separatorsRegex = Regex("[-_+,.`'\\s\\p{Z}]")

/**
 * True if [appLabel] matches [query] for the app-drawer search: either a plain
 * case-insensitive substring match, or a match once both sides are normalised
 * (diacritics stripped, separator characters removed) — e.g. "cafe" matches
 * "Café", and "googlemaps" matches "Google Maps".
 */
fun appLabelMatches(appLabel: String, query: CharSequence): Boolean =
    AppLabelMatcher(query).matches(appLabel, appLabel.normalizeForSearch())

/** Prepare the query once per search and reuse normalized labels across keystrokes. */
class AppLabelMatcher(query: CharSequence) {
    private val trimmed = query.trim().toString()
    private val normalized = query.normalizeForSearch()

    fun matches(label: String, normalizedLabel: String): Boolean =
        label.contains(trimmed, true) ||
            (normalized.isNotEmpty() && normalizedLabel.contains(normalized, true))
}

internal fun CharSequence.normalizeForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(diacriticsRegex, "")
        .replace(separatorsRegex, "")

/**
 * Thunder mode lets a user start anywhere in an app name and continue from
 * the beginning when they reach its end (e.g. "bookface" -> "Facebook").
 * Preserve normal matching and prevent repeated/wrapped text longer than the
 * actual label from matching. Normal search deliberately does not use this.
 */
fun thunderAppLabelMatches(label: String, normalizedLabel: String, query: CharSequence): Boolean {
    val matcher = AppLabelMatcher(query)
    if (matcher.matches(label, normalizedLabel)) return true
    val needle = query.normalizeForSearch()
    if (needle.isEmpty() || needle.length > normalizedLabel.length) return false
    return (normalizedLabel + normalizedLabel.dropLast(1)).contains(needle, ignoreCase = true)
}
