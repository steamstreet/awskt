package com.steamstreet.env

import kotlin.properties.ReadOnlyProperty
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
public class EnvProperty(public val name: String, private val default: String? = null): ReadOnlyProperty<Any?, String> {
    public override operator fun getValue(thisRef: Any?, property: KProperty<*>): String = value

    /**
     * Get the value of the property
     */
    public val value: String get() {
        return if (default != null) {
            Env.optional(name) ?: default
        } else {
            Env[name]
        }
    }

    public val optional: EnvOptionalProperty get() = EnvOptionalProperty(name)
}

/**
 * An optional version of the property.
 */
public class EnvOptionalProperty(public val name: String): ReadOnlyProperty<Any?, String?> {
    public override operator fun getValue(thisRef: Any?, property: KProperty<*>): String? = value

    /**
     * Get the value of the property
     */
    public val value: String? get() {
        return Env.optional(name)
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

public val x: EnvProperty = env("SomeName")