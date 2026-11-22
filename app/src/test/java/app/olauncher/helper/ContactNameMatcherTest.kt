package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNameMatcherTest {
    @Test
    fun `matches initials after direct names`() {
        val jm = ContactNameMatcher("jm")
        assertTrue(jm.rank("Jm", emptyList())!! < jm.rank("Jamie Morgan", emptyList())!!)
        assertEquals(4, ContactNameMatcher("jms").rank("Jamie Marie Smith", emptyList()))
        assertNull(ContactNameMatcher("jms").rank("Jamie Morgan", emptyList()))
    }

    @Test
    fun `matches surname first and distinguishes typo corrections`() {
        assertEquals(3, ContactNameMatcher("morgan jamie").rank("Jamie Morgan", emptyList()))
        assertEquals(3, ContactNameMatcher("morgan jose").rank("José Morgan", emptyList()))
        assertEquals(ContactNameMatcher.TYPO_MATCH,
            ContactNameMatcher("jamie morgna").rank("Jamie Morgan", emptyList()))
        assertNull(ContactNameMatcher("5551").rank("Jamie Morgan", listOf("5552")))
    }
}
