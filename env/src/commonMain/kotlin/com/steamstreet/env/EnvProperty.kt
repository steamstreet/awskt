package com.steamstreet.env

import kotlin.reflect.KProperty

/**
 * A property delegate for string values.
 */
public fun env(name: String, default: String? = null): EnvProperty = EnvProperty(name, default)

/**
 * A property delegate for integer values. This is a non-null property that requires a default.
 */
public fun env(name: String, default: Int): EnvIntProperty = EnvIntProperty(name, default)

/**
 * Property class to read from the environment.
 */
public class EnvProperty(private val name: String, private val default: String? = null) {
    public operator fun getValue(thisRef: Any, property: KProperty<*>): String {
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
public class EnvIntProperty(private val name: String, private val default: Int) {
    public operator fun getValue(thisRef: Any, property: KProperty<*>): Int {
        return Env.int(name) ?: default
    }
}

/**
 * Defines a variable as a constant that can be used in multiple places. Useful for sharing
 * environment variable names between the CDK and code.
 */
public fun variable(name: String, default: String? = null): EnvProperty = EnvProperty(name, default)
