package app.olauncher.helper

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class InAppSearchLinksTest {
    @Test fun `special characters stay inside the query instead of adding parameters`() {
        val query = "café & cats #1 + 100% / 日本"
        for (pkg in listOf("com.google.android.youtube", "com.google.android.apps.maps", "com.android.vending", "com.reddit.frontpage", "com.pinterest")) {
            val uri = URI(InAppSearchLinks.url(pkg, query)!!)
            assertNull(uri.fragment)
            val encoded = uri.rawQuery.substringAfterLast('=')
            assertEquals(query, URLDecoder.decode(encoded, "UTF-8"))
        }
    }
    @Test fun `maps retains required API parameter and Play uses its documented search path`() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=coffee", InAppSearchLinks.url("com.google.android.apps.maps", "coffee"))
        assertEquals("https://play.google.com/store/search?q=launcher", InAppSearchLinks.url("com.android.vending", "launcher"))
    }
    @Test fun `Pinterest and YouTube links include their search paths and query keys`() {
        assertEquals("https://www.pinterest.com/search/pins/?q=home%20ideas", InAppSearchLinks.url("com.pinterest", "home ideas"))
        assertEquals("https://www.youtube.com/results?search_query=live%20music", InAppSearchLinks.url("com.google.android.youtube", "live music"))
    }
    @Test fun `unknown apps and unverified Spotify scheme are not advertised as links`() {
        assertNull(InAppSearchLinks.url("com.spotify.music", "music"))
        assertNull(InAppSearchLinks.url("example.app", "anything"))
    }
}
