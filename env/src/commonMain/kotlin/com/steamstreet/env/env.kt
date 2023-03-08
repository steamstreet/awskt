package com.steamstreet.env

/**
 * Wrapper for the environment to allow for more flexibility when returning environment variables and potentially
 * other environment customizations.
 */
public object Env {
    public fun optional(key: String): String? = getEnvironmentVariable(key)

    /**
     * Wraps the request in a lazy block so that it won't be evaluated until first used.
     */
    public fun lazy(key: String): Lazy<String> = lazy { getEnvironmentVariable(key)!! }

    /**
     * Get an environment variable. We use this to allow certain environments to interject values, since environment
     * variables cannot be changed programmatically.
     */
    public operator fun get(key: String): String = getEnvironmentVariable(key)!!

    public fun int(key: String): Int? = getIntEnvironmentVariable(key)

    /**
     * Get a long value.
     */
    public fun long(key: String): Long? = getEnvironmentVariable(key)?.toLongOrNull()
}

public expect fun getEnvironmentVariable(key: String): String?
public expect fun getIntEnvironmentVariable(key: String): Int?

/**
 * Register an environment variable. This simple implementation installs system properties with an "ENV." prefix.
 * This allows other tools to be used to install variables (like the command line).
 */
public expect fun registerEnvironmentVariable(key: String, value: String)

