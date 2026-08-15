package com.steamstreet.awskt.kms

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
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import io.ktor.client.HttpClient

/**
 * KMS's AWS-JSON 1.1 dialect.
 *
 * The target prefix is **`TrentService`**, which is not a typo and not derivable from anything:
 * it is the service's original internal name, and it is what `X-Amz-Target` must say. The endpoint
 * and signing prefixes are the ordinary `kms`. All three are named explicitly here because two of
 * them agree and the interesting one does not.
 */
public val KMS_PROTOCOL: AwsProtocol =
    AwsProtocol.awsJson1_1(endpointPrefix = "kms", targetPrefix = "TrentService")

/**
 * A KMS client covering the **data plane**: the operations that use a key rather than manage one.
 *
 * Encrypt, decrypt, re-encrypt, mint data keys, draw randomness, sign, verify, and fetch the public
 * half of an asymmetric key so signatures can be verified without a KMS call. Key lifecycle —
 * `CreateKey`, `ScheduleKeyDeletion`, `CreateAlias`, `PutKeyPolicy`, `CreateGrant` — is out of
 * scope: those calls belong to whatever provisions the infrastructure, which for the Lambdas this
 * library exists to serve is CloudFormation or Terraform rather than the function's own runtime.
 * They are reachable through the extension seam below if a caller genuinely needs them.
 *
 * ### The two things worth knowing before you use it
 *
 * 1. **A failed signature verification is an exception, not a `false`.** See [verify] and
 *    [verifySignature].
 * 2. **`EncryptionContext` is authenticated, not encrypted.** It appears in CloudTrail in the
 *    clear. It is the right place for a tenant id and the wrong place for anything secret.
 *
 * ### Extending it
 *
 * As with `DynamoDb` and `EventBridge`, [client] is public and no operation below has privileged
 * access to it — each is a `callJson` on that same object. An operation this library does not ship
 * can be added downstream as an extension function with identical signing, retry and error
 * handling:
 *
 * ```kotlin
 * @Serializable
 * data class DescribeKeyRequest(@SerialName("KeyId") val keyId: String)
 *
 * @Serializable
 * data class DescribeKeyResponse(@SerialName("KeyMetadata") val keyMetadata: JsonObject? = null)
 *
 * suspend fun Kms.describeKey(keyId: String): DescribeKeyResponse =
 *     client.callJson(
 *         "DescribeKey",
 *         DescribeKeyRequest(keyId),
 *         DescribeKeyRequest.serializer(),
 *         DescribeKeyResponse.serializer(),
 *     )
 * ```
 *
 * The failures such an extension sees are `aws-core`'s [com.steamstreet.awskt.core.AwsServiceException],
 * not this module's [KmsException] — [mapErrors] is internal, and applying it from outside would
 * mean re-declaring the mapping. That is the same trade `aws-dynamodb` and `aws-eventbridge` make.
 */
public interface Kms : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Encrypts up to 4096 bytes directly under a KMS key.
     *
     * **4096 bytes is the whole limit**, and it is a plaintext limit rather than a request limit,
     * so it cannot be worked around by chunking without inventing a format. Anything larger wants
     * envelope encryption: [generateDataKey], encrypt locally with the plaintext key, store the
     * returned ciphertext blob beside the data.
     */
    public suspend fun encrypt(request: EncryptRequest): EncryptResponse

    /** Decrypts a blob produced by [encrypt], [generateDataKey] or [reEncrypt]. */
    public suspend fun decrypt(request: DecryptRequest): DecryptResponse

    /**
     * Moves a ciphertext to a different key without the plaintext leaving KMS.
     *
     * The operation a key rotation is built on: the data stays where it is and only the blob beside
     * it is rewritten.
     */
    public suspend fun reEncrypt(request: ReEncryptRequest): ReEncryptResponse

    /**
     * Mints a data key and returns it twice: once in the clear, once sealed under the KMS key.
     *
     * See [GenerateDataKeyResponse] for what to do with each half, and for why only one of them may
     * be written down.
     */
    public suspend fun generateDataKey(request: GenerateDataKeyRequest): GenerateDataKeyResponse

    /** Mints a data key and returns only the sealed copy. See [GenerateDataKeyWithoutPlaintextRequest]. */
    public suspend fun generateDataKeyWithoutPlaintext(
        request: GenerateDataKeyWithoutPlaintextRequest,
    ): GenerateDataKeyWithoutPlaintextResponse

    /** Draws random bytes from KMS's FIPS-validated source. See [GenerateRandomResponse] for when that is worth a round trip. */
    public suspend fun generateRandom(request: GenerateRandomRequest): GenerateRandomResponse

    /** Signs a message, or a digest of one, with an asymmetric key. */
    public suspend fun sign(request: SignRequest): SignResponse

    /**
     * Verifies a signature — **and throws [KmsInvalidSignatureException] when it does not match**.
     *
     * KMS reports a bad signature as an error response rather than as
     * [VerifyResponse.signatureValid] = `false`, so the flag on a returned response is always
     * `true` and branching on it is branching on a constant. This method exists in its literal
     * form because that is the operation KMS has; [verifySignature] is the one to call.
     *
     * ```kotlin
     * // Wrong: the false branch is unreachable, and a bad signature escapes as an exception.
     * if (kms.verify(request).signatureValid) accept() else reject()
     *
     * // Right:
     * if (kms.verifySignature(request)) accept() else reject()
     * ```
     */
    public suspend fun verify(request: VerifyRequest): VerifyResponse

    /**
     * Fetches the public half of an **asymmetric** key, as DER-encoded `SubjectPublicKeyInfo`.
     *
     * The operation that takes verification off the KMS bill: [verify] costs a KMS call per check
     * and requires `kms:Verify` on the key, whereas a public key fetched once can verify any number
     * of signatures locally, anywhere, by anyone. The trade is that a caller verifying locally has
     * to pick the algorithm — see [GetPublicKeyResponse.signingAlgorithms] — and has to trust its
     * own copy of the key rather than KMS's, which is why the response reports the [GetPublicKeyResponse.keyId]
     * ARN it came from.
     *
     * A symmetric or HMAC key has no public half: [KmsUnsupportedOperationException].
     */
    public suspend fun getPublicKey(request: GetPublicKeyRequest): GetPublicKeyResponse
}

/** Configuration for [Kms]. */
public class KmsConfig {
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
     * The defaults suit KMS well: every request and response here is a few kilobytes at most — the
     * plaintext limit is 4096 bytes — so a call that has not completed in the default window is
     * not slow, it is stuck.
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

/** Builds a KMS client. */
public fun Kms(configure: KmsConfig.() -> Unit = {}): Kms {
    val config = KmsConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultKms(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("kms", region, config.endpointUrl),
            region = region,
            protocol = KMS_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

// Every operation below is OperationSafety.IDEMPOTENT. Two of the three reasons are obvious and
// the third is not, so all three are stated once here rather than repeated per operation:
//
//  - Encrypt, Decrypt, ReEncrypt, Sign and Verify are pure functions of their arguments. Replaying
//    one changes nothing. (Encrypt and Sign may return *different bytes* on a replay — a fresh IV,
//    a randomised PSS salt — but each of those answers decrypts or verifies identically, which is
//    what the safety flag is actually about.)
//  - GenerateDataKey, GenerateDataKeyWithoutPlaintext and GenerateRandom return *different*
//    material on a replay, and are still safe to replay. That looks like a contradiction against
//    OperationSafety.IDEMPOTENT's "the same end state *and the same answer*", and is not: there is
//    no end state to double-apply, because KMS does not store the data key it generates — which is
//    precisely why it has to hand back both copies — and the answer only has to match when the
//    caller might already hold the first one. After an ambiguous transport failure the first answer
//    is exactly what was lost. Marking them NOT_IDEMPOTENT would instead surface a dropped
//    connection as a failed request, for operations that have no side effect to protect.
//
// What is *not* safe to replay is a KMS call whose result a partially-completed caller has already
// committed somewhere. That is the caller's transaction to manage; the transport cannot see it.
internal class DefaultKms(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : Kms {

    override suspend fun encrypt(request: EncryptRequest): EncryptResponse = mapErrors {
        client.callJson(
            "Encrypt", request, EncryptRequest.serializer(), EncryptResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun decrypt(request: DecryptRequest): DecryptResponse = mapErrors {
        client.callJson(
            "Decrypt", request, DecryptRequest.serializer(), DecryptResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun reEncrypt(request: ReEncryptRequest): ReEncryptResponse = mapErrors {
        client.callJson(
            "ReEncrypt", request, ReEncryptRequest.serializer(), ReEncryptResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun generateDataKey(request: GenerateDataKeyRequest): GenerateDataKeyResponse =
        mapErrors {
            client.callJson(
                "GenerateDataKey", request, GenerateDataKeyRequest.serializer(),
                GenerateDataKeyResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
            )
        }

    override suspend fun generateDataKeyWithoutPlaintext(
        request: GenerateDataKeyWithoutPlaintextRequest,
    ): GenerateDataKeyWithoutPlaintextResponse = mapErrors {
        client.callJson(
            "GenerateDataKeyWithoutPlaintext", request,
            GenerateDataKeyWithoutPlaintextRequest.serializer(),
            GenerateDataKeyWithoutPlaintextResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun generateRandom(request: GenerateRandomRequest): GenerateRandomResponse =
        mapErrors {
            client.callJson(
                "GenerateRandom", request, GenerateRandomRequest.serializer(),
                GenerateRandomResponse.serializer(), safety = OperationSafety.IDEMPOTENT,
            )
        }

    override suspend fun sign(request: SignRequest): SignResponse = mapErrors {
        client.callJson(
            "Sign", request, SignRequest.serializer(), SignResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun verify(request: VerifyRequest): VerifyResponse = mapErrors {
        client.callJson(
            "Verify", request, VerifyRequest.serializer(), VerifyResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun getPublicKey(request: GetPublicKeyRequest): GetPublicKeyResponse = mapErrors {
        client.callJson(
            "GetPublicKey", request, GetPublicKeyRequest.serializer(), GetPublicKeyResponse.serializer(),
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

// -- Conveniences --------------------------------------------------------------------------------

/**
 * `Verify`, shaped the way a signature check reads: **`true` for a good signature, `false` for a
 * bad one.**
 *
 * This is the method to call. [Kms.verify] reports a mismatch as [KmsInvalidSignatureException],
 * so the natural-looking `verify(request).signatureValid` is a test that is either `true` or
 * throws — and a mismatched signature, the case the check exists for, takes the branch that was
 * never written.
 *
 * ### What it does and does not swallow
 *
 * Only [KmsInvalidSignatureException] becomes `false`. A disabled key, an expired grant token, a
 * wrong signing algorithm and a throttle all still throw, because none of them is evidence about
 * the signature: answering `false` for "KMS was unreachable" turns an availability failure into an
 * authentication failure, and rejects a request that was never checked. That distinction is the
 * whole reason this is a few lines rather than a `runCatching { }.getOrDefault(false)`.
 *
 * @return whether [VerifyRequest.signature] is a valid signature over [VerifyRequest.message]
 *   under [VerifyRequest.keyId].
 */
public suspend fun Kms.verifySignature(request: VerifyRequest): Boolean = try {
    verify(request).signatureValid
} catch (invalid: KmsInvalidSignatureException) {
    false
}

/**
 * Encrypts, then discards everything but the ciphertext.
 *
 * Sugar for the overwhelmingly common shape, where the caller has bytes, a key and a context, and
 * wants a blob back. Reach for [Kms.encrypt] when the response's `KeyId` or `EncryptionAlgorithm`
 * matters — for instance to record which key version a stored blob was written under.
 */
public suspend fun Kms.encrypt(
    keyId: String,
    plaintext: ByteArray,
    encryptionContext: Map<String, String>? = null,
): ByteArray = encrypt(EncryptRequest(keyId, plaintext, encryptionContext)).ciphertextBlob

/**
 * Decrypts, then discards everything but the plaintext.
 *
 * [keyId] is second and **not** optional here, unlike on [DecryptRequest], where AWS's own shape
 * has to be reproduced faithfully. A convenience is free to have a safer default than the wire
 * form it wraps, and the safer default is the one where a substituted ciphertext fails with
 * [IncorrectKeyException] instead of being decrypted under a key the attacker chose. Use
 * [Kms.decrypt] directly for the genuinely key-agnostic case.
 */
public suspend fun Kms.decrypt(
    keyId: String,
    ciphertextBlob: ByteArray,
    encryptionContext: Map<String, String>? = null,
): ByteArray = decrypt(DecryptRequest(ciphertextBlob, keyId, encryptionContext)).plaintext

/**
 * `GetPublicKey`, then discards everything but the DER.
 *
 * For the caller who already knows the key's spec and algorithm — typically because it configured
 * them — and just wants bytes to hand to a key parser. Reach for [Kms.getPublicKey] when the
 * response's `KeySpec`, `KeyUsage` or algorithm lists matter, which for anything that has to *choose*
 * how to verify they do.
 */
public suspend fun Kms.getPublicKey(keyId: String): ByteArray =
    getPublicKey(GetPublicKeyRequest(keyId)).publicKey
