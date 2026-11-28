package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class ThunderNextLettersTest {
    private fun hints(query: String, vararg apps: String) =
        thunderNextLetters(query, apps.toList(), apps.toSet(), { it }, { it },
            { it.normalizeForSearch() }, { true })

    @Test
    fun twoAppsCanHaveDifferentHints() {
        assertEquals(mapOf("Reddit" to 'd', "Relay" to 'l'), hints("re", "Reddit", "Relay"))
    }

    @Test
    fun sharedLetterIsBlockedByOffscreenResult() {
        assertEquals(emptyMap<String, Char>(),
            hints("re", "Reddit", "Redbox", "Redfin"))
    }

    @Test
    fun noHintsWhenEveryContinuationIsAmbiguous() {
        assertEquals(emptyMap<String, Char>(),
            hints("re", "Reddit", "Redbox", "Relay", "Relate"))
    }

    @Test
    fun continuationCanWrapToStartOfName() {
        assertEquals(mapOf("Facebook" to 'f', "Bookmark" to 'm'),
            hints("book", "Facebook", "Bookmark"))
    }

    @Test
    fun originalLabelsParticipateAndNonLaunchableResultsStillBlockHints() {
        val apps = listOf("Custom", "Redbox", "Relay")
        val result = thunderNextLetters("re", apps, apps.toSet(), { it },
            { if (it == "Custom") "Reddit" else it }, { it.normalizeForSearch() },
            { it != "Redbox" })
        assertEquals(mapOf("Relay" to 'l'), result)
    }
}
