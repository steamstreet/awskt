package com.steamstreet.awskt.core

internal actual fun platformGetEnv(name: String): String? = System.getenv(name)

internal actual fun platformGetProperty(name: String): String? = System.getProperty(name)
