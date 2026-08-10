package com.steamstreet.awskt.core

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol

/** A configuration value could not be resolved from any source. */
public class AwsConfigurationException(message: String) : Exception(message)

/**
 * A resolved service endpoint.
 *
 * @param url the base URL, without a trailing path.
 * @param authority the value signed as `host` and sent as the `Host` header — **including the port
 *   when it is not the scheme default**. Every integration test in this repo runs against LocalStack
 *   on an ephemeral port, so dropping the port passes CI and fails production.
 */
public class AwsEndpoint(
    public val url: String,
    public val authority: String,
    public val protocol: URLProtocol,
    public val host: String,
    public val port: Int,
) {
    override fun toString(): String = "AwsEndpoint($url)"
}

/**
 * Resolves the region: explicit configuration first, then the environment, then — on JVM only —
 * the `aws.region` system property. Throws naming every source it tried, because a missing region
 * is otherwise diagnosed as a mysterious signature failure.
 */
public fun resolveRegion(
    explicit: String? = null,
    getEnv: (String) -> String? = ::platformGetEnv,
    getProperty: (String) -> String? = ::platformGetProperty,
): String =
    explicit?.takeIf { it.isNotBlank() }
        ?: getEnv("AWS_REGION")?.takeIf { it.isNotBlank() }
        ?: getEnv("AWS_DEFAULT_REGION")?.takeIf { it.isNotBlank() }
        ?: getProperty("aws.region")?.takeIf { it.isNotBlank() }
        ?: throw AwsConfigurationException(
            "No AWS region configured. Set it explicitly on the client config, or via the " +
                "AWS_REGION or AWS_DEFAULT_REGION environment variable, or the aws.region system property.",
        )

/**
 * Resolves a service endpoint.
 *
 * Explicit configuration always wins — several existing tests set an endpoint override in code to
 * point at DynamoDB Local or LocalStack, and an environment variable must never be able to
 * silently redirect them.
 *
 * Deliberately not an endpoint-ruleset engine: the `aws` partition and the `amazonaws.com` suffix
 * are hardcoded. FIPS, dual-stack, China and GovCloud are out of scope; this stays a small function
 * that can grow a branch when one of them is actually needed.
 */
public fun resolveEndpoint(
    endpointPrefix: String,
    region: String,
    explicit: String? = null,
    getEnv: (String) -> String? = ::platformGetEnv,
): AwsEndpoint {
    val serviceEnvKey = "AWS_ENDPOINT_URL_" + endpointPrefix.uppercase().map {
        if (it.isLetterOrDigit()) it else '_'
    }.joinToString("")

    val url = explicit?.takeIf { it.isNotBlank() }
        ?: getEnv(serviceEnvKey)?.takeIf { it.isNotBlank() }
        ?: getEnv("AWS_ENDPOINT_URL")?.takeIf { it.isNotBlank() }
        ?: "https://$endpointPrefix.$region.amazonaws.com"

    return parseEndpoint(url)
}

internal fun parseEndpoint(url: String): AwsEndpoint {
    val builder = URLBuilder(url)
    val protocol = builder.protocol
    val host = builder.host
    // Ktor reports an unspecified port as 0, not as the scheme default. Left unnormalized, every
    // default-port endpoint would sign `host:0` and fail everywhere.
    val port = if (builder.port <= 0) protocol.defaultPort else builder.port
    val authority = if (port == protocol.defaultPort) host else "$host:$port"
    return AwsEndpoint(
        url = url.trimEnd('/'),
        authority = authority,
        protocol = protocol,
        host = host,
        port = port,
    )
}
