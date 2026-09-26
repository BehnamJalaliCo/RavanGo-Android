package com.ravango.core.common.result

import kotlin.coroutines.cancellation.CancellationException

/** Typed error categories shown to users with localized messages. */
enum class ErrorKind { NETWORK, AUTH, PERMISSION, STORAGE_FULL, NOT_SUPPORTED, QUOTA, INVALID_INPUT, NOT_CONFIGURED, CANCELLED, UNKNOWN }

class AppException(val kind: ErrorKind, message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** Result type for operations that can fail in expected ways. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val kind: ErrorKind, val message: String? = null, val cause: Throwable? = null) : Outcome<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> = also { if (it is Outcome.Success) block(it.value) }
inline fun <T> Outcome<T>.onFailure(block: (Outcome.Failure) -> Unit): Outcome<T> = also { if (it is Outcome.Failure) block(it) }

/** Runs [block], converting exceptions to [Outcome.Failure] while never swallowing coroutine cancellation. */
suspend inline fun <T> outcomeOf(crossinline block: suspend () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: AppException) {
    Outcome.Failure(e.kind, e.message, e)
} catch (e: java.io.IOException) {
    Outcome.Failure(ErrorKind.NETWORK, e.message, e)
} catch (e: SecurityException) {
    Outcome.Failure(ErrorKind.PERMISSION, e.message, e)
} catch (e: Exception) {
    Outcome.Failure(ErrorKind.UNKNOWN, e.message, e)
}
