package com.steamstreet.env

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * A property delegate for string values.
 */
public fun <T> env(name: String, default: String? = null): ReadOnlyProperty<T, String> = EnvProperty(name, default)

/**
 * A property delegate for integer values. This is a non-null property that requires a default.
 */
public fun <T> env(name: String, default: Int): ReadOnlyProperty<T, Int> = EnvIntProperty(name, default)

/**
 * Property class to read from the environment.
 */
public class EnvProperty<T>(private val name: String, private val default: String? = null) :
    ReadOnlyProperty<T, String> {
    override fun getValue(thisRef: T, property: KProperty<*>): String {
        return if (default != null) {
            Env.optional(name) ?: default
        } else {
            Env[name]
        }
    }
}

/**
 * A property that can be used to retrieve an environment variable as an integer.
 */
public class EnvIntProperty<T>(private val name: String, private val default: Int) :
    ReadOnlyProperty<T, Int> {
    override fun getValue(thisRef: T, property: KProperty<*>): Int {
        return Env.int(name) ?: default
    }
}