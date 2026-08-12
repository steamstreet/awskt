package com.steamstreet.awskt.core

/** Reads an environment variable. JVM `System.getenv`; native `getenv` + `toKString`. */
internal expect fun platformGetEnv(name: String): String?

/**
 * Reads an environment variable.
 *
 * Public so sibling service modules can honour the AWS environment conventions
 * (`AWS_ENDPOINT_URL_*`, `AWS_CREDENTIAL_EXPIRATION`) without each declaring its own
 * `expect`/`actual` pair for `getenv`. Injectable at every call site that uses it, so tests never
 * depend on the ambient process environment.
 */
public fun awsEnv(name: String): String? = platformGetEnv(name)

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
