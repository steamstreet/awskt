package com.steamstreet.awskt.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformGetEnv(name: String): String? = getenv(name)?.toKString()

/** Native has no system properties. Returning null keeps the resolution chain uniform. */
internal actual fun platformGetProperty(name: String): String? = null
