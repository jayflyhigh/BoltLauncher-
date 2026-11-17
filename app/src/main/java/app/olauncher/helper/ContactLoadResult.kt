package app.olauncher.helper

import kotlinx.coroutines.CancellationException

sealed class ContactLoadResult<out T> {
    data class Success<T>(val contacts: List<T>) : ContactLoadResult<T>()
    object PermissionDenied : ContactLoadResult<Nothing>()
    data class Failure(val exceptionType: String) : ContactLoadResult<Nothing>()
}

// Never retain exception messages or causes: provider errors may contain personal data.
internal inline fun <T> readContactsSafely(read: () -> List<T>): ContactLoadResult<T> = try {
    ContactLoadResult.Success(read())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: SecurityException) {
    ContactLoadResult.PermissionDenied
} catch (failure: Exception) {
    ContactLoadResult.Failure(failure.javaClass.name)
}
