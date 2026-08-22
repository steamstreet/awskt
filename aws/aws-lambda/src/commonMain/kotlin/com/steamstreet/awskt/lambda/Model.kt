package com.steamstreet.awskt.lambda

import com.steamstreet.awskt.core.OperationSafety
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Request and response types for Lambda's invoke plane.
 *
 * ### Nothing that carries a `ByteArray` is a `data class`
 *
 * The rule is `aws-s3`'s and `aws-kms`'s, restated because every type here carries one: a generated
 * `copy()`, `componentN()` and `toString()` all expose the array — `toString()` by printing an
 * identity hash that reads like content, `equals` by comparing references so two identical payloads
 * compare unequal. Those types get hand-written `equals`/`hashCode` over
 * `contentEquals`/`contentHashCode`.
 *
 * ### A payload is bytes, not a `String`
 *
 * Lambda's `Payload` is a blob. It is *usually* UTF-8 JSON — the header this client sends says so —
 * but the service does not require it and a custom runtime may answer with anything. Decoding to a
 * `String` at the boundary would make that a lossy conversion for the callers who do not send JSON,
 * so the wire types carry bytes and the text is one `payloadText` away. The conveniences in
 * `Lambda.kt` take and return `String` for the common case.
 */

/**
 * How Lambda should run the invocation.
 *
 * @see InvokeRequest.invocationType
 */
public enum class InvocationType(internal val wire: String) {
    /**
     * Run it now and answer with the result. The default, and the only one that returns a payload.
     *
     * The whole invocation happens inside this HTTP request, so the request outlives the function:
     * a five-minute function means a five-minute response. See [LambdaConfig.httpTimeouts], which
     * is the setting people get wrong.
     */
    REQUEST_RESPONSE("RequestResponse"),

    /**
     * Queue it and answer `202` immediately, with no payload.
     *
     * **Lambda's own asynchronous retry behaviour begins here, and it is not this client's.** A
     * queued invocation that fails is retried twice by the service, hours apart if the function is
     * throttled, and then sent to the function's dead-letter target or on-failure destination if it
     * has one. So a `202` means "accepted", not "ran", and nothing this client can report tells the
     * caller which of those eventually happened.
     *
     * The payload ceiling is **256 KB** here rather than 6 MB — see [MAX_EVENT_PAYLOAD_BYTES].
     */
    EVENT("Event"),

    /**
     * Validate the parameters and the caller's permissions without running anything. Answers `204`.
     *
     * Useful for exactly one thing: proving at start-up that the role can invoke the function,
     * rather than discovering it on the first real request. It does **not** prove the function
     * works, and it does not warm it.
     */
    DRY_RUN("DryRun"),
}

/** Whether Lambda should return the tail of the invocation's logs. */
public enum class LogType(internal val wire: String) {
    /** No logs. */
    NONE("None"),

    /**
     * The **last 4 KB** of the log output, base64 in the `X-Amz-Log-Result` header.
     *
     * Only legal with [InvocationType.REQUEST_RESPONSE]; there are no logs to tail on an invocation
     * that has not run yet. Read it through [InvokeResponse.logTail], which decodes it.
     *
     * 4 KB is a *tail*, not a transcript: a chatty function's useful line is often already off the
     * top of it. It costs nothing extra and is worth setting on an error path.
     */
    TAIL("Tail"),
}

/**
 * The largest payload `RequestResponse` and `DryRun` accept: 6 MB.
 *
 * Checked locally by [Lambda.invoke] so an oversized request fails before it is uploaded rather
 * than after — see [LambdaPayloadTooLargeException]. The same 6 MB bounds the *response*, which is
 * why this client has nothing resembling `aws-s3`'s download ceiling: Lambda will not hand back
 * more than that.
 */
public const val MAX_SYNC_PAYLOAD_BYTES: Int = 6 * 1024 * 1024

/** The largest payload [InvocationType.EVENT] accepts: 256 KB. */
public const val MAX_EVENT_PAYLOAD_BYTES: Int = 256 * 1024

/** The largest `X-Amz-Client-Context` Lambda accepts, measured **after** base64 encoding. */
public const val MAX_CLIENT_CONTEXT_BYTES: Int = 3583

/**
 * One invocation. **Not a data class** — it carries bytes.
 *
 * @param functionName a bare name (`my-function`), a name with a version or alias
 *   (`my-function:PROD`), a partial ARN (`123456789012:function:my-function`) or a full ARN. It is
 *   percent-encoded into the path, so a `:` reaches Lambda as `%3A` and is decoded before the name
 *   is resolved — the same treatment `aws-bedrock-runtime` gives an inference-profile ARN.
 * @param payload the event, as bytes. Null sends an empty body, which a function receives as `{}`.
 * @param invocationType see [InvocationType]. Defaults to [InvocationType.REQUEST_RESPONSE].
 * @param logType see [LogType]. Ignored by Lambda unless the invocation type is
 *   [InvocationType.REQUEST_RESPONSE].
 * @param qualifier a version number or alias name, as an alternative to embedding it in
 *   [functionName]. Sent as the `Qualifier` query parameter. Supplying it in **both** places is a
 *   `ValidationException` from the service when the two disagree, so pick one.
 * @param clientContextJson **raw JSON, base64-encoded by this client** — pass
 *   `{"custom":{"trace":"abc"}}`, not its base64. Named for the contract because the AWS SDKs take
 *   this field already encoded and passing raw JSON to them fails at the service with a message
 *   that names nothing useful. Rejected locally if it exceeds [MAX_CLIENT_CONTEXT_BYTES] once
 *   encoded. The function reads it from its own runtime context, not from the event.
 * @param safety whether an ambiguous mid-flight failure may be replayed. **Defaults to
 *   [OperationSafety.NOT_IDEMPOTENT], and that default is the point**: if the request reached
 *   Lambda and the socket died before the answer came back, a retry runs the function a second
 *   time — charging a payment, sending an email, incrementing a counter. Set
 *   [OperationSafety.IDEMPOTENT] only when the *handler* is idempotent, which is a fact about
 *   code this client cannot see. Per-request rather than per-client for exactly that reason.
 *
 *   This governs only *ambiguous transport* failures. A `429` or a `500` from the service is
 *   classified and replayed by `aws-core` regardless — see the note on retries in [Lambda].
 *
 * @see OperationSafety
 */
public class InvokeRequest(
    public val functionName: String,
    public val payload: ByteArray? = null,
    public val invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    public val logType: LogType = LogType.NONE,
    public val qualifier: String? = null,
    public val clientContextJson: String? = null,
    public val safety: OperationSafety = OperationSafety.NOT_IDEMPOTENT,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is InvokeRequest &&
                functionName == other.functionName &&
                payload.contentEquals(other.payload) &&
                invocationType == other.invocationType &&
                logType == other.logType &&
                qualifier == other.qualifier &&
                clientContextJson == other.clientContextJson &&
                safety == other.safety
            )

    override fun hashCode(): Int {
        var result = functionName.hashCode()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        result = 31 * result + invocationType.hashCode()
        result = 31 * result + logType.hashCode()
        result = 31 * result + (qualifier?.hashCode() ?: 0)
        result = 31 * result + (clientContextJson?.hashCode() ?: 0)
        return result
    }

    /** Reports the payload's size and never its bytes — an event routinely carries personal data. */
    override fun toString(): String =
        "InvokeRequest(functionName=$functionName, payload=${payload?.size ?: 0} bytes, " +
            "invocationType=$invocationType, logType=$logType, qualifier=$qualifier)"
}

/**
 * What one invocation answered. **Not a data class** — it carries bytes.
 *
 * ### Read [functionError] before you read [payload]
 *
 * **A function that threw still answers `200`.** The HTTP status reports whether Lambda ran the
 * function, not whether the function worked; a handler that raised sets the
 * `X-Amz-Function-Error` header and puts its error object in the payload. So a caller that
 * deserializes [payload] into its expected result type and never looks at [functionError] treats
 * `{"errorMessage":"…","errorType":"…"}` as a result — which usually surfaces much later, as a
 * missing-field failure in unrelated code.
 *
 * This client returns the failure rather than throwing it, matching the AWS SDKs and leaving the
 * error payload readable. [orThrow] is the one-call opt-out, and the conveniences in `Lambda.kt`
 * apply it for you.
 *
 * @param statusCode the HTTP status: `200` for [InvocationType.REQUEST_RESPONSE], `202` for
 *   [InvocationType.EVENT], `204` for [InvocationType.DRY_RUN].
 * @param payload the function's answer, or its error object, or empty for a non-synchronous
 *   invocation.
 * @param functionError `"Handled"` when the runtime caught the error and reported it, `"Unhandled"`
 *   when the function crashed, timed out or ran out of memory. **Null is the only success value.**
 * @param executedVersion which published version actually ran, from `X-Amz-Executed-Version`. The
 *   answer to "which version does this alias point at *right now*", and worth logging when invoking
 *   through a weighted alias.
 * @param logResult the raw base64 of `X-Amz-Log-Result`, present only when [LogType.TAIL] was
 *   requested. Use [logTail].
 */
public class InvokeResponse(
    public val statusCode: Int,
    public val payload: ByteArray,
    public val functionError: String? = null,
    public val executedVersion: String? = null,
    public val logResult: String? = null,
) {
    /** True when the function itself failed. The invocation still succeeded — see the class KDoc. */
    public val isFunctionError: Boolean get() = functionError != null

    /** [payload] as UTF-8 text. Empty for an [InvocationType.EVENT] or [InvocationType.DRY_RUN]. */
    public val payloadText: String get() = payload.decodeToString()

    /**
     * [logResult] decoded, or null when no logs were requested — the last 4 KB of the invocation's
     * log output, newline-separated and including Lambda's own `START` / `END` / `REPORT` lines.
     *
     * Decoded on each access rather than stored, because it is at most 4 KB and is usually read
     * zero times. A header that is not valid base64 answers null rather than throwing: a
     * malformed log tail is not a reason to fail an invocation that otherwise worked.
     */
    public val logTail: String?
        get() = logResult?.let { encoded ->
            runCatching { Base64.Default.decode(encoded).decodeToString() }.getOrNull()
        }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is InvokeResponse &&
                statusCode == other.statusCode &&
                payload.contentEquals(other.payload) &&
                functionError == other.functionError &&
                executedVersion == other.executedVersion &&
                logResult == other.logResult
            )

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (functionError?.hashCode() ?: 0)
        result = 31 * result + (executedVersion?.hashCode() ?: 0)
        return result
    }

    /** Reports the payload's size and never its bytes. */
    override fun toString(): String =
        "InvokeResponse(statusCode=$statusCode, payload=${payload.size} bytes, " +
            "functionError=$functionError, executedVersion=$executedVersion)"
}

/**
 * The shape a managed Lambda runtime uses for an error payload.
 *
 * Every field is optional and unknown keys are ignored, because **this is a convention rather than
 * a contract**: the managed runtimes emit it, and a custom runtime — including one built on this
 * repository's `lambda-native` — returns whatever its handler wrote. Parsing is therefore always
 * best-effort, which is why [FunctionErrorException] keeps the raw payload alongside the parsed
 * fields.
 */
@Serializable
public data class FunctionErrorPayload(
    @SerialName("errorType") public val errorType: String? = null,
    @SerialName("errorMessage") public val errorMessage: String? = null,
    @SerialName("stackTrace") public val stackTrace: List<String>? = null,
    @SerialName("requestId") public val requestId: String? = null,
)

// -- Response streaming ---------------------------------------------------------------------------

/**
 * One event from [Lambda.invokeWithResponseStream].
 *
 * The stream is always at least one [Complete]: a function that streamed nothing still terminates,
 * and a function that *failed* reports it there rather than by failing the HTTP call.
 */
public sealed interface InvokeStreamEvent {
    /**
     * A slice of the function's response body. **Not necessarily a whole anything.**
     *
     * The boundaries are the ones the runtime happened to flush, so a chunk can split a JSON
     * document, a line, or a single multi-byte UTF-8 character. Concatenate before decoding —
     * [payloadChunks] gives you the bytes and deliberately does not offer a per-chunk `String`.
     */
    public class PayloadChunk(public val payload: ByteArray) : InvokeStreamEvent {
        override fun toString(): String = "PayloadChunk(${payload.size} bytes)"
    }

    /**
     * The stream ended. **[errorCode] is the only place a streaming invocation reports failure.**
     *
     * The HTTP status was `200` and chunks may already have been delivered — a function that
     * streamed half an answer and then threw produces exactly that — so there is no way to
     * un-deliver them and no exception the transport could have raised instead.
     *
     * @param errorCode null on success; otherwise the runtime's error type.
     * @param errorDetails the error payload as text, when the runtime supplied one.
     * @param logResult raw base64, as [InvokeResponse.logResult].
     */
    public class Complete(
        public val errorCode: String? = null,
        public val errorDetails: String? = null,
        public val logResult: String? = null,
    ) : InvokeStreamEvent {
        override fun toString(): String = "Complete(errorCode=$errorCode)"
    }
}

/** The JSON body of an `InvokeComplete` frame. */
@Serializable
internal data class InvokeCompleteBody(
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("ErrorDetails") val errorDetails: String? = null,
    @SerialName("LogResult") val logResult: String? = null,
)
