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
    @Test fun `custom aliases support symbols unicode and spaces but stay unique`() {
        assertTrue(SearchCommandUrl.validAlias("動画!", emptyList()))
        assertTrue(SearchCommandUrl.validAlias("my app", emptyList()))
        assertFalse(SearchCommandUrl.validAlias(" YT ", listOf("yt")))
        assertFalse(SearchCommandUrl.validAlias(" \t", emptyList()))
    }
    @Test fun `confirmation picks full longest alias and preserves original query`() {
        assertEquals("my app" to "red flowers", SearchCommandUrl.confirmed("MY APP red flowers", listOf("my", "my app")))
        assertEquals("yt" to "", SearchCommandUrl.confirmed("YT ", listOf("yt")))
        assertNull(SearchCommandUrl.confirmed("yt", listOf("yt")))
    }
}
