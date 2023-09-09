package com.steamstreet.exceptions

import kotlinx.coroutines.delay
import kotlin.reflect.KClass

/**
 * Retry if an exception is encountered.
 *
 * @param throwHandler called whenever an exception is thrown, even if we're still retrying, allowing for logging.
 */
public suspend fun <R> retry(
    times: Int,
    exceptionType: KClass<out Throwable>? = null,
    delay: Long = 0,
    throwHandler: (Throwable) -> Unit = {},
    block: suspend () -> R
): R {
    for (i in 0 until times) {
        try {
            return block()
        } catch (e: Throwable) {
            if ((exceptionType == null || e.javaClass.kotlin == exceptionType) && i < times - 1) {
                throwHandler(e)
                if (delay > 0) {
                    delay(delay)
                }
            } else {
                throw e
            }
        }
    }
    // we'll never get here, so just throw
    throw IllegalStateException("This should never happen in retry")
}