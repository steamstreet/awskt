package com.steamstreet

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration

/**
 * A version of the lazy delegate that also allows the value to be set at any point.
 */
public class MutableLazy<T>(private val timeout: Duration?, initializer: () -> T) :
    ReadWriteProperty<Any?, T> {
    private var value: Any? = UninitializedValue
    private var initializer: (() -> T)? = initializer
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
        value = initializer!!()
        lastRetrieved = epochMillis()
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
        manuallySet = true
    }
}

public actual fun <T> cached(timeout: Duration, initializer: () -> T): ReadWriteProperty<Any?, T> =
    MutableLazy(timeout, initializer)