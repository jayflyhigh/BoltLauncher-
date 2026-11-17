package app.olauncher.helper

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class ContactLoadResultTest {
    @Test fun successfulLoadKeepsResults() {
        assertEquals(ContactLoadResult.Success(listOf(1, 2)), readContactsSafely { listOf(1, 2) })
    }

    @Test fun emptyAddressBookIsSuccessful() {
        assertEquals(ContactLoadResult.Success(emptyList<Int>()), readContactsSafely { emptyList<Int>() })
    }

    @Test fun permissionFailureIsDistinctFromEmptyAndProviderFailure() {
        assertSame(ContactLoadResult.PermissionDenied,
            readContactsSafely<Int> { throw SecurityException("private provider details") })
    }

    @Test fun providerAndCursorFailuresDoNotExposeMessages() {
        for (error in listOf(IllegalStateException("private data"), IndexOutOfBoundsException("private data"))) {
            val result = readContactsSafely<Int> { throw error }
            assertEquals(ContactLoadResult.Failure(error.javaClass.name), result)
            assertFalse(result.toString().contains("private data"))
        }
    }

    @Test fun failedReadClosesResourceAndAllowsRetry() {
        var closed = false
        val resource = Closeable { closed = true }
        assertTrue(readContactsSafely<Int> {
            resource.use { throw IllegalStateException() }
        } is ContactLoadResult.Failure)
        assertTrue(closed)
        assertEquals(ContactLoadResult.Success(listOf(1)), readContactsSafely { listOf(1) })
    }

    @Test fun cancellationEscapesUnchangedAndClosesResource() {
        var closed = false
        val cancelled = CancellationException("leaving screen")
        try {
            readContactsSafely<Int> {
                Closeable { closed = true }.use { throw cancelled }
            }
            fail("Cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertTrue(closed)
    }
}
