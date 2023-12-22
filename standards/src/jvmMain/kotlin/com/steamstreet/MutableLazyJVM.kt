package com.steamstreet

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration

/**
 * A version of the lazy delegate that also allows the value to be set at any point.
 */
public actual class MutableLazy<T> actual constructor(private val timeout: Duration?, initializer: () -> T) :
    ReadWriteProperty<Any?, T> {
    private var value: Any? = UninitializedValue
    private var initializer: (() -> T)? = initializer
    private var lastRetrieved: Long = epochMillis()
    private var manuallySet = false

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (!manuallySet) {
            if (needsNewValue()) {
                synchronized(this) {
                    if (!manuallySet) {
                        if (needsNewValue()) {
                            retrieve()
                        }
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun needsNewValue(): Boolean {
        if (value === UninitializedValue) {
            return true
        } else if (timeout != null) {
            if (lastRetrieved + timeout.inWholeMilliseconds > epochMillis()) {
                return true
            }
        }
        return false
    }

    private fun retrieve() {
        value = initializer!!()
        lastRetrieved = epochMillis()
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(this) {
            this.value = value
            manuallySet = true
        }
    }
}