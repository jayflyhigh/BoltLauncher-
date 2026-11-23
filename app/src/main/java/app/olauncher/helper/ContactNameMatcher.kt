package app.olauncher.helper

private val nameWords = Regex("[\\p{L}\\p{M}]+")
private val termSeparator = Regex("\\s+")

internal class ContactNameMatcher(private val query: String) {
    private val terms = query.split(termSeparator).filter { it.isNotEmpty() }
    private val normalizedTerms = terms.map { it.normalizeForSearch() }
    private val initialsQuery = query.normalizeForSearch()
    private val allowInitials = initialsQuery.length >= 2 && terms.size == 1 && initialsQuery.all(Char::isLetter)
    private val phoneQuery = query.takeIf { text ->
        text.any(Char::isDigit) && text.all { it.isDigit() || it.isWhitespace() || it in "+-()." }
    }?.filter(Char::isDigit)
    private val typoMatcher = SearchTypoMatcher(query)
    private val allowTypos = query.length >= 4 && query.none(Char::isDigit)

    class SearchName(val raw: String) {
        val normalized = raw.normalizeForSearch()
        val initials = nameWords.findAll(raw).map { it.value.first() }.joinToString("").normalizeForSearch()
    }

    fun rank(name: SearchName, numbers: List<String>): Int? {
        // Match individual terms so a surname-first query finds the same contact.
        if (terms.all { term ->
                name.raw.contains(term, ignoreCase = true) || numbers.any { it.contains(term) }
            }) return appSearchRank(name.raw, query)
        if (phoneQuery != null && numbers.any { phoneQuery in it.filter(Char::isDigit) }) {
            return appSearchRank(name.raw, query)
        }
        if (normalizedTerms.all { it.isNotEmpty() && name.normalized.contains(it, ignoreCase = true) }) {
            return appSearchRank(name.normalized, initialsQuery)
        }
        if (allowInitials && name.initials.equals(initialsQuery, ignoreCase = true)) return 4
        return TYPO_MATCH.takeIf { allowTypos && typoMatcher.matches(name.normalized) }
    }

    companion object {
        const val TYPO_MATCH = 5
    }
}
