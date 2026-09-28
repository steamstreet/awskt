package com.steamstreet.env

/**
 * Wrapper for the environment to allow for more flexibility when returning environment variables and potentially
 * other environment customizations.
 */
public object Env {
    public fun optional(key: String): String? = getEnvironmentVariable(key)

    /**
     * Wraps the request in a lazy block so that it won't be evaluated until first used. Reading the value throws
     * [IllegalStateException] naming [key] if the variable has no value, as [get] does.
     */
    public fun lazy(key: String): Lazy<String> = lazy { required(key) }

    /**
     * Get an environment variable. We use this to allow certain environments to interject values, since environment
     * variables cannot be changed programmatically.
     *
     * @throws IllegalStateException naming [key] if the variable has no value: it is unset, set to `_NoValue`, or
     * refers to a secret that could not be read. Through 3.1.5 this was a bare [NullPointerException] that did not
     * name the variable. Use [optional] to read a variable that may be absent.
     */
    public operator fun get(key: String): String = required(key)

    private fun required(key: String): String =
        getEnvironmentVariable(key) ?: throw IllegalStateException("Environment variable '$key' is not set")

    /**
     * Get an integer environment variable.
     */
    public fun int(key: String): Int? = getIntEnvironmentVariable(key)

    /**
     * Get a long environment variable.
     */
    public fun long(key: String): Long? = getEnvironmentVariable(key)?.toLongOrNull()
}

/**
 * Native call to get an environment variable
 */
public expect fun getEnvironmentVariable(key: String): String?

/**
 * Native call to get an integer environment variable.
 */
public expect fun getIntEnvironmentVariable(key: String): Int?

/**
 * Register an environment variable. This simple implementation installs system properties with an "ENV." prefix.
 * This allows other tools to be used to install variables (like the command line).
 */
public expect fun registerEnvironmentVariable(key: String, value: String)