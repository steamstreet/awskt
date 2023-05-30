package com.steamstreet

import kotlinx.coroutines.*
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration

internal object UninitializedValue

/**
 * A version of the lazy delegate that also allows the value to be set at any point.
 */
private class MutableLazy<T>(val timeout: Duration? = null, initializer: suspend () -> T) : ReadWriteProperty<Any?, T> {
    private var value: Any? = UninitializedValue
    private var initializer: (suspend () -> T)? = initializer
    private var lastRetrieved: Long = epochMillis()
    private var manuallySet = false

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (!manuallySet) {
            if (value === UninitializedValue) {
                retrieve()
            } else if (timeout != null) {
                if (lastRetrieved + timeout.inWholeMilliseconds > epochMillis()) {
                    retrieve()
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun retrieve() {
        CoroutineScope(Dispatchers.Default).launch {
            value = initializer?.invoke()
        }
        lastRetrieved = epochMillis()
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
        manuallySet = true
    }
}


/**
 * A version of the lazy delegate that also allows the value to be set at any point.
 */
public fun <T> mutableLazy(initializer: () -> T): ReadWriteProperty<Any?, T> = MutableLazy(null, initializer)

/**
 * A lazy delegate with a timeout. Once duration has been met, the initializer will be called
 * again.
 */
public fun <T> cached(timeout: Duration, initializer: () -> T): ReadWriteProperty<Any?, T> =
    MutableLazy(timeout, initializer)


public fun <T> coLazy(initializer: suspend () -> T): ReadOnlyProperty<Any?, T> = MutableLazy(null, initializer)
public fun <T> coMutableLazy(initializer: suspend () -> T): ReadWriteProperty<Any?, T> = MutableLazy(null, initializer)
