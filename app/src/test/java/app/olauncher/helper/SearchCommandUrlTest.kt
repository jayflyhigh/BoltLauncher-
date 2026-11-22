package app.olauncher.helper

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class SearchCommandUrlTest {
    @Test fun `both placeholder styles substitute queries inside a URL`() {
        assertEquals("https://example.com/search?q=cat%20videos&type=pins", SearchCommandUrl.build("https://example.com/search?q={query}&type=pins", "cat videos"))
        assertEquals("myapp://search/cat%20videos", SearchCommandUrl.build("myapp://search/%s", "cat videos"))
    }
    @Test fun `prefix URLs append the encoded search`() {
        assertEquals("https://example.com/search?q=cat%20videos", SearchCommandUrl.build(" https://example.com/search?q= ", "cat videos"))
    }
    @Test fun `query text cannot inject parameters or fragments and unicode round trips`() {
        val query = "日本 café & sort=top #1 + 100%"
        val uri = URI(SearchCommandUrl.build("https://example.com/search?q={query}", query))
        assertNull(uri.fragment)
        assertEquals(1, uri.rawQuery.split('&').size)
        assertEquals(query, URLDecoder.decode(uri.rawQuery.substringAfter('='), "UTF-8"))
    }
    @Test fun `URL validation permits web and app schemes while rejecting invalid or local URLs`() {
        assertTrue(SearchCommandUrl.valid("https://example.com/search?q={query}"))
        assertTrue(SearchCommandUrl.valid("myapp:search:%s"))
        assertFalse(SearchCommandUrl.valid("not a URL"))
        assertFalse(SearchCommandUrl.valid("https:///search?q={query}"))
        assertFalse(SearchCommandUrl.valid("javascript:alert('{query}')"))
        assertFalse(SearchCommandUrl.valid("file:///data/{query}"))
    }
    @Test fun `aliases reject spaces and case insensitive duplicates`() {
        assertTrue(SearchCommandUrl.validAlias("動画!", emptyList()))
        assertFalse(SearchCommandUrl.validAlias("my app", emptyList()))
        assertFalse(SearchCommandUrl.validAlias(" yt ", emptyList()))
        assertFalse(SearchCommandUrl.validAlias("YT", listOf("yt")))
    }

    @Test fun `default aliases skip previously reserved codes`() {
        assertEquals("play", SearchCommandUrl.availableAlias("play", emptyList()))
        assertEquals("play3", SearchCommandUrl.availableAlias("play", listOf("PLAY", "Play2")))
    }

    @Test fun `confirmation consumes one space and preserves the rest of the query`() {
        assertEquals("rd" to "café & cats", SearchCommandUrl.confirmed("RD café & cats", listOf("rd")))
        assertEquals("rd" to "", SearchCommandUrl.confirmed("rd ", listOf("rd")))
        assertEquals("rd" to " cats", SearchCommandUrl.confirmed("rd  cats", listOf("rd")))
        assertNull(SearchCommandUrl.confirmed("rd", listOf("rd")))
        assertNull(SearchCommandUrl.confirmed("rd.cats", listOf("rd")))
        assertNull(SearchCommandUrl.confirmed("read cats", listOf("rd")))
        assertNull(SearchCommandUrl.confirmed("cats rd hello", listOf("rd")))
    }
}
