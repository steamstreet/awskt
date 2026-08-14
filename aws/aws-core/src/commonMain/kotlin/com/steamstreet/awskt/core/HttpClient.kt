package com.steamstreet.awskt.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/**
 * How long a single AWS attempt may take before the transport gives up on it.
 *
 * ### Why this exists at all
 *
 * [RetryConfig.maxTotalRetryDuration] bounds only the *sleeps between* attempts. The attempt itself
 * is bounded by nothing but the network, so a TCP connection that black-holes after the handshake,
 * or a response that stalls halfway through its body, hangs the call forever — and the retry loop
 * never fires, because a request that never fails is never retried. Inside Lambda that surfaces as a
 * function timeout: no stack trace, no typed exception, no CloudWatch error entry, only a truncated
 * invocation. Every official AWS SDK ships a short connect timeout and a socket timeout by default
 * for exactly this reason, and these values are chosen to sit in the same range.
 *
 * ### Choosing the values
 *
 * @param connectTimeoutMillis TCP (and TLS) establishment only, default 3 s. Short on purpose: a
 *   connection to a regional AWS endpoint either completes in tens of milliseconds or is not going
 *   to complete. A failure in this phase is provably [TransportFailure.NOT_SENT], so it is the one
 *   timeout that is safe to retry even for a non-idempotent write.
 * @param socketTimeoutMillis the maximum gap between two bytes arriving, default 30 s. This is an
 *   idle-detector, not a transfer budget: a slow but progressing download resets it continuously.
 * @param requestTimeoutMillis the whole attempt — connect, send, and read the body to its end —
 *   default 30 s. **This one is a transfer budget**, and it is the value to raise for large
 *   payloads: `aws-s3` buffers up to `S3Config.maxBufferedDownloadBytes` (64 MB by default), and 64
 *   MB inside 30 s needs a sustained ~2.2 MB/s. That is comfortable within a Region and marginal
 *   across the public internet, so a caller who both raises the download ceiling and fetches over a
 *   slow link must raise this in step or convert a working download into a timeout. It is also the
 *   only one of the three that can fire *after* the request reached AWS, which makes it
 *   [TransportFailure.AMBIGUOUS] — retried for an idempotent operation, surfaced for a write.
 *
 * Any of the three may be null, which removes that bound entirely. Null is a deliberate choice a
 * caller makes for a specific client, never a default: an unbounded attempt is the hang this class
 * exists to prevent.
 *
 * ### Interaction with [RetryConfig.maxTotalRetryDuration]
 *
 * That deadline covers the **whole call**, and it defaults to 25 s — shorter than the 30 s request
 * timeout here. So an attempt that stalls for its full budget has already spent the call's deadline,
 * and the timeout surfaces on the first attempt rather than being retried. That is the intended
 * ordering for a Lambda (one bounded failure beats three, and the caller still gets a typed
 * exception instead of a function timeout), but it means the retry loop only rescues stalls that end
 * *early* — a socket that dies at 5 s, not one that hangs for the full 30. Callers who want a
 * long-transfer client that also retries must raise both numbers together.
 */
public class AwsHttpTimeouts(
    public val connectTimeoutMillis: Long? = 3_000,
    public val socketTimeoutMillis: Long? = 30_000,
    public val requestTimeoutMillis: Long? = 30_000,
) {
    init {
        // Ktor rejects a non-positive value too, but only when the first request is issued — a long
        // way from the line that set it. Fail where the mistake was made.
        require(connectTimeoutMillis == null || connectTimeoutMillis > 0) {
            "connectTimeoutMillis must be positive or null (unbounded), was $connectTimeoutMillis"
        }
        require(socketTimeoutMillis == null || socketTimeoutMillis > 0) {
            "socketTimeoutMillis must be positive or null (unbounded), was $socketTimeoutMillis"
        }
        require(requestTimeoutMillis == null || requestTimeoutMillis > 0) {
            "requestTimeoutMillis must be positive or null (unbounded), was $requestTimeoutMillis"
        }
    }

    override fun toString(): String =
        "AwsHttpTimeouts(connect=$connectTimeoutMillis, socket=$socketTimeoutMillis, " +
            "request=$requestTimeoutMillis)"
}

/**
 * The engine-independent half of [awsHttpClient], shared by both actuals.
 *
 * Factored out so the settings below are configured **once** rather than copied per platform — a
 * copy is how one engine silently ends up following redirects — and so a test can apply the exact
 * production configuration to a `MockEngine` and observe the resulting behaviour, which it cannot do
 * through [awsHttpClient] itself because that function hardcodes its engine.
 */
internal fun HttpClientConfig<*>.configureAwsClient(timeouts: AwsHttpTimeouts) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = timeouts.connectTimeoutMillis
        socketTimeoutMillis = timeouts.socketTimeoutMillis
        requestTimeoutMillis = timeouts.requestTimeoutMillis
    }
}

/**
 * Builds the HTTP client used for signed AWS calls: CIO on JVM, Curl on native.
 *
 * Three settings are not negotiable and are asserted by tests:
 *
 * - `followRedirects = false`. SigV4 signs `host`. Ktor follows `Location` by default, which would
 *   replay an `Authorization` header computed over one authority to a different host — the caller
 *   sees an opaque `SignatureDoesNotMatch` instead of a typed error, and the credential is sent
 *   off-origin. S3 has two live cases that trigger this (307 for pre-2019 Regions via the legacy
 *   global endpoint, 301 for path-style requests to the wrong regional endpoint), and this plan's
 *   own path-style fallback is exactly the configuration that produces them.
 * - `expectSuccess = false`. Error responses are data: the retry classifier and the error parser
 *   need the status and body, not an exception thrown before either can see them.
 * - Ktor's `HttpTimeout` plugin, configured from [timeouts]. Without it an attempt has no bound of
 *   any kind and a stalled socket hangs the call until the Lambda itself is killed — see
 *   [AwsHttpTimeouts] for why the retry loop cannot rescue that on its own.
 *
 * **The Lambda runtime-API client is deliberately not built here.** `lambdaHttpClient` in
 * `lambda-native` talks to `GET /runtime/invocation/next`, a long poll that legitimately blocks for
 * as long as the function sits idle — hours, on a low-traffic function. Any of these timeouts
 * applied to that client would turn normal idling into a stream of spurious failures, so it keeps
 * its own untimed configuration. The two clients look similar and mean opposite things; do not
 * unify them.
 *
 * @param caInfo path to a CA bundle for the native Curl engine. **Defaults to null on purpose** —
 *   libcurl then uses the system trust store. The AL2023 path `/etc/pki/tls/certs/ca-bundle.crt` is
 *   supplied only by the Lambda bootstrap; hardcoding it here would break macOS at runtime in a way
 *   no MockEngine test can catch.
 * @param timeouts per-attempt time limits. The defaults are AWS-SDK-shaped; raise
 *   [AwsHttpTimeouts.requestTimeoutMillis] for large S3 transfers.
 */
public expect fun awsHttpClient(
    caInfo: String? = null,
    timeouts: AwsHttpTimeouts = AwsHttpTimeouts(),
): HttpClient
