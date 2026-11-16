package app.olauncher.helper

import org.junit.Assert.assertEquals
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
}
