package app.olauncher.helper

private val nameWords = Regex("[\\p{L}\\p{M}]+")
private val termSeparator = Regex("\\s+")

internal class ContactNameMatcher(private val query: String) {
    private val terms = query.split(termSeparator).filter { it.isNotEmpty() }
    private val normalizedTerms = terms.map { it.normalizeForSearch() }
    private val initialsQuery = query.normalizeForSearch()
    private val allowInitials = initialsQuery.length >= 2 && terms.size == 1 && initialsQuery.all(Char::isLetter)
    private val typoMatcher = SearchTypoMatcher(query)
    private val allowTypos = query.length >= 4 && query.none(Char::isDigit)

    fun rank(name: String, numbers: List<String>): Int? {
        // Match individual terms so a surname-first query finds the same contact.
        if (terms.all { term ->
                name.contains(term, ignoreCase = true) || numbers.any { it.contains(term) }
            }) return appSearchRank(name, query)
        val normalizedName = name.normalizeForSearch()
        if (normalizedTerms.all { it.isNotEmpty() && normalizedName.contains(it, ignoreCase = true) }) {
            return appSearchRank(normalizedName, initialsQuery)
        }
        if (allowInitials) {
            val initials = nameWords.findAll(name).map { it.value.first() }.joinToString("")
            if (initials.normalizeForSearch().equals(initialsQuery, ignoreCase = true)) return 4
        }
        return TYPO_MATCH.takeIf { allowTypos && typoMatcher.matches(normalizedName) }
    }

    companion object {
        const val TYPO_MATCH = 5
    }
}
