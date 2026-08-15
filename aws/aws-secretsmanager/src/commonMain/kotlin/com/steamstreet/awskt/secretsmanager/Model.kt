package com.steamstreet.awskt.secretsmanager

import com.steamstreet.awskt.core.Base64BlobSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request and response types for Secrets Manager's data plane.
 *
 * ### Two rules that are not style preferences
 *
 * **Nothing that carries secret material is a `data class`.** `aws-s3` states the rule for
 * `ByteArray` — a generated `copy()`, `componentN()` and `toString()` all expose the array — and
 * here it extends to `SecretString`, which is an ordinary `String` and every bit as dangerous. A
 * `data class` would print the secret in full from any `logger.info("$response")`, any test
 * failure message, and any exception whose message interpolates the request. Those types get
 * hand-written `equals`/`hashCode` and a `toString()` that reports *presence*, never content.
 * Types holding only identifiers are ordinary data classes and gain `copy()` legitimately.
 *
 * **A `null` [GetSecretValueResponse.secretString] does not mean "no secret".** It means the
 * secret is stored as binary and is in [GetSecretValueResponse.secretBinary] instead. Exactly one
 * of the two is populated, decided by how the secret was written, and a caller that reads only the
 * string form fails on the first secret somebody happens to store as a blob.
 *
 * ### `CreatedDate` is a raw epoch-seconds `Double`
 *
 * `aws-s3` states the equivalent rule for its IMF-fixdate headers: date-shaped values are passed
 * through unparsed, so no parse failure can fail a response. AWS-JSON encodes timestamps as
 * epoch seconds with a fractional part, which arrives as a `Double`; converting it needs a
 * multiplication rather than a parser, so [GetSecretValueResponse.createdDateEpochMillis] is
 * offered as a computed property and the wire value is kept beside it.
 */

// -- GetSecretValue ------------------------------------------------------------------------------

/**
 * `GetSecretValue`.
 *
 * @param secretId the secret's name or its ARN. A **partial** ARN — one without the six-character
 *   random suffix Secrets Manager appends — works, but AWS warns it can match the wrong secret if
 *   another secret's name is a prefix of this one. Prefer the name or the full ARN.
 * @param versionId a specific version. Mutually exclusive with [versionStage]; setting both is an
 *   `InvalidParameterException` unless they name the same version.
 * @param versionStage defaults to `AWSCURRENT` when neither this nor [versionId] is set. During a
 *   rotation `AWSPENDING` is the new value that has not been promoted yet, and `AWSPREVIOUS` is the
 *   one it replaced — which is the version a caller holding a stale connection may still need.
 */
@Serializable
public data class GetSecretValueRequest(
    @SerialName("SecretId") public val secretId: String,
    @SerialName("VersionId") public val versionId: String? = null,
    @SerialName("VersionStage") public val versionStage: String? = null,
)

/**
 * `GetSecretValue`'s result. **Not a data class** — it carries the secret.
 *
 * @property secretString the secret, when it was stored as text. Null when it was stored as binary.
 * @property secretBinary the secret, when it was stored as binary. Null when it was stored as text.
 * @property createdDate epoch **seconds**, with a fractional part, exactly as AWS sent it. See
 *   [createdDateEpochMillis].
 */
@Serializable
public class GetSecretValueResponse(
    @SerialName("SecretString") public val secretString: String? = null,
    @SerialName("SecretBinary") @Serializable(with = Base64BlobSerializer::class)
    public val secretBinary: ByteArray? = null,
    @SerialName("ARN") public val arn: String? = null,
    @SerialName("Name") public val name: String? = null,
    @SerialName("VersionId") public val versionId: String? = null,
    @SerialName("VersionStages") public val versionStages: List<String>? = null,
    @SerialName("CreatedDate") public val createdDate: Double? = null,
) {
    /** [createdDate] as epoch milliseconds, or null when AWS sent no date. */
    public val createdDateEpochMillis: Long? get() = createdDate?.let { (it * 1000).toLong() }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is GetSecretValueResponse &&
                secretString == other.secretString &&
                secretBinary.contentEquals(other.secretBinary) &&
                arn == other.arn &&
                name == other.name &&
                versionId == other.versionId &&
                versionStages == other.versionStages &&
                createdDate == other.createdDate
            )

    override fun hashCode(): Int {
        var result = secretString?.hashCode() ?: 0
        result = 31 * result + (secretBinary?.contentHashCode() ?: 0)
        result = 31 * result + (arn?.hashCode() ?: 0)
        result = 31 * result + (versionId?.hashCode() ?: 0)
        return result
    }

    /** Reports whether a secret is present and never what it is — see the file KDoc. */
    override fun toString(): String =
        "GetSecretValueResponse(name=$name, arn=$arn, versionId=$versionId, " +
            "versionStages=$versionStages, secretString=${redact(secretString != null)}, " +
            "secretBinary=${redact(secretBinary != null)})"
}

// -- BatchGetSecretValue -------------------------------------------------------------------------

/**
 * `BatchGetSecretValue`.
 *
 * Exactly one of [secretIdList] and [filters] may be set. [getSecretValues] wraps the id-list form,
 * which is the one worth using from a Lambda's cold start; the filter form is a discovery API and
 * returns whatever currently matches, which is not a thing to start a process against.
 *
 * @param secretIdList at most **20** ids. [getSecretValues] chunks to that bound.
 * @param maxResults 1 to 20. Independent of [secretIdList]'s length: a request may be answered
 *   across several pages even when every id fits in one request.
 */
@Serializable
public data class BatchGetSecretValueRequest(
    @SerialName("SecretIdList") public val secretIdList: List<String>? = null,
    @SerialName("Filters") public val filters: List<SecretFilter>? = null,
    @SerialName("MaxResults") public val maxResults: Int? = null,
    @SerialName("NextToken") public val nextToken: String? = null,
)

/** A `BatchGetSecretValue` filter. `key` is one of `name`, `description`, `tag-key`, `tag-value`, `primary-region`, `all`. */
@Serializable
public data class SecretFilter(
    @SerialName("Key") public val key: String? = null,
    @SerialName("Values") public val values: List<String>? = null,
)

/**
 * `BatchGetSecretValue`'s result.
 *
 * ### [errors] can be non-empty on an HTTP 200
 *
 * This is the same trap as EventBridge's `PutEvents` and DynamoDB's `UnprocessedKeys`: a secret
 * that could not be read — it does not exist, this principal cannot decrypt it, KMS refused —
 * is reported *per entry inside a successful response*, not as a status code. A caller that checks
 * only the HTTP result and reads [secretValues] gets a **short list** and no indication that it is
 * short, which for a process loading its own configuration means starting up with a missing
 * credential and discovering it at the first use.
 *
 * [getSecretValues] exists so this does not have to be remembered: it returns only when every
 * requested id was resolved, and raises [BatchGetSecretValuePartialFailureException] otherwise.
 *
 * A data class despite the secrets, and legitimately so: its `toString()` delegates to
 * [SecretValueEntry.toString], which redacts. Nothing secret is reachable from the generated
 * members that is not already redacted by the type that holds it.
 */
@Serializable
public data class BatchGetSecretValueResponse(
    @SerialName("SecretValues") public val secretValues: List<SecretValueEntry>? = null,
    @SerialName("Errors") public val errors: List<ApiError>? = null,
    @SerialName("NextToken") public val nextToken: String? = null,
)

/**
 * One secret in a [BatchGetSecretValueResponse]. **Not a data class** — it carries the secret.
 *
 * Structurally the same as [GetSecretValueResponse] and deliberately a separate type: they are
 * different shapes on the wire (this one is a member of a list, that one is a whole response body)
 * and collapsing them would mean one of the two carrying fields the other never populates.
 */
@Serializable
public class SecretValueEntry(
    @SerialName("SecretString") public val secretString: String? = null,
    @SerialName("SecretBinary") @Serializable(with = Base64BlobSerializer::class)
    public val secretBinary: ByteArray? = null,
    @SerialName("ARN") public val arn: String? = null,
    @SerialName("Name") public val name: String? = null,
    @SerialName("VersionId") public val versionId: String? = null,
    @SerialName("VersionStages") public val versionStages: List<String>? = null,
    @SerialName("CreatedDate") public val createdDate: Double? = null,
) {
    /** [createdDate] as epoch milliseconds, or null when AWS sent no date. */
    public val createdDateEpochMillis: Long? get() = createdDate?.let { (it * 1000).toLong() }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SecretValueEntry &&
                secretString == other.secretString &&
                secretBinary.contentEquals(other.secretBinary) &&
                arn == other.arn &&
                name == other.name &&
                versionId == other.versionId &&
                versionStages == other.versionStages &&
                createdDate == other.createdDate
            )

    override fun hashCode(): Int {
        var result = secretString?.hashCode() ?: 0
        result = 31 * result + (secretBinary?.contentHashCode() ?: 0)
        result = 31 * result + (arn?.hashCode() ?: 0)
        result = 31 * result + (versionId?.hashCode() ?: 0)
        return result
    }

    /** Reports whether a secret is present and never what it is — see the file KDoc. */
    override fun toString(): String =
        "SecretValueEntry(name=$name, arn=$arn, versionId=$versionId, " +
            "secretString=${redact(secretString != null)}, secretBinary=${redact(secretBinary != null)})"
}

/**
 * One secret that could not be read, from [BatchGetSecretValueResponse.errors].
 *
 * [errorCode] is the error the single-secret call would have thrown — `ResourceNotFoundException`,
 * `AccessDeniedException`, `DecryptionFailure`, `InternalServiceError` — arriving as data instead,
 * because the rest of the batch succeeded.
 */
@Serializable
public data class ApiError(
    @SerialName("SecretId") public val secretId: String? = null,
    @SerialName("ErrorCode") public val errorCode: String? = null,
    @SerialName("Message") public val message: String? = null,
)

// -- PutSecretValue ------------------------------------------------------------------------------

/**
 * `PutSecretValue`: writes a **new version** of an existing secret. **Not a data class** — it
 * carries the secret.
 *
 * It does not create secrets and it does not overwrite the old version. The previous `AWSCURRENT`
 * becomes `AWSPREVIOUS`, and Secrets Manager keeps the versions it is holding stages for — which is
 * what makes a rotation reversible and what makes writing a secret on every deployment a slow leak
 * of versions.
 *
 * Exactly one of [secretString] and [secretBinary] may be set.
 *
 * @param clientRequestToken the idempotency token. **Leave it null**: [SecretsManager.putSecretValue]
 *   generates one per call and reuses it across every retry of that call, which is what makes the
 *   operation safe to replay. Set it explicitly only when the idempotency has to span something
 *   larger than one call — a workflow step that may itself be re-executed — and then keep it stable
 *   across those executions, because that is the entire point of supplying your own.
 * @param versionStages defaults to `["AWSCURRENT"]`, which promotes the new version immediately. A
 *   rotation writes `["AWSPENDING"]` here instead, tests the new value, and moves the stage
 *   afterwards with `UpdateSecretVersionStage` — a control-plane call reachable through the
 *   extension seam.
 */
public class PutSecretValueRequest(
    public val secretId: String,
    public val secretString: String? = null,
    public val secretBinary: ByteArray? = null,
    public val versionStages: List<String>? = null,
    public val clientRequestToken: String? = null,
) {
    internal fun withClientRequestToken(token: String): PutSecretValueRequest =
        PutSecretValueRequest(secretId, secretString, secretBinary, versionStages, token)

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PutSecretValueRequest &&
                secretId == other.secretId &&
                secretString == other.secretString &&
                secretBinary.contentEquals(other.secretBinary) &&
                versionStages == other.versionStages &&
                clientRequestToken == other.clientRequestToken
            )

    override fun hashCode(): Int {
        var result = secretId.hashCode()
        result = 31 * result + (secretString?.hashCode() ?: 0)
        result = 31 * result + (secretBinary?.contentHashCode() ?: 0)
        result = 31 * result + (clientRequestToken?.hashCode() ?: 0)
        return result
    }

    /** Reports whether a secret is present and never what it is — see the file KDoc. */
    override fun toString(): String =
        "PutSecretValueRequest(secretId=$secretId, versionStages=$versionStages, " +
            "clientRequestToken=$clientRequestToken, secretString=${redact(secretString != null)}, " +
            "secretBinary=${redact(secretBinary != null)})"
}

/**
 * The wire form of [PutSecretValueRequest].
 *
 * Separate because the public type is not a `data class` — it holds a secret — while the
 * serializable form wants to be one, and because the public type deliberately does not expose a
 * `copy()` that would let a caller clone a secret by accident. The conversion is one function and
 * it is the only place the two shapes have to agree.
 */
@Serializable
internal data class PutSecretValueWire(
    @SerialName("SecretId") val secretId: String,
    @SerialName("ClientRequestToken") val clientRequestToken: String? = null,
    @SerialName("SecretString") val secretString: String? = null,
    @SerialName("SecretBinary") @Serializable(with = Base64BlobSerializer::class)
    val secretBinary: ByteArray? = null,
    @SerialName("VersionStages") val versionStages: List<String>? = null,
)

internal fun PutSecretValueRequest.toWire(): PutSecretValueWire = PutSecretValueWire(
    secretId = secretId,
    clientRequestToken = clientRequestToken,
    secretString = secretString,
    secretBinary = secretBinary,
    versionStages = versionStages,
)

/** `PutSecretValue`'s result. A data class — it carries only identifiers. */
@Serializable
public data class PutSecretValueResponse(
    @SerialName("ARN") public val arn: String? = null,
    @SerialName("Name") public val name: String? = null,
    @SerialName("VersionId") public val versionId: String? = null,
    @SerialName("VersionStages") public val versionStages: List<String>? = null,
)

// -- Shared helpers ------------------------------------------------------------------------------

/**
 * Renders a secret-bearing field's *presence*.
 *
 * Not the length: a length is a small leak with no upside here — it narrows a brute-force search
 * over a password and tells a reader nothing they can act on. `aws-s3` and `aws-kms` do print byte
 * counts, and that is the right call there, where the bytes are ciphertext or an opaque payload
 * whose size is the useful diagnostic. A Secrets Manager secret is the plaintext itself.
 */
private fun redact(present: Boolean): String = if (present) "<redacted>" else "null"
