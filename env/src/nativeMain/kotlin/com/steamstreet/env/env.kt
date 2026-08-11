package com.steamstreet.env

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.setenv

@OptIn(ExperimentalForeignApi::class)
public actual fun getEnvironmentVariable(key: String): String? = getenv(key)?.toKString()

/**
 * Unlike the JVM actual, which shadows the environment with an `ENV.`-prefixed system property,
 * this writes the real process environment — a native process has nowhere else to put it. The
 * value is therefore visible to anything else in the process that reads `getenv`, and inherited by
 * child processes.
 */
@OptIn(ExperimentalForeignApi::class)
public actual fun registerEnvironmentVariable(key: String, value: String) {
    setenv(key, value, 1)
}

public actual fun getIntEnvironmentVariable(key: String): Int? =
    getEnvironmentVariable(key)?.toIntOrNull()
