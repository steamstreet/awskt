package com.steamstreet.awskt.core

import io.ktor.client.HttpClient

/**
 * Builds the HTTP client used for signed AWS calls: CIO on JVM, Curl on native.
 *
 * Two settings are not negotiable and are asserted by tests:
 *
 * - `followRedirects = false`. SigV4 signs `host`. Ktor follows `Location` by default, which would
 *   replay an `Authorization` header computed over one authority to a different host — the caller
 *   sees an opaque `SignatureDoesNotMatch` instead of a typed error, and the credential is sent
 *   off-origin. S3 has two live cases that trigger this (307 for pre-2019 Regions via the legacy
 *   global endpoint, 301 for path-style requests to the wrong regional endpoint), and this plan's
 *   own path-style fallback is exactly the configuration that produces them.
 * - `expectSuccess = false`. Error responses are data: the retry classifier and the error parser
 *   need the status and body, not an exception thrown before either can see them.
 *
 * @param caInfo path to a CA bundle for the native Curl engine. **Defaults to null on purpose** —
 *   libcurl then uses the system trust store. The AL2023 path `/etc/pki/tls/certs/ca-bundle.crt` is
 *   supplied only by the Lambda bootstrap; hardcoding it here would break macOS at runtime in a way
 *   no MockEngine test can catch.
 */
public expect fun awsHttpClient(caInfo: String? = null): HttpClient
