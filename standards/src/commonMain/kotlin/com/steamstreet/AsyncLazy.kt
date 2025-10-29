package com.steamstreet

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A thread-safe lazy initialization wrapper for suspend functions that supports both
 * eager initialization (when a default value is provided) and lazy initialization
 * (when the factory is called on first access).
 *
 * This is useful for Lambda handlers and other contexts where you want to:
 * - Support dependency injection (provide a value at construction time)
 * - Fall back to lazy initialization from environment (create on first use)
 * - Ensure thread-safe initialization without blocking threads
 *
 * @param T The type of value being lazily initialized
 * @param default An optional default value (e.g., from dependency injection)
 * @param factory A suspend function that creates the value if needed
 *
 * Example usage:
 * ```kotlin
 * class MyLambdaHandler(
 *     private val service: MyService? = null
 * ) {
 *     private val serviceDelegate = AsyncLazy(service) {
 *         MyService.fromEnvironment()
 *     }
 *
 *     suspend fun getService() = serviceDelegate.get()
 *
 *     suspend fun handleEvent() {
 *         val svc = getService()
 *         // use svc...
 *     }
 * }
 * ```
 */
public class AsyncLazy<T>(
    private val default: T? = null,
    private val factory: suspend () -> T
) {
    private val mutex = Mutex()
    private var instance: T? = default

    /**
     * Get the value, initializing it if necessary.
     * If a default was provided at construction, it's returned immediately.
     * Otherwise, the factory is called (once) on first access.
     */
    public suspend fun get(): T {
        // Fast path: if we have a default, return it immediately without locking
        default?.let { return it }

        // Slow path: need to initialize lazily
        return mutex.withLock {
            instance ?: factory().also {
                instance = it
            }
        }
    }

    /**
     * Check if the value has been initialized (either via default or factory).
     */
    public val isInitialized: Boolean
        get() = instance != null
}

/**
 * Convenience extension function to create an AsyncLazy with a cleaner syntax.
 *
 * Example:
 * ```kotlin
 * private val service = asyncLazy(injectedService) {
 *     MyService.fromEnvironment()
 * }
 * ```
 */
public fun <T> asyncLazy(default: T? = null, factory: suspend () -> T): AsyncLazy<T> {
    return AsyncLazy(default, factory)
}
