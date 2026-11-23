package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNameMatcherTest {
    private fun searchName(raw: String) = ContactNameMatcher.SearchName(raw)

    @Test
    fun `matches initials after direct names`() {
        val jm = ContactNameMatcher("jm")
        assertTrue(jm.rank(searchName("Jm"), emptyList())!! < jm.rank(searchName("Jamie Morgan"), emptyList())!!)
        assertEquals(4, ContactNameMatcher("jms").rank(searchName("Jamie Marie Smith"), emptyList()))
        assertNull(ContactNameMatcher("jms").rank(searchName("Jamie Morgan"), emptyList()))
    }

    @Test
    fun `phone search matches across formatting`() {
        val name = searchName("Jamie Morgan")
        assertEquals(3, ContactNameMatcher("(555) 123-4567").rank(name, listOf("+15551234567")))
        assertEquals(3, ContactNameMatcher("555123").rank(name, listOf("(555) 123-4567")))
        assertNull(ContactNameMatcher("555234").rank(name, listOf("(555) 123-4567")))
    }

    @Test
    fun `matches surname first and distinguishes typo corrections`() {
        assertEquals(3, ContactNameMatcher("morgan jamie").rank(searchName("Jamie Morgan"), emptyList()))
        assertEquals(3, ContactNameMatcher("morgan jose").rank(searchName("José Morgan"), emptyList()))
        assertEquals(ContactNameMatcher.TYPO_MATCH,
            ContactNameMatcher("jamie morgna").rank(searchName("Jamie Morgan"), emptyList()))
        assertNull(ContactNameMatcher("5551").rank(searchName("Jamie Morgan"), listOf("5552")))
    }
}
