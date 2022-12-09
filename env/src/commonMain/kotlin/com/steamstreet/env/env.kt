package com.steamstreet.env

/**
 * Wrapper for the environment to allow for more flexibility when returning environment variables and potentially
 * other environment customizations.
 */
object Env {
    fun optional(key: String): String? = getEnvironmentVariable(key)

    /**
     * Wraps the request in a lazy block so that it won't be evaluated until first used.
     */
    fun lazy(key: String): Lazy<String> = lazy { getEnvironmentVariable(key)!! }

    /**
     * Get an environment variable. We use this to allow certain environments to interject values, since environment
     * variables cannot be changed programmatically.
     */
    operator fun get(key: String): String = getEnvironmentVariable(key)!!

    fun int(key: String): Int? = getIntEnvironmentVariable(key)

    fun long(key: String): Long? = getEnvironmentVariable(key)?.toLongOrNull()
}

expect fun getEnvironmentVariable(key: String): String?
expect fun getIntEnvironmentVariable(key: String): Int?

/**
 * Register an environment variable. This simple implementation installs system properties with an "ENV." prefix.
 * This allows other tools to be used to install variables (like the command line).
 */
expect fun registerEnvironmentVariable(key: String, value: String)

