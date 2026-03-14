package com.steamstreet.env

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.setenv

@OptIn(ExperimentalForeignApi::class)
public actual fun getEnvironmentVariable(key: String): String? {
    return getenv(key)?.toKString()
}

@OptIn(ExperimentalForeignApi::class)
public actual fun registerEnvironmentVariable(key: String, value: String) {
    setenv(key, value, 1)
}

public actual fun getIntEnvironmentVariable(key: String): Int? {
    return getEnvironmentVariable(key)?.toIntOrNull()
}
