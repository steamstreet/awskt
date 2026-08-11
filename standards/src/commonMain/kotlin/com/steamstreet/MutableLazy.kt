package com.steamstreet

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration

internal object UninitializedValue

/**
 * A version of the lazy delegate that also allows the value to be set at any point.
 *
 * The initializer runs on first read. If [timeout] is finite, a read taken after the timeout has
 * elapsed since the value was last computed runs the initializer again. A null or infinite timeout
 * means the value is computed once and kept forever.
 *
 * Assigning a value explicitly pins it: the initializer is never called again, whatever the timeout.
 */
public class MutableLazy<T>(private val timeout: Duration?, initializer: () -> T) :
    ReadWriteProperty<Any?, T> {
    private val lock = MutableLazyLock()
    private var value: Any? = UninitializedValue
    private var initializer: (() -> T)? = initializer
    private var lastRetrieved: Long = epochMillis()
    private var manuallySet = false

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (!manuallySet && needsNewValue()) {
            lock.withLock {
                if (!manuallySet && needsNewValue()) {
                    retrieve()
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /**
     * True when the initializer should run.
     *
     * The elapsed time is compared against the timeout rather than adding the timeout to
     * [lastRetrieved]: `Duration.INFINITE.inWholeMilliseconds` is [Long.MAX_VALUE], so adding it to
     * a timestamp overflows into a negative number and every comparison against it is meaningless.
     */
    private fun needsNewValue(): Boolean {
        if (value === UninitializedValue) return true
        val expiry = timeout ?: return false
        return epochMillis() - lastRetrieved >= expiry.inWholeMilliseconds
    }

    private fun retrieve() {
        value = initializer!!()
        lastRetrieved = epochMillis()
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        lock.withLock {
            this.value = value
            manuallySet = true
        }
    }
}

/**
 * A version of the lazy delegate that also allows the value to be set at any point.
 */
public fun <T> mutableLazy(initializer: () -> T): ReadWriteProperty<Any?, T> =
    cached(Duration.INFINITE, initializer)

/**
 * A lazy delegate with a timeout. Once duration has been met, the initializer will be called
 * again.
 */
public fun <T> cached(timeout: Duration, initializer: () -> T): ReadWriteProperty<Any?, T> =
    MutableLazy(timeout, initializer)
