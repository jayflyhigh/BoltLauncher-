package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchMatcherTest {
    @Test
    fun `exact case-insensitive substring matches`() {
        assertTrue(appLabelMatches("Camera", "cam"))
        assertTrue(appLabelMatches("Camera", "CAMERA"))
    }

    @Test
    fun `no match when query is not a substring at all`() {
        assertFalse(appLabelMatches("Camera", "xyz"))
    }

    @Test
    fun `query is trimmed before the plain substring check`() {
        assertTrue(appLabelMatches("Camera", "  cam  "))
    }

    @Test
    fun `diacritics are ignored — cafe matches Café`() {
        assertTrue(appLabelMatches("Café", "cafe"))
    }

    @Test
    fun `diacritics in the query are also normalised`() {
        assertTrue(appLabelMatches("Cafe", "café"))
    }

    @Test
    fun `separator characters are ignored on both sides`() {
        assertTrue(appLabelMatches("Google Maps", "googlemaps"))
        assertTrue(appLabelMatches("Google-Maps", "google maps"))
        assertTrue(appLabelMatches("Sam's App", "sams app"))
    }

    @Test
    fun `blank query matches everything, via plain substring semantics`() {
        // String.contains("") is always true — this mirrors the pre-existing behaviour
        // exactly (not a new edge case introduced by extracting this function). The
        // production caller (AppDrawerAdapter) never reaches this path with a blank
        // query: it shows the unfiltered list instead via its own isNullOrBlank() guard.
        assertTrue(appLabelMatches("Camera", ""))
        assertTrue(appLabelMatches("Camera", "   "))
    }

    @Test
    fun `normalized match still requires real content, not just separators`() {
        // A query of only separator characters normalizes to an empty string,
        // which must not vacuously match every label via the fallback path.
        assertFalse(appLabelMatches("Camera", "---"))
    }
    @Test
    fun `prepared matcher reuses cached labels with unchanged search semantics`() {
        val labels = listOf("Café", "Google Maps", "Sam's App", "Camera", "---")
        val cached = labels.associateWith { it.normalizeForSearch() }
        for (query in listOf("cafe", "googlemaps", "sams app", "CAM", "  cam  ", "---", "", "xyz")) {
            val matcher = AppLabelMatcher(query)
            labels.forEach { label ->
                org.junit.Assert.assertEquals(appLabelMatches(label, query), matcher.matches(label, cached.getValue(label)))
            }
        }
    }
    @Test
    fun `thunder supports continuing from the end to the start of an app name`() {
        val facebook = "Facebook"
        val normalized = facebook.normalizeForSearch()
        for (query in listOf("book", "bookf", "bookfa", "bookface", "FACEBOOK")) {
            assertTrue(thunderAppLabelMatches(facebook, normalized, query))
        }
        assertFalse(appLabelMatches(facebook, "bookface"))
    }

    @Test
    fun `thunder matching does not accept extra repetitions or unrelated text`() {
        val normalized = "Facebook".normalizeForSearch()
        assertFalse(thunderAppLabelMatches("Facebook", normalized, "bookfacebook"))
        assertFalse(thunderAppLabelMatches("Facebook", normalized, "bookxyz"))
        assertFalse(thunderAppLabelMatches("Facebook", normalized, "---"))
        assertTrue(thunderAppLabelMatches("Google Maps", "Google Maps".normalizeForSearch(), "mapsgoogle"))
    }

    @Test
    fun `thunder highlights the original label across normalized and wrapped matches`() {
        assertEquals(listOf(0..3), thunderMatchRanges("Café", "cafe"))
        assertEquals(listOf(0..10), thunderMatchRanges("Google Maps", "googlemaps"))
        assertEquals(listOf(4..7, 0..1), thunderMatchRanges("Facebook", "bookfa"))
        assertEquals(emptyList<IntRange>(), thunderMatchRanges("Facebook", "bookxyz"))
    }

}
