package com.steamstreet.env

private val env = HashMap<String, String>()

public actual fun getEnvironmentVariable(key: String): String? {
    return env[key]
}

/**
 * Register an environment variable. This simple implementation installs system properties with an "ENV." prefix.
 * This allows other tools to be used to install variables (like the command line).
 */
public actual fun registerEnvironmentVariable(key: String, value: String) {
    env[key] = value
}

public actual fun getIntEnvironmentVariable(key: String): Int? {
    return getEnvironmentVariable(key)?.toIntOrNull()
}