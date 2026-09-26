package com.steamstreet.awskt.sts

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.parseAwsCredentialExpirationOrNull
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient

/** STS's **query protocol** dialect: form-encoded requests, XML responses. See `Wire.kt`. */
public val STS_PROTOCOL: AwsProtocol = AwsProtocol.awsQuery(endpointPrefix = "sts")

/** The API version every query-protocol request must name; omitting it is `MissingParameter`. */
internal const val STS_API_VERSION: String = "2011-06-15"

/**
 * An STS client: `AssumeRole` and `GetCallerIdentity`.
 *
 * Most callers want [AssumeRoleCredentialsProvider] rather than this interface directly: it calls
 * [assumeRole], caches the result and refreshes it before it expires, and plugs into any awskt
 * client as its `credentialsProvider`.
 *
 * ### Always the regional endpoint
 *
 * Requests go to `sts.<region>.amazonaws.com`, never the legacy global `sts.amazonaws.com`. Regional
 * endpoints are what AWS recommends, they keep the call inside the Lambda's region, and the
 * credentials they issue are valid in every region the account has enabled. The cost is
 * [RegionDisabledException] in a region where the account has deactivated STS.
 *
 * ### Extending it
 *
 * [client] is the extension seam, as in `aws-sns`: an added operation builds its own form body,
 * calls `client.callRaw("POST", body = …)` and reads the XML back.
 */
public interface Sts : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Assumes [AssumeRoleRequest.roleArn] and returns temporary credentials for it.
     *
     * `IDEMPOTENT` for retry purposes: a replay issues a second set of credentials for the same
     * role, which is harmless — nothing is created that outlives the session.
     */
    public suspend fun assumeRole(request: AssumeRoleRequest): AssumeRoleResponse

    /**
     * Reports who the credentials this client signs with belong to.
     *
     * Needs no IAM permission — an explicit deny cannot block it — so it is the check to reach for
     * when working out *which* principal a process is actually running as.
     */
    public suspend fun getCallerIdentity(): CallerIdentity
}

/** Configuration for [Sts]. */
public class StsConfig {
    public var region: String? = null
    public var endpointUrl: String? = null

    /**
     * The credentials that call STS — the principal doing the assuming. Null uses
     * [defaultCredentialsProvider]; see [AssumeRoleCredentialsProvider] for why the provider it
     * builds does not.
     */
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here. Supplying one **bypasses [caInfo] and
     * [httpTimeouts]**, which are arguments to the client this factory would have built.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /** Per-attempt time limits for the client this factory builds. Ignored when [httpClient] is set. */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /** Notified of every attempt, retry decision and give-up. Null means no instrumentation. */
    public var observer: AwsCallObserver? = null
}

/** Builds an STS client. */
public fun Sts(configure: StsConfig.() -> Unit = {}): Sts = buildSts(StsConfig().apply(configure))

/**
 * Builds from a finished config, with [fallbackCredentials] standing in for an unset
 * [StsConfig.credentialsProvider]. [AssumeRoleCredentialsProvider] passes its own fallback here.
 */
internal fun buildSts(
    config: StsConfig,
    fallbackCredentials: () -> AwsCredentialsProvider = ::defaultCredentialsProvider,
): Sts {
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultSts(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: fallbackCredentials(),
            endpoint = resolveEndpoint("sts", region, config.endpointUrl),
            region = region,
            protocol = STS_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultSts(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : Sts {

    override suspend fun assumeRole(request: AssumeRoleRequest): AssumeRoleResponse {
        val body = FormBody()
            .put("Action", "AssumeRole")
            .put("Version", STS_API_VERSION)
            .put("RoleArn", request.roleArn)
            .put("RoleSessionName", request.roleSessionName)
            .put("DurationSeconds", request.duration?.inWholeSeconds)
            .put("ExternalId", request.externalId)
            .put("Policy", request.policy)
            .put("SourceIdentity", request.sourceIdentity)
        body.putMembers("PolicyArns", request.policyArns, field = "arn")
        body.putTags(request.tags)
        body.putMembers("TransitiveTagKeys", request.transitiveTagKeys)

        val xml = mapErrors {
            client.callRaw(
                method = "POST",
                body = body.encode(),
                operation = "AssumeRole",
                safety = OperationSafety.IDEMPOTENT,
            )
        }.body.decodeToString()

        return parseAssumeRoleResponse(xml)
    }

    override suspend fun getCallerIdentity(): CallerIdentity {
        val body = FormBody()
            .put("Action", "GetCallerIdentity")
            .put("Version", STS_API_VERSION)
            .encode()

        val xml = mapErrors {
            client.callRaw(
                method = "POST",
                body = body,
                operation = "GetCallerIdentity",
                safety = OperationSafety.IDEMPOTENT,
            )
        }.body.decodeToString()

        return CallerIdentity(
            account = requireField(xml, "Account", "GetCallerIdentity"),
            arn = requireField(xml, "Arn", "GetCallerIdentity"),
            userId = requireField(xml, "UserId", "GetCallerIdentity"),
        )
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

/**
 * Reads an `AssumeRole` response.
 *
 * Each block is scoped before its fields are read: `Arn` appears under `AssumedRoleUser`, and a scan
 * over the whole document would be one refactor away from reading the wrong one.
 */
internal fun parseAssumeRoleResponse(xml: String): AssumeRoleResponse {
    val credentials = Xml.block(xml, "Credentials")
        ?: throw malformed("AssumeRole", "Credentials")
    val user = Xml.block(xml, "AssumedRoleUser")
    return AssumeRoleResponse(
        credentials = AwsCredentials(
            accessKeyId = requireField(credentials, "AccessKeyId", "AssumeRole"),
            secretAccessKey = requireField(credentials, "SecretAccessKey", "AssumeRole"),
            sessionToken = requireField(credentials, "SessionToken", "AssumeRole"),
            // A null here degrades to "unknown expiry", which the credential cache bounds with its
            // own ceiling — see AssumeRoleCredentialsProvider. It never means "never expires".
            expiresAtEpochMillis = Xml.text(credentials, "Expiration")
                ?.let(::parseAwsCredentialExpirationOrNull),
        ),
        assumedRoleUser = user?.let {
            AssumedRoleUser(
                arn = requireField(it, "Arn", "AssumeRole"),
                assumedRoleId = requireField(it, "AssumedRoleId", "AssumeRole"),
            )
        },
        packedPolicySize = Xml.text(xml, "PackedPolicySize")?.toIntOrNull(),
        sourceIdentity = Xml.text(xml, "SourceIdentity"),
    )
}

/**
 * A field a 200 response cannot be without.
 *
 * Throws rather than returning null, unlike `aws-sns`'s reader: a credential with a blank secret
 * would fail every request it signs with a signature mismatch, far from the response that caused it.
 */
private fun requireField(xml: String, tag: String, operation: String): String =
    Xml.text(xml, tag)?.takeIf { it.isNotEmpty() } ?: throw malformed(operation, tag)

private fun malformed(operation: String, tag: String): StsException =
    StsException(null, "$operation answered 200 without <$tag>; the response could not be read", 200)
