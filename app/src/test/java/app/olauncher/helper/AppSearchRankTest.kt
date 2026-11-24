package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchRankTest {
    @Test fun `exact result stays below prefix word and substring matches`() {
        val labels = listOf("My Maps Tool", "Maps Go", "Maps", "Bitmaps")
        assertEquals(listOf("Maps", "Maps Go", "My Maps Tool", "Bitmaps"),
            labels.sortedBy { appSearchRank(it, "maps") })
    }

    @Test fun `word matching preserves whitespace and case rules`() {
        assertEquals(2, appSearchRank("Google\t  MAPS", "map"))
        assertEquals(0, appSearchRank("MAPS", "maps"))
        assertEquals(3, appSearchRank("Bitmaps", "maps"))
    }

    @Test fun `exact contact beats partial app`() {
        assertTrue(contactWinsBestMatch("sam rivera", "Sam Rivera Notes", "Sam Rivera"))
    }

    @Test fun `exact app beats partial contact and wins exact ties`() {
        assertFalse(contactWinsBestMatch("sam", "Sam", "Sam Rivera"))
        assertFalse(contactWinsBestMatch("sam rivera", "Sam Rivera", "Sam Rivera"))
        assertFalse(contactWinsBestMatch("sam", "Sam Notes", "Sam Rivera"))
    }

    @Test fun `displayed names ignore repeated whitespace and case`() {
        val query = "  SAM   RIVERA  "
        val name = ContactNameMatcher.SearchName("Sam Rivera")
        assertTrue(contactWinsBestMatch(query, "Rivera Notes", name.raw))
        assertEquals(-1, ContactNameMatcher(query).rank(name, emptyList()))
        assertFalse(contactWinsBestMatch(query, " Sam\tRivera ", name.raw))
    }
}
