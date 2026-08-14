package com.steamstreet.awskt.secretsmanager

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.callJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.randomUuidString
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient

/**
 * Secrets Manager's AWS-JSON 1.1 dialect.
 *
 * The target prefix and the endpoint prefix are both `secretsmanager` — unusually, they agree. Both
 * are still named explicitly, so that the one place this would have to change is the one place it
 * is written down.
 */
public val SECRETS_MANAGER_PROTOCOL: AwsProtocol =
    AwsProtocol.awsJson1_1(endpointPrefix = "secretsmanager", targetPrefix = "secretsmanager")

/**
 * A Secrets Manager client covering the **data plane**: the operations that read and write secret
 * *values*.
 *
 * `CreateSecret`, `DeleteSecret`, `DescribeSecret`, `TagResource`, `RotateSecret` and the resource
 * policy calls are out of scope — a secret's existence is provisioned by whatever manages the
 * infrastructure, and the Lambdas this library exists to serve consume secrets rather than declare
 * them. All of them are reachable through the extension seam below.
 *
 * The one operation on the boundary is `UpdateSecretVersionStage`, which a rotation function needs
 * to promote `AWSPENDING` to `AWSCURRENT`. It is left out on the grounds that a rotation function
 * needs `DescribeSecret` as well, so shipping half of that flow would be more misleading than
 * shipping none of it — see [putSecretValue]'s KDoc for the shape, and the seam for how to add
 * both in about fifteen lines.
 *
 * ### Caching is the caller's job, and it matters more here than elsewhere
 *
 * This client does not cache. Secrets Manager charges per 10,000 API calls and is rate limited per
 * account, so a Lambda that calls [getSecretValue] on every invocation pays for it twice — in
 * dollars and in a throttle that arrives during a traffic spike, which is exactly when it is least
 * survivable. Resolve secrets once per container (a `val` at file scope, or `env`'s
 * `SecretsProvider`) and accept that a rotation is picked up on the next cold start. If a rotation
 * has to be picked up sooner than that, cache with an expiry and catch the authentication failure
 * that a rotated credential produces — do not solve it by fetching every time.
 *
 * ### Extending it
 *
 * As with `DynamoDb`, `EventBridge` and `Kms`, [client] is public and no operation below has
 * privileged access to it:
 *
 * ```kotlin
 * @Serializable
 * data class UpdateSecretVersionStageRequest(
 *     @SerialName("SecretId") val secretId: String,
 *     @SerialName("VersionStage") val versionStage: String,
 *     @SerialName("MoveToVersionId") val moveToVersionId: String? = null,
 *     @SerialName("RemoveFromVersionId") val removeFromVersionId: String? = null,
 * )
 *
 * @Serializable
 * data class UpdateSecretVersionStageResponse(
 *     @SerialName("ARN") val arn: String? = null,
 *     @SerialName("Name") val name: String? = null,
 * )
 *
 * suspend fun SecretsManager.updateSecretVersionStage(
 *     request: UpdateSecretVersionStageRequest,
 * ): UpdateSecretVersionStageResponse =
 *     client.callJson(
 *         "UpdateSecretVersionStage",
 *         request,
 *         UpdateSecretVersionStageRequest.serializer(),
 *         UpdateSecretVersionStageResponse.serializer(),
 *     )
 * ```
 */
public interface SecretsManager : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Reads one secret.
     *
     * See [GetSecretValueResponse] for why the answer may be in [GetSecretValueResponse.secretBinary]
     * rather than [GetSecretValueResponse.secretString], and [getSecretString] for the shape most
     * callers want.
     */
    public suspend fun getSecretValue(request: GetSecretValueRequest): GetSecretValueResponse

    /**
     * Reads up to 20 secrets in one call.
     *
     * **Read [BatchGetSecretValueResponse] before using this directly**: failures are reported
     * per-entry inside an HTTP 200, and pagination is possible even when every id fits in one
     * request. [getSecretValues] handles both.
     */
    public suspend fun batchGetSecretValue(request: BatchGetSecretValueRequest): BatchGetSecretValueResponse

    /**
     * Writes a new version of an existing secret.
     *
     * ### Why this is `IDEMPOTENT` despite being a write
     *
     * Secrets Manager takes a `ClientRequestToken`, and this method **generates one before the
     * retry loop starts and reuses it for every attempt of the call**. Under that token an
     * ambiguous replay is defined behaviour rather than a gamble: a request whose bytes already
     * landed is recognised, and because the content is byte-identical it is a no-op that returns
     * the version the first attempt created. A *different* value under the same token would be
     * [ResourceExistsException] — which is the case a caller supplying its own token has to think
     * about, and which cannot arise from a retry of one call.
     *
     * That is the same reasoning `aws-dynamodb` applies to `TransactWriteItems`, and it is
     * materialized the same way: the token is fixed *before* the first attempt, not per attempt. A
     * token minted inside the loop would make every retry a fresh write, which is the exact
     * behaviour the token exists to prevent — and, on a secret, would burn a version per attempt.
     *
     * A caller-supplied [PutSecretValueRequest.clientRequestToken] is honoured untouched.
     */
    public suspend fun putSecretValue(request: PutSecretValueRequest): PutSecretValueResponse
}

/** Configuration for [SecretsManager]. */
public class SecretsManagerConfig {
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
     * Worth shortening rather than lengthening for this service in particular: secrets are usually
     * fetched during a cold start, inside the same wall clock the caller's first request is waiting
     * on, and a secret fetch that hangs converts into an API Gateway timeout with no useful log line.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /**
     * Notified of every attempt, retry decision and give-up. Null means no instrumentation.
     *
     * Unlike [httpTimeouts] and [caInfo], this is **not** bypassed by supplying your own
     * [httpClient]: it observes the retry loop, which is this library's, rather than the transport
     * underneath it, which may be the caller's.
     */
    public var observer: AwsCallObserver? = null
}

/** Builds a Secrets Manager client. */
public fun SecretsManager(configure: SecretsManagerConfig.() -> Unit = {}): SecretsManager {
    val config = SecretsManagerConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultSecretsManager(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("secretsmanager", region, config.endpointUrl),
            region = region,
            protocol = SECRETS_MANAGER_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultSecretsManager(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : SecretsManager {

    override suspend fun getSecretValue(request: GetSecretValueRequest): GetSecretValueResponse =
        mapErrors {
            client.callJson(
                "GetSecretValue", request, GetSecretValueRequest.serializer(),
                GetSecretValueResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
            )
        }

    override suspend fun batchGetSecretValue(
        request: BatchGetSecretValueRequest,
    ): BatchGetSecretValueResponse = mapErrors {
        client.callJson(
            "BatchGetSecretValue", request, BatchGetSecretValueRequest.serializer(),
            BatchGetSecretValueResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun putSecretValue(request: PutSecretValueRequest): PutSecretValueResponse {
        // Materialized BEFORE the retry loop and reused verbatim for every attempt — the same
        // construction, and for the same reason, as `aws-dynamodb`'s TransactWriteItems token. See
        // the interface KDoc for why this is what makes the operation IDEMPOTENT.
        val withToken = request.clientRequestToken?.let { request }
            ?: request.withClientRequestToken(randomUuidString())
        return mapErrors {
            client.callJson(
                "PutSecretValue", withToken.toWire(), PutSecretValueWire.serializer(),
                PutSecretValueResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
            )
        }
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

// -- Conveniences --------------------------------------------------------------------------------

/**
 * Reads a secret and returns its string value.
 *
 * The shape almost every call site wants, and the one that removes the trap in
 * [GetSecretValueResponse]: a secret stored as binary would otherwise arrive as a null
 * `secretString` that reads exactly like "no secret", and a caller doing `?: error("missing")`
 * would report a secret that plainly exists as absent. This decodes
 * [GetSecretValueResponse.secretBinary] as UTF-8 instead, which is what a binary-stored *text*
 * secret is.
 *
 * @return the secret as text, or null when neither form was populated — which for an existing
 *   secret AWS does not do, so a null here means the response was not what it claimed to be.
 * @throws ResourceNotFoundException when the secret does not exist. Deliberately not folded into
 *   the null: "there is no such secret" and "the secret is empty" are different facts, and
 *   collapsing them is how a typo in a secret name becomes a silent default.
 */
public suspend fun SecretsManager.getSecretString(
    secretId: String,
    versionStage: String? = null,
): String? {
    val response = getSecretValue(GetSecretValueRequest(secretId, versionStage = versionStage))
    return response.secretString ?: response.secretBinary?.decodeToString()
}

/**
 * Writes a new `AWSCURRENT` version of a secret from a string.
 *
 * @return the new version's id.
 */
public suspend fun SecretsManager.putSecretString(secretId: String, value: String): String? =
    putSecretValue(PutSecretValueRequest(secretId, secretString = value)).versionId

/** `BatchGetSecretValue` accepts at most this many ids per request. Above it, AWS returns a 400. */
private const val BATCH_GET_MAX_IDS = 20

/**
 * Reads many secrets, chunked, paginated, and **complete or not at all**.
 *
 * ### What this fixes
 *
 * [SecretsManager.batchGetSecretValue] is one raw call with two failure modes that do not look like
 * failures:
 *
 * - it rejects the whole request above **20** ids, so a twenty-first secret is a runtime 400; and
 * - it reports per-secret failures **inside an HTTP 200** — see [BatchGetSecretValueResponse] —
 *   and pages its results, so the natural `response.secretValues` is a list that may be short for
 *   two unrelated reasons, neither of which raises anything.
 *
 * A process reading its own configuration cannot use a maybe-short list. This returns every
 * requested secret or throws, which is the only contract that makes the result safe to index into.
 *
 * ### Ordering
 *
 * The returned list is in **request order**, not response order. Secrets Manager is under no
 * obligation to answer in the order asked, and pagination means a single id's answer can arrive in
 * any page; reordering here is what lets a caller zip the result against the ids it asked for.
 *
 * Entries are matched back to requested ids by [SecretValueEntry.arn] and [SecretValueEntry.name],
 * since an id may be given as either. An id given as a **partial ARN** matches on neither and is
 * therefore reported in [BatchGetSecretValuePartialFailureException.missingSecretIds] rather than
 * being silently dropped — one more reason [GetSecretValueRequest] recommends full ARNs or names.
 *
 * ### Duplicates
 *
 * Duplicate ids in [secretIds] are read once and returned once, at the position of the first
 * occurrence. Secrets Manager rejects a request whose id list repeats an entry, and forwarding the
 * duplicate merely to reproduce that rejection helps nobody.
 *
 * @param secretIds the secrets to read, by name or full ARN.
 * @param maxPagesPerChunk a bound on pagination per chunk of 20, so a `NextToken` that never
 *   clears cannot loop forever. Twenty pages of a twenty-id chunk is already far past anything the
 *   service should need.
 * @return one entry per **distinct** requested id, in request order.
 * @throws BatchGetSecretValuePartialFailureException if any requested secret was not returned.
 */
public suspend fun SecretsManager.getSecretValues(
    secretIds: List<String>,
    maxPagesPerChunk: Int = 20,
): List<SecretValueEntry> {
    val distinctIds = secretIds.distinct()
    if (distinctIds.isEmpty()) return emptyList()

    val resolved = mutableListOf<SecretValueEntry>()
    val errors = mutableListOf<ApiError>()

    for (chunk in distinctIds.chunked(BATCH_GET_MAX_IDS)) {
        var nextToken: String? = null
        var page = 0
        do {
            val response = batchGetSecretValue(
                BatchGetSecretValueRequest(secretIdList = chunk, nextToken = nextToken),
            )
            resolved += response.secretValues.orEmpty()
            errors += response.errors.orEmpty()
            nextToken = response.nextToken
            page++
            // A token that keeps coming back with nothing attached is the shape a pagination bug
            // takes, and an unbounded `while` on it is an infinite loop inside somebody's cold
            // start. Stopping leaves the chunk short, which the accounting below reports.
        } while (nextToken != null && page < maxPagesPerChunk)
    }

    // Indexed by both, because a caller may have asked by either — see the KDoc.
    val byIdentifier = buildMap {
        resolved.forEach { entry ->
            entry.arn?.let { put(it, entry) }
            entry.name?.let { put(it, entry) }
        }
    }
    val failedIds = errors.mapNotNull { it.secretId }.toSet()
    val ordered = distinctIds.mapNotNull { byIdentifier[it] }
    val missing = distinctIds.filter { it !in byIdentifier && it !in failedIds }

    if (errors.isNotEmpty() || missing.isNotEmpty()) {
        throw BatchGetSecretValuePartialFailureException(
            resolved = ordered,
            errors = errors.toList(),
            missingSecretIds = missing,
            message = buildString {
                append("BatchGetSecretValue resolved ${ordered.size} of ${distinctIds.size} secrets")
                if (errors.isNotEmpty()) {
                    append("; ${errors.size} failed (")
                    append(errors.joinToString { "${it.secretId}: ${it.errorCode}" })
                    append(')')
                }
                if (missing.isNotEmpty()) {
                    append("; ${missing.size} were neither returned nor reported as errors (")
                    append(missing.joinToString())
                    append(") — check for partial ARNs, which cannot be matched back to a response")
                }
                append('.')
            },
        )
    }
    return ordered
}
