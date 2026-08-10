package com.steamstreet.awskt.core

/** Reads an environment variable. JVM `System.getenv`; native `getenv` + `toKString`. */
internal expect fun platformGetEnv(name: String): String?

/**
 * Reads a JVM system property. Always null on native, where the concept does not exist — callers
 * must treat it as one optional source in a chain, never as required.
 */
internal expect fun platformGetProperty(name: String): String?

/**
 * Wall-clock time in epoch milliseconds.
 *
 * Must be wall-clock, not monotonic: SigV4 signs an absolute timestamp and AWS rejects one more
 * than a few minutes from its own.
 */
internal fun currentEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
