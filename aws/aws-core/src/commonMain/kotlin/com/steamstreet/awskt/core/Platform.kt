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
 * A platform's answer for **one link** of a failure's cause chain, or null when it has nothing to
 * say about that link.
 *
 * [classifyTransportFailure] is otherwise a string matcher — class simple names and message
 * substrings — which is the only tool that works on Kotlin/Native, where curl reports every failure
 * as an untyped `IllegalStateException`. On the JVM that is both weaker and more fragile than it
 * needs to be: `java.net.ConnectException` usually carries no message worth matching, and every
 * name pattern silently stops working the day an engine wraps or renames something. This hook lets
 * a platform answer from the real type where it has one.
 *
 * Contract: return null unless certain. A non-null answer ends the chain walk immediately, so
 * answering [TransportFailure.AMBIGUOUS] would *suppress* a NOT_SENT that a deeper cause would have
 * produced. Both actuals today return [TransportFailure.NOT_SENT] or null, never AMBIGUOUS, which
 * makes the hook a strict strengthening of the string matching rather than a replacement for it.
 */
internal expect fun platformTransportFailureHint(failure: Throwable): TransportFailure?

/**
 * Wall-clock time in epoch milliseconds.
 *
 * Must be wall-clock, not monotonic: SigV4 signs an absolute timestamp and AWS rejects one more
 * than a few minutes from its own.
 */
internal fun currentEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
