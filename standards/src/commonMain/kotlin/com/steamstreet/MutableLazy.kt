package com.steamstreet

import kotlin.properties.ReadWriteProperty
import kotlin.time.Duration

internal object UninitializedValue

//public expect class MutableLazy<T>(timeout: Duration? = null, initializer: () -> T) : ReadWriteProperty<Any?, T>

/**
 * A version of the lazy delegate that also allows the value to be set at any point.
 */
public fun <T> mutableLazy(initializer: () -> T): ReadWriteProperty<Any?, T> =
    cached(Duration.INFINITE, initializer)

/**
 * A lazy delegate with a timeout. Once duration has been met, the initializer will be called
 * again.
 */
public expect fun <T> cached(timeout: Duration, initializer: () -> T): ReadWriteProperty<Any?, T>