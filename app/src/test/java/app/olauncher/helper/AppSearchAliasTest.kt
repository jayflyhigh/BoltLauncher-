package app.olauncher.helper

import org.junit.Assert.*
import org.junit.Test

class AppSearchAliasTest {
    @Test fun `typing without a space stays normal search`() {
        assertNull(AppSearchAlias.confirmed("yt"))
        assertNull(AppSearchAlias.confirmed(""))
        assertNull(AppSearchAlias.confirmed(" yt"))
    }
    @Test fun `space confirms a case insensitive code and preserves query`() {
        assertEquals("yt" to "", AppSearchAlias.confirmed("YT "))
        assertEquals("yt" to "live music", AppSearchAlias.confirmed("yT live music"))
    }
    @Test fun `aliases cannot be empty contain separators or conflict`() {
        assertFalse(AppSearchAlias.valid("", emptyList()))
        assertFalse(AppSearchAlias.valid("my app", emptyList()))
        assertFalse(AppSearchAlias.valid("yt", listOf("YT")))
        assertTrue(AppSearchAlias.valid("yt2", listOf("yt")))
        assertTrue(AppSearchAlias.valid("yt", emptyList()))
    }
    @Test fun `normalization trims editor input`() {
        assertEquals("yt", AppSearchAlias.normalize(" YT "))
    }
}
