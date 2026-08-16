package com.steamstreet.awskt.ses

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.callRestJson
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient

/**
 * SES v2's **restJson1** dialect.
 *
 * The endpoint prefix is `email` and the **signing name is `ses`** — they differ, so both are named
 * explicitly, exactly as in `aws-bedrock-runtime`. Signing against `email` produces a
 * `SignatureDoesNotMatch` on every call, and the error says nothing about which of the two is wrong.
 *
 * The prefix also drives the endpoint override variable `aws-core` derives from it, so a local
 * override is `AWS_ENDPOINT_URL_EMAIL` rather than `..._SES`.
 *
 * **This is SES v2, not SES v1.** The old `ses` API is the AWS query protocol answering XML at
 * `POST /`, which is a different wire format reached through the same host; nothing here speaks it.
 */
public val SES_PROTOCOL: AwsProtocol = AwsProtocol.restJson1(endpointPrefix = "email", signingName = "ses")

/**
 * An Amazon SES v2 client.
 *
 * ### Scope: `SendEmail` with simple content, and nothing else yet
 *
 * SES v2 has some ninety operations, nearly all of which manage provisioned things — identities,
 * configuration sets, dedicated IPs, contact lists, templates, suppression entries. Those belong to
 * whatever provisions the account, not to a request handler, and this module draws the same line
 * `aws-scheduler` and `aws-kms` draw: the operation an application performs at runtime is in scope
 * and the account's configuration is not.
 *
 * That leaves the sending operations, and of those, [sendEmail] with a [Message] is the one shipped:
 *
 * - **`Raw` content** — a caller-assembled MIME blob — and **`Simple.Attachments`** are the two
 *   places SES v2 takes a blob. Both need a base64 `KSerializer` and neither has a consumer here.
 * - **`Template` content** and `SendBulkEmail` need a template, which is a provisioned resource by
 *   the line above.
 *
 * All of them remain reachable through the extension seam below, and adding `Raw` or `Template` to
 * [EmailContent] later is additive rather than breaking.
 *
 * ### Two things worth knowing before you use it
 *
 * 1. **A send is not idempotent and this client will not replay one.** See [sendEmail].
 * 2. **A new account is in the SES sandbox**, where every *recipient* must be a verified identity,
 *    not only the sender. Mail to a real user fails with [MessageRejected] until AWS grants the
 *    account production access. That is an account state rather than a code path, and it is the
 *    first thing to check when a correct-looking integration cannot mail anyone.
 *
 * ### Extending it
 *
 * [client] is public and [sendEmail] has no privileged access to it. Note that a restJson1 seam
 * needs an explicit method and path:
 *
 * ```kotlin
 * @Serializable
 * data class GetAccountResponse(@SerialName("SendingEnabled") val sendingEnabled: Boolean? = null)
 *
 * suspend fun Ses.getAccount(): GetAccountResponse =
 *     client.callRestJsonNoBody(
 *         method = "GET",
 *         path = "/v2/email/account",
 *         responseSerializer = GetAccountResponse.serializer(),
 *         operation = "GetAccount",
 *     )
 * ```
 *
 * **Encode interpolated path segments with `sigV4UriEncode`** — `callRestJson` signs and sends the
 * path byte-for-byte as given, so a raw identity or template name is a signature mismatch at best.
 * No operation in this module interpolates anything, which is why it does not import it.
 */
public interface Ses : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Sends one email. `POST /v2/email/outbound-emails`.
     *
     * ### `NOT_IDEMPOTENT`, and this is the design decision in the module
     *
     * SES v2's `SendEmail` **has no client token**. Every other write in this library that is marked
     * `IDEMPOTENT` earns it by carrying one — `aws-scheduler`'s `CreateSchedule`,
     * `aws-secretsmanager`'s `PutSecretValue`, `aws-dynamodb`'s `TransactWriteItems` all mint a
     * token before the first attempt and reuse it across retries, so the service recognises a
     * replay. SES offers nothing to recognise a replay *with*: two identical `SendEmail` calls are
     * two emails, and the recipient sees both.
     *
     * So this is the one operation here that declines a retry it could have taken. Concretely, under
     * [OperationSafety.NOT_IDEMPOTENT] `aws-core` refuses to replay an **ambiguous transport
     * failure** — the request went out and the socket died before an answer came back — which is
     * exactly the case that duplicates a sign-in code in someone's inbox. `RetryConfig
     * .retryAmbiguousWrites` opts back in, and for transactional mail it should not be set.
     *
     * **What this does not stop**, stated plainly because the flag's name oversells it: a *service*
     * error is still retried. A 500, a 429, a [LimitExceededException] — those are answers, not
     * ambiguity, and by SES's contract an answer that is not a 200 did not accept the message. That
     * is the same bet every AWS SDK makes, and the narrow case `safety` exists to refuse is the one
     * where nobody can tell what happened.
     *
     * @throws MessageRejected most often, and most often because the account is still in the SES
     *   sandbox — see the interface KDoc.
     * @throws BadRequestException if the request is structurally invalid.
     * @throws SendingPausedException or [AccountSuspendedException] if the account cannot send at
     *   all. Neither is fixed by retrying.
     */
    public suspend fun sendEmail(request: SendEmailRequest): SendEmailResponse
}

/** Configuration for [Ses]. */
public class SesConfig {
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

    /**
     * Retry pacing.
     *
     * **Leave `retryAmbiguousWrites` off.** It is off by default, and turning it on for this client
     * specifically means opting into duplicate sends — see [Ses.sendEmail], which is the only
     * operation here and is the case that flag was written to protect.
     */
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits for the client this factory builds. Ignored when [httpClient] is set.
     *
     * The library defaults suit SES: a `SendEmail` request is a small JSON document and the call
     * returns as soon as SES has *accepted* the message. Delivery happens afterwards and takes as
     * long as it takes, with nothing here waiting on it — so a slow inbox is never a slow call, and
     * these do not need widening for one.
     *
     * A large HTML body is the one thing that moves the request size, and even a generous one is a
     * few tens of kilobytes.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /** Notified of every attempt, retry decision and give-up. Null means no instrumentation. */
    public var observer: AwsCallObserver? = null
}

/** Builds an Amazon SES v2 client. */
public fun Ses(configure: SesConfig.() -> Unit = {}): Ses {
    val config = SesConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultSes(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            // The endpoint prefix is `email`, not `ses` — see SES_PROTOCOL. Passing the signing name
            // here would resolve `https://ses.<region>.amazonaws.com`, which does not exist.
            endpoint = resolveEndpoint("email", region, config.endpointUrl),
            region = region,
            protocol = SES_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultSes(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : Ses {

    override suspend fun sendEmail(request: SendEmailRequest): SendEmailResponse = mapErrors {
        client.callRestJson(
            method = "POST",
            // A constant, and the only path this module uses. Nothing is interpolated into it, so
            // there is nothing here to percent-encode.
            path = SEND_EMAIL_PATH,
            request = request,
            requestSerializer = SendEmailRequest.serializer(),
            responseSerializer = SendEmailResponse.serializer(),
            operation = "SendEmail",
            // The whole reason this is not the defaulted IDEMPOTENT. See Ses.sendEmail's KDoc: SES
            // has no client token, so a replayed send is a second email in somebody's inbox.
            safety = OperationSafety.NOT_IDEMPOTENT,
        )
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

/**
 * `SendEmail`'s path.
 *
 * The `/v2/` is part of the URI, not a header or a query parameter, and it is the whole of what
 * distinguishes this from SES v1 on the wire — see [SES_PROTOCOL].
 */
internal const val SEND_EMAIL_PATH: String = "/v2/email/outbound-emails"

// -- Conveniences --------------------------------------------------------------------------------

/**
 * Sends a simple message, without building four nested objects to say so.
 *
 * The shape transactional mail actually takes, and the counterpart to `aws-scheduler`'s
 * `scheduleOnce`: [SendEmailRequest] models what SES accepts, and this models what a sign-in code,
 * a receipt or a password reset needs.
 *
 * @param from the `From` address. Display-name form (`Acme <no-reply@example.com>`) is accepted here
 *   and is usually what you want — but see [SendEmailRequest.fromEmailAddress], because the verified
 *   identity and any IAM resource ARN naming it are the **bare** address.
 * @param text the plain-text body. Sending both this and [html] is the norm — see [Body].
 * @param charset applied to the subject and to both body parts. Defaults to `UTF-8` rather than to
 *   null, which is the one place this function deliberately differs from the API underneath it: SES
 *   falls back to 7-bit ASCII when the charset is absent, and a subject line containing a curly
 *   quote or an em dash — which is to say, a subject line written by a person — arrives mangled.
 *   Pass null for the API's own default.
 * @throws IllegalArgumentException if [to] is empty, or if neither [text] nor [html] is given. Both
 *   are `BadRequestException` from SES; failing here names the actual problem and costs no round
 *   trip.
 */
public suspend fun Ses.sendSimpleEmail(
    from: String,
    to: List<String>,
    subject: String,
    text: String? = null,
    html: String? = null,
    replyTo: List<String>? = null,
    configurationSetName: String? = null,
    charset: String? = "UTF-8",
): SendEmailResponse {
    require(to.isNotEmpty()) { "sendSimpleEmail needs at least one recipient in `to`." }
    require(text != null || html != null) {
        "sendSimpleEmail needs a `text` body, an `html` body, or both; got neither."
    }
    return sendEmail(
        SendEmailRequest(
            content = EmailContent(
                simple = Message(
                    subject = Content(subject, charset),
                    body = Body(
                        text = text?.let { Content(it, charset) },
                        html = html?.let { Content(it, charset) },
                    ),
                ),
            ),
            fromEmailAddress = from,
            destination = Destination(toAddresses = to),
            replyToAddresses = replyTo,
            configurationSetName = configurationSetName,
        ),
    )
}
