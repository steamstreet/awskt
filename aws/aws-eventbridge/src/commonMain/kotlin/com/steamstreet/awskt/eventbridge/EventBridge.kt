package com.steamstreet.awskt.eventbridge

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.callJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient

/**
 * EventBridge's AWS-JSON 1.1 dialect.
 *
 * The target prefix is `AWSEvents` and the endpoint prefix is `events` — they differ, which is why
 * both are named explicitly here rather than letting one default from the other.
 */
public val EVENTBRIDGE_PROTOCOL: AwsProtocol =
    AwsProtocol.awsJson1_1(endpointPrefix = "events", targetPrefix = "AWSEvents")

/**
 * An EventBridge client.
 *
 * ### Extending it
 *
 * As with `DynamoDb` (plan Decision 18), [client] is public and [putEvents] has no privileged
 * access to it — it is a `callJson` on that same object. An operation this library does not ship,
 * such as `PutRule` or `CreateEventBus`, can be added downstream as an extension function with
 * identical signing, retry and error handling.
 */
public interface EventBridgeApi : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    public suspend fun putEvents(entries: List<PutEventsEntry>): PutEventsResponse
}

/** Configuration for [EventBridgeApi]. */
public class EventBridgeConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller. A
     * caller-supplied client with no `HttpTimeout` plugin has no attempt bound at all.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits for the client this factory builds. Ignored when [httpClient] is set.
     *
     * Worth reading together with [EventBridgeApi.putEvents]'s `NOT_IDEMPOTENT` safety: a request
     * timeout may have landed, so a timed-out `PutEvents` is surfaced rather than replayed and the
     * caller decides whether to republish. Shortening this value therefore converts hangs into
     * *decisions*, not into duplicate deliveries.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()
}

/** Builds an EventBridge client. */
public fun EventBridge(configure: EventBridgeConfig.() -> Unit = {}): EventBridgeApi {
    val config = EventBridgeConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultEventBridge(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("events", region, config.endpointUrl),
            region = region,
            protocol = EVENTBRIDGE_PROTOCOL,
            retryConfig = config.retryConfig,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultEventBridge(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : EventBridgeApi {

    /**
     * `NOT_IDEMPOTENT`, and this is the interesting call in the module.
     *
     * `PutEvents` has no request token — unlike `TransactWriteItems`, there is nothing to
     * de-duplicate on. If the bytes reached EventBridge and the socket died before the response
     * came back, a retry publishes every entry in the batch a second time, and any rule targeting
     * them fires twice. Duplicate delivery is cheaper than silent loss for most callers, but it is
     * not a decision this client gets to make silently: an ambiguous transport failure surfaces as
     * an exception, and the caller decides whether to republish.
     */
    override suspend fun putEvents(entries: List<PutEventsEntry>): PutEventsResponse =
        mapErrors {
            client.callJson(
                "PutEvents",
                PutEventsRequest(entries),
                PutEventsRequest.serializer(),
                PutEventsResponse.serializer(),
                safety = OperationSafety.NOT_IDEMPOTENT,
            )
        }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}
