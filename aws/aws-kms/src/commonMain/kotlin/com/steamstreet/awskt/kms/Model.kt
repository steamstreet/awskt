package com.steamstreet.awskt.kms

import com.steamstreet.awskt.core.Base64BlobSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request and response types for KMS's data plane.
 *
 * ### Three rules that are not style preferences
 *
 * **Nothing that carries a `ByteArray` is a `data class`.** The rule is `aws-s3`'s, restated
 * because it applies for the same reasons and to more types here: a generated `copy()`,
 * `componentN()` and `toString()` all expose the array — `toString()` by printing an identity hash
 * that reads like content, `equals` by comparing references so two identical payloads compare
 * unequal. Those types get hand-written `equals`/`hashCode` over `contentEquals`/
 * `contentHashCode`. Types with no `ByteArray` are ordinary data classes and gain `copy()`
 * legitimately.
 *
 * **Nothing that carries plaintext key material prints it.** Every `toString()` on a type holding
 * a `Plaintext` reports `<redacted>` and, at most, a byte count. This is a stronger rule than
 * `aws-s3`'s "report the size, never the bytes", and it is the whole reason [DecryptResponse] and
 * [GenerateDataKeyResponse] have hand-written `toString()`s that look almost, but not quite, like
 * the ones the compiler would have generated. A data key that reaches CloudWatch Logs in a
 * `logger.debug("$response")` is a data key that has to be rotated, and the log entry outlives the
 * incident by the retention period.
 *
 * ### Algorithms and key specs are `String`, not `enum class`
 *
 * A deliberate departure from `aws-dynamodb`, whose `ReturnValue` and `Select` *are* enums. Those
 * are closed sets that this library only ever **sends**. KMS's `EncryptionAlgorithm`,
 * `SigningAlgorithm` and `KeySpec` come back on the **response** as well, and AWS extends them —
 * `SM2PKE` and `SM2DSA` arrived after the original set. An `enum class` on a response field turns
 * "AWS supports a new algorithm" into a `SerializationException` for every caller of this library,
 * including callers not using the new algorithm, and the fix is a release. A `String` makes it a
 * non-event. The documented values are named as constants on [EncryptionAlgorithm],
 * [SigningAlgorithm], [DataKeySpec] and [MessageType], so call sites still get autocompletion and
 * a spelling that cannot drift.
 *
 * ### What is not modelled
 *
 * `Recipient` / `CiphertextForRecipient` (Nitro Enclaves attestation) are absent. They are
 * meaningful only inside an enclave, which is not a target this library builds for, and modelling
 * them would mean carrying an attestation document shape that nothing here can produce. The
 * response fields are simply unread — `awsJson` sets `ignoreUnknownKeys = true`, so a caller who
 * adds them through the extension seam is not fighting this module.
 */

/** Documented values for `EncryptionAlgorithm`. See the file KDoc for why these are not an enum. */
public object EncryptionAlgorithm {
    public const val SYMMETRIC_DEFAULT: String = "SYMMETRIC_DEFAULT"
    public const val RSAES_OAEP_SHA_1: String = "RSAES_OAEP_SHA_1"
    public const val RSAES_OAEP_SHA_256: String = "RSAES_OAEP_SHA_256"
    public const val SM2PKE: String = "SM2PKE"
}

/** Documented values for `SigningAlgorithm`. */
public object SigningAlgorithm {
    public const val RSASSA_PSS_SHA_256: String = "RSASSA_PSS_SHA_256"
    public const val RSASSA_PSS_SHA_384: String = "RSASSA_PSS_SHA_384"
    public const val RSASSA_PSS_SHA_512: String = "RSASSA_PSS_SHA_512"
    public const val RSASSA_PKCS1_V1_5_SHA_256: String = "RSASSA_PKCS1_V1_5_SHA_256"
    public const val RSASSA_PKCS1_V1_5_SHA_384: String = "RSASSA_PKCS1_V1_5_SHA_384"
    public const val RSASSA_PKCS1_V1_5_SHA_512: String = "RSASSA_PKCS1_V1_5_SHA_512"
    public const val ECDSA_SHA_256: String = "ECDSA_SHA_256"
    public const val ECDSA_SHA_384: String = "ECDSA_SHA_384"
    public const val ECDSA_SHA_512: String = "ECDSA_SHA_512"
    public const val SM2DSA: String = "SM2DSA"
}

/** Documented values for `KeySpec` on the data-key operations. */
public object DataKeySpec {
    public const val AES_256: String = "AES_256"
    public const val AES_128: String = "AES_128"
}

/**
 * Documented values for a **KMS key's** `KeySpec`, as reported by [GetPublicKeyResponse.keySpec].
 *
 * Distinct from [DataKeySpec], which names the shape of a *data key* being minted. Only the
 * asymmetric specs can answer `GetPublicKey`; the symmetric and HMAC ones are listed because the
 * field can carry them elsewhere and a caller comparing against the wrong object should not have to
 * guess why nothing matches.
 */
public object KeySpec {
    public const val RSA_2048: String = "RSA_2048"
    public const val RSA_3072: String = "RSA_3072"
    public const val RSA_4096: String = "RSA_4096"
    public const val ECC_NIST_P256: String = "ECC_NIST_P256"
    public const val ECC_NIST_P384: String = "ECC_NIST_P384"
    public const val ECC_NIST_P521: String = "ECC_NIST_P521"
    public const val ECC_SECG_P256K1: String = "ECC_SECG_P256K1"
    public const val SM2: String = "SM2"
    public const val ML_DSA_44: String = "ML_DSA_44"
    public const val ML_DSA_65: String = "ML_DSA_65"
    public const val ML_DSA_87: String = "ML_DSA_87"
    public const val SYMMETRIC_DEFAULT: String = "SYMMETRIC_DEFAULT"
    public const val HMAC_224: String = "HMAC_224"
    public const val HMAC_256: String = "HMAC_256"
    public const val HMAC_384: String = "HMAC_384"
    public const val HMAC_512: String = "HMAC_512"
}

/** Documented values for `KeyUsage`. */
public object KeyUsage {
    public const val SIGN_VERIFY: String = "SIGN_VERIFY"
    public const val ENCRYPT_DECRYPT: String = "ENCRYPT_DECRYPT"
    public const val GENERATE_VERIFY_MAC: String = "GENERATE_VERIFY_MAC"
    public const val KEY_AGREEMENT: String = "KEY_AGREEMENT"
}

/**
 * Documented values for `MessageType` on [SignRequest] and [VerifyRequest].
 *
 * [DIGEST] means the `Message` field already holds the hash, not the message — used when the
 * message is larger than KMS's 4096-byte limit. Getting this backwards produces a signature over
 * the wrong bytes that verifies perfectly against the same mistake, so it is wrong only when
 * something else has to check it.
 */
public object MessageType {
    public const val RAW: String = "RAW"
    public const val DIGEST: String = "DIGEST"
}

// -- Encrypt -------------------------------------------------------------------------------------

/**
 * `Encrypt`. **Not a data class** — it carries plaintext.
 *
 * @param keyId a key id, key ARN, alias name (`alias/my-key`) or alias ARN.
 * @param encryptionContext additional authenticated data. Whatever is supplied here **must be
 *   supplied identically to [DecryptRequest]**, or the decrypt fails — it is authenticated, not
 *   encrypted, so it is visible in CloudTrail and must not hold anything secret. This is the
 *   mechanism that stops a ciphertext for tenant A being decrypted in tenant B's request path.
 * @param dryRun checks authorization without doing the work. On success KMS raises
 *   [DryRunOperationException] rather than returning a response — see that type.
 */
@Serializable
public class EncryptRequest(
    @SerialName("KeyId") public val keyId: String,
    @SerialName("Plaintext") @Serializable(with = Base64BlobSerializer::class)
    public val plaintext: ByteArray,
    @SerialName("EncryptionContext") public val encryptionContext: Map<String, String>? = null,
    @SerialName("EncryptionAlgorithm") public val encryptionAlgorithm: String? = null,
    @SerialName("GrantTokens") public val grantTokens: List<String>? = null,
    @SerialName("DryRun") public val dryRun: Boolean? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is EncryptRequest &&
                keyId == other.keyId &&
                plaintext.contentEquals(other.plaintext) &&
                encryptionContext == other.encryptionContext &&
                encryptionAlgorithm == other.encryptionAlgorithm &&
                grantTokens == other.grantTokens &&
                dryRun == other.dryRun
            )

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + plaintext.contentHashCode()
        result = 31 * result + (encryptionContext?.hashCode() ?: 0)
        result = 31 * result + (encryptionAlgorithm?.hashCode() ?: 0)
        return result
    }

    /** Reports the plaintext's size and never its bytes. */
    override fun toString(): String =
        "EncryptRequest(keyId=$keyId, plaintext=<redacted, ${plaintext.size} bytes>, " +
            "encryptionContext=${encryptionContext?.keys}, encryptionAlgorithm=$encryptionAlgorithm)"
}

/**
 * `Encrypt`'s result. **Not a data class** — it carries bytes.
 *
 * [ciphertextBlob] is **not** secret and is printed as a size rather than as content only because
 * it is large and uninteresting, not because it is sensitive. It already contains everything needed
 * to identify the key that encrypted it, which is why [DecryptRequest] can omit `KeyId` at all —
 * and why it should not.
 */
@Serializable
public class EncryptResponse(
    @SerialName("CiphertextBlob") @Serializable(with = Base64BlobSerializer::class)
    public val ciphertextBlob: ByteArray,
    @SerialName("KeyId") public val keyId: String? = null,
    @SerialName("EncryptionAlgorithm") public val encryptionAlgorithm: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is EncryptResponse &&
                ciphertextBlob.contentEquals(other.ciphertextBlob) &&
                keyId == other.keyId &&
                encryptionAlgorithm == other.encryptionAlgorithm
            )

    override fun hashCode(): Int {
        var result = ciphertextBlob.contentHashCode()
        result = 31 * result + (keyId?.hashCode() ?: 0)
        result = 31 * result + (encryptionAlgorithm?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "EncryptResponse(ciphertextBlob=${ciphertextBlob.size} bytes, keyId=$keyId, " +
            "encryptionAlgorithm=$encryptionAlgorithm)"
}

// -- Decrypt -------------------------------------------------------------------------------------

/**
 * `Decrypt`. **Not a data class** — it carries bytes.
 *
 * ### Set [keyId], even though it is optional
 *
 * For a symmetric ciphertext KMS will infer the key from metadata inside [ciphertextBlob] when
 * [keyId] is absent. That is a confused-deputy hazard, and AWS's own documentation recommends
 * against relying on it: a caller that decrypts a blob it did not itself produce, and does not name
 * the key, will happily decrypt under *whatever key the blob names* — including a key an attacker
 * chose, provided this principal is allowed to use it. Naming the key turns that into an
 * [IncorrectKeyException]. This client does not force it, because a blob whose key genuinely is not
 * known ahead of time is a real case, but the default is worth going out of your way to avoid.
 *
 * @param encryptionContext must match the context the ciphertext was created with, exactly.
 */
@Serializable
public class DecryptRequest(
    @SerialName("CiphertextBlob") @Serializable(with = Base64BlobSerializer::class)
    public val ciphertextBlob: ByteArray,
    @SerialName("KeyId") public val keyId: String? = null,
    @SerialName("EncryptionContext") public val encryptionContext: Map<String, String>? = null,
    @SerialName("EncryptionAlgorithm") public val encryptionAlgorithm: String? = null,
    @SerialName("GrantTokens") public val grantTokens: List<String>? = null,
    @SerialName("DryRun") public val dryRun: Boolean? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is DecryptRequest &&
                ciphertextBlob.contentEquals(other.ciphertextBlob) &&
                keyId == other.keyId &&
                encryptionContext == other.encryptionContext &&
                encryptionAlgorithm == other.encryptionAlgorithm &&
                grantTokens == other.grantTokens &&
                dryRun == other.dryRun
            )

    override fun hashCode(): Int {
        var result = ciphertextBlob.contentHashCode()
        result = 31 * result + (keyId?.hashCode() ?: 0)
        result = 31 * result + (encryptionContext?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "DecryptRequest(ciphertextBlob=${ciphertextBlob.size} bytes, keyId=$keyId, " +
            "encryptionContext=${encryptionContext?.keys})"
}

/**
 * `Decrypt`'s result. **Not a data class** — it carries plaintext.
 *
 * [keyId] is the ARN of the key that actually decrypted the blob. When [DecryptRequest.keyId] was
 * left null this is the *only* place the answer appears, and a caller relying on inference should
 * be checking it against an allow-list rather than trusting it.
 */
@Serializable
public class DecryptResponse(
    @SerialName("Plaintext") @Serializable(with = Base64BlobSerializer::class)
    public val plaintext: ByteArray,
    @SerialName("KeyId") public val keyId: String? = null,
    @SerialName("EncryptionAlgorithm") public val encryptionAlgorithm: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is DecryptResponse &&
                plaintext.contentEquals(other.plaintext) &&
                keyId == other.keyId &&
                encryptionAlgorithm == other.encryptionAlgorithm
            )

    override fun hashCode(): Int {
        var result = plaintext.contentHashCode()
        result = 31 * result + (keyId?.hashCode() ?: 0)
        return result
    }

    /** Reports the plaintext's size and never its bytes — see the file KDoc. */
    override fun toString(): String =
        "DecryptResponse(plaintext=<redacted, ${plaintext.size} bytes>, keyId=$keyId, " +
            "encryptionAlgorithm=$encryptionAlgorithm)"
}

// -- ReEncrypt -----------------------------------------------------------------------------------

/**
 * `ReEncrypt`. **Not a data class** — it carries bytes.
 *
 * Re-encrypts server-side: the plaintext never leaves KMS, which is the entire point of using this
 * instead of a decrypt followed by an encrypt. The caller needs `kms:ReEncryptFrom` on the source
 * key and `kms:ReEncryptTo` on the destination.
 *
 * @param sourceEncryptionContext the context the blob was created with. Required when it was
 *   created with one, and a mismatch here fails the same way a bad [DecryptRequest] context does.
 * @param destinationEncryptionContext the context to bind the *new* ciphertext to. It does not
 *   default to the source context — leaving it null re-encrypts with no context at all, silently
 *   dropping a binding the original ciphertext had.
 */
@Serializable
public class ReEncryptRequest(
    @SerialName("CiphertextBlob") @Serializable(with = Base64BlobSerializer::class)
    public val ciphertextBlob: ByteArray,
    @SerialName("DestinationKeyId") public val destinationKeyId: String,
    @SerialName("SourceEncryptionContext") public val sourceEncryptionContext: Map<String, String>? = null,
    @SerialName("SourceKeyId") public val sourceKeyId: String? = null,
    @SerialName("DestinationEncryptionContext")
    public val destinationEncryptionContext: Map<String, String>? = null,
    @SerialName("SourceEncryptionAlgorithm") public val sourceEncryptionAlgorithm: String? = null,
    @SerialName("DestinationEncryptionAlgorithm")
    public val destinationEncryptionAlgorithm: String? = null,
    @SerialName("GrantTokens") public val grantTokens: List<String>? = null,
    @SerialName("DryRun") public val dryRun: Boolean? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is ReEncryptRequest &&
                ciphertextBlob.contentEquals(other.ciphertextBlob) &&
                destinationKeyId == other.destinationKeyId &&
                sourceEncryptionContext == other.sourceEncryptionContext &&
                sourceKeyId == other.sourceKeyId &&
                destinationEncryptionContext == other.destinationEncryptionContext &&
                sourceEncryptionAlgorithm == other.sourceEncryptionAlgorithm &&
                destinationEncryptionAlgorithm == other.destinationEncryptionAlgorithm &&
                grantTokens == other.grantTokens &&
                dryRun == other.dryRun
            )

    override fun hashCode(): Int {
        var result = ciphertextBlob.contentHashCode()
        result = 31 * result + destinationKeyId.hashCode()
        result = 31 * result + (sourceKeyId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ReEncryptRequest(ciphertextBlob=${ciphertextBlob.size} bytes, sourceKeyId=$sourceKeyId, " +
            "destinationKeyId=$destinationKeyId)"
}

/** `ReEncrypt`'s result. **Not a data class** — it carries bytes. */
@Serializable
public class ReEncryptResponse(
    @SerialName("CiphertextBlob") @Serializable(with = Base64BlobSerializer::class)
    public val ciphertextBlob: ByteArray,
    @SerialName("SourceKeyId") public val sourceKeyId: String? = null,
    @SerialName("KeyId") public val keyId: String? = null,
    @SerialName("SourceEncryptionAlgorithm") public val sourceEncryptionAlgorithm: String? = null,
    @SerialName("DestinationEncryptionAlgorithm")
    public val destinationEncryptionAlgorithm: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is ReEncryptResponse &&
                ciphertextBlob.contentEquals(other.ciphertextBlob) &&
                sourceKeyId == other.sourceKeyId &&
                keyId == other.keyId &&
                sourceEncryptionAlgorithm == other.sourceEncryptionAlgorithm &&
                destinationEncryptionAlgorithm == other.destinationEncryptionAlgorithm
            )

    override fun hashCode(): Int {
        var result = ciphertextBlob.contentHashCode()
        result = 31 * result + (keyId?.hashCode() ?: 0)
        result = 31 * result + (sourceKeyId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ReEncryptResponse(ciphertextBlob=${ciphertextBlob.size} bytes, sourceKeyId=$sourceKeyId, " +
            "keyId=$keyId)"
}

// -- GenerateDataKey -----------------------------------------------------------------------------

/**
 * `GenerateDataKey`. A data class — it carries no bytes in either direction.
 *
 * Exactly one of [keySpec] and [numberOfBytes] should be set; setting both is a
 * `ValidationException` from KMS and setting neither defaults to `AES_256`. Prefer [keySpec] —
 * [numberOfBytes] exists for algorithms KMS does not name, and a bare `32` at a call site does not
 * say which algorithm it was meant for.
 */
@Serializable
public data class GenerateDataKeyRequest(
    @SerialName("KeyId") public val keyId: String,
    @SerialName("EncryptionContext") public val encryptionContext: Map<String, String>? = null,
    @SerialName("KeySpec") public val keySpec: String? = null,
    @SerialName("NumberOfBytes") public val numberOfBytes: Int? = null,
    @SerialName("GrantTokens") public val grantTokens: List<String>? = null,
    @SerialName("DryRun") public val dryRun: Boolean? = null,
)

/**
 * `GenerateDataKey`'s result: the same key, twice. **Not a data class** — it carries plaintext.
 *
 * The envelope-encryption contract is that [plaintext] is used to encrypt locally and then
 * **discarded**, while [ciphertextBlob] is stored next to the encrypted data and handed back to
 * [Kms.decrypt] when it is next needed. Storing [plaintext] anywhere durable defeats the entire
 * arrangement: the point of a data key is that the copy at rest is one KMS has to be asked to open.
 *
 * Kotlin cannot help with the discarding. [plaintext] is a `ByteArray` on the heap and there is no
 * `finally { zero() }` this class can impose on a caller that keeps the reference — callers that
 * care can `plaintext.fill(0)` once the local encryption is done, which is worth doing in a
 * long-lived process and close to pointless in a Lambda that is about to freeze anyway. What this
 * class *can* do is refuse to print it, which it does.
 */
@Serializable
public class GenerateDataKeyResponse(
    @SerialName("CiphertextBlob") @Serializable(with = Base64BlobSerializer::class)
    public val ciphertextBlob: ByteArray,
    @SerialName("Plaintext") @Serializable(with = Base64BlobSerializer::class)
    public val plaintext: ByteArray,
    @SerialName("KeyId") public val keyId: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is GenerateDataKeyResponse &&
                ciphertextBlob.contentEquals(other.ciphertextBlob) &&
                plaintext.contentEquals(other.plaintext) &&
                keyId == other.keyId
            )

    override fun hashCode(): Int {
        var result = ciphertextBlob.contentHashCode()
        result = 31 * result + plaintext.contentHashCode()
        result = 31 * result + (keyId?.hashCode() ?: 0)
        return result
    }

    /** Reports the plaintext's size and never its bytes — see the file KDoc. */
    override fun toString(): String =
        "GenerateDataKeyResponse(ciphertextBlob=${ciphertextBlob.size} bytes, " +
            "plaintext=<redacted, ${plaintext.size} bytes>, keyId=$keyId)"
}

/**
 * `GenerateDataKeyWithoutPlaintext`. A data class — it carries no bytes.
 *
 * Use this when the key is minted somewhere that will not encrypt with it — a control plane
 * provisioning a key for a worker that decrypts it later. If the process calling this is the one
 * that needs to encrypt, it is going to call [Kms.decrypt] immediately afterwards, which costs a
 * second KMS request and a second point of failure for no gain over [GenerateDataKeyRequest].
 */
@Serializable
public data class GenerateDataKeyWithoutPlaintextRequest(
    @SerialName("KeyId") public val keyId: String,
    @SerialName("EncryptionContext") public val encryptionContext: Map<String, String>? = null,
    @SerialName("KeySpec") public val keySpec: String? = null,
    @SerialName("NumberOfBytes") public val numberOfBytes: Int? = null,
    @SerialName("GrantTokens") public val grantTokens: List<String>? = null,
    @SerialName("DryRun") public val dryRun: Boolean? = null,
)

/** `GenerateDataKeyWithoutPlaintext`'s result. **Not a data class** — it carries bytes. */
@Serializable
public class GenerateDataKeyWithoutPlaintextResponse(
    @SerialName("CiphertextBlob") @Serializable(with = Base64BlobSerializer::class)
    public val ciphertextBlob: ByteArray,
    @SerialName("KeyId") public val keyId: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is GenerateDataKeyWithoutPlaintextResponse &&
                ciphertextBlob.contentEquals(other.ciphertextBlob) &&
                keyId == other.keyId
            )

    override fun hashCode(): Int = 31 * ciphertextBlob.contentHashCode() + (keyId?.hashCode() ?: 0)

    override fun toString(): String =
        "GenerateDataKeyWithoutPlaintextResponse(ciphertextBlob=${ciphertextBlob.size} bytes, keyId=$keyId)"
}

// -- GenerateRandom ------------------------------------------------------------------------------

/**
 * `GenerateRandom`. A data class — it carries no bytes.
 *
 * @param numberOfBytes 1 to 1024. KMS rejects anything outside that with a `ValidationException`;
 *   this client does not pre-check it, because the bound is the service's and duplicating it here
 *   creates a second place for it to be wrong.
 */
@Serializable
public data class GenerateRandomRequest(
    @SerialName("NumberOfBytes") public val numberOfBytes: Int,
    @SerialName("CustomKeyStoreId") public val customKeyStoreId: String? = null,
)

/**
 * `GenerateRandom`'s result. **Not a data class** — it carries secret bytes.
 *
 * Worth being clear about what this is *for*: it is a network round trip to obtain randomness, so
 * it is not a substitute for the platform RNG in a hot path. It earns its cost when the randomness
 * must be FIPS 140-3 validated, or must come from a CloudHSM cluster via
 * [GenerateRandomRequest.customKeyStoreId], or
 * when the caller has no trustworthy local entropy source. `kotlin.random.Random.Default` is the
 * right answer for anything that is not one of those, and `aws-core`'s `randomUuidString` already
 * documents that it is not a CSPRNG.
 */
@Serializable
public class GenerateRandomResponse(
    @SerialName("Plaintext") @Serializable(with = Base64BlobSerializer::class)
    public val plaintext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is GenerateRandomResponse && plaintext.contentEquals(other.plaintext))

    override fun hashCode(): Int = plaintext.contentHashCode()

    /** Reports the size and never the bytes — see the file KDoc. */
    override fun toString(): String = "GenerateRandomResponse(plaintext=<redacted, ${plaintext.size} bytes>)"
}

// -- Sign / Verify -------------------------------------------------------------------------------

/**
 * `Sign`. **Not a data class** — it carries bytes.
 *
 * @param message at most **4096 bytes** when [messageType] is `RAW`. Above that, hash the message
 *   locally and send the digest with [messageType] = [MessageType.DIGEST] — the digest algorithm
 *   has to match the one named in [signingAlgorithm], so `ECDSA_SHA_384` takes a SHA-384 digest and
 *   nothing else.
 */
@Serializable
public class SignRequest(
    @SerialName("KeyId") public val keyId: String,
    @SerialName("Message") @Serializable(with = Base64BlobSerializer::class)
    public val message: ByteArray,
    @SerialName("SigningAlgorithm") public val signingAlgorithm: String,
    @SerialName("MessageType") public val messageType: String? = null,
    @SerialName("GrantTokens") public val grantTokens: List<String>? = null,
    @SerialName("DryRun") public val dryRun: Boolean? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SignRequest &&
                keyId == other.keyId &&
                message.contentEquals(other.message) &&
                signingAlgorithm == other.signingAlgorithm &&
                messageType == other.messageType &&
                grantTokens == other.grantTokens &&
                dryRun == other.dryRun
            )

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + message.contentHashCode()
        result = 31 * result + signingAlgorithm.hashCode()
        return result
    }

    override fun toString(): String =
        "SignRequest(keyId=$keyId, message=${message.size} bytes, " +
            "signingAlgorithm=$signingAlgorithm, messageType=$messageType)"
}

/** `Sign`'s result. **Not a data class** — it carries bytes. */
@Serializable
public class SignResponse(
    @SerialName("Signature") @Serializable(with = Base64BlobSerializer::class)
    public val signature: ByteArray,
    @SerialName("KeyId") public val keyId: String? = null,
    @SerialName("SigningAlgorithm") public val signingAlgorithm: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SignResponse &&
                signature.contentEquals(other.signature) &&
                keyId == other.keyId &&
                signingAlgorithm == other.signingAlgorithm
            )

    override fun hashCode(): Int {
        var result = signature.contentHashCode()
        result = 31 * result + (keyId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SignResponse(signature=${signature.size} bytes, keyId=$keyId, signingAlgorithm=$signingAlgorithm)"
}

/**
 * `Verify`. **Not a data class** — it carries bytes.
 *
 * **Read [Kms.verify] before using this.** A signature that does not verify is an *exception*, not
 * a `false`, and [VerifyResponse.signatureValid] is therefore never false in a returned response.
 * [verifySignature] is the boolean-shaped wrapper.
 */
@Serializable
public class VerifyRequest(
    @SerialName("KeyId") public val keyId: String,
    @SerialName("Message") @Serializable(with = Base64BlobSerializer::class)
    public val message: ByteArray,
    @SerialName("Signature") @Serializable(with = Base64BlobSerializer::class)
    public val signature: ByteArray,
    @SerialName("SigningAlgorithm") public val signingAlgorithm: String,
    @SerialName("MessageType") public val messageType: String? = null,
    @SerialName("GrantTokens") public val grantTokens: List<String>? = null,
    @SerialName("DryRun") public val dryRun: Boolean? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is VerifyRequest &&
                keyId == other.keyId &&
                message.contentEquals(other.message) &&
                signature.contentEquals(other.signature) &&
                signingAlgorithm == other.signingAlgorithm &&
                messageType == other.messageType &&
                grantTokens == other.grantTokens &&
                dryRun == other.dryRun
            )

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + message.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + signingAlgorithm.hashCode()
        return result
    }

    override fun toString(): String =
        "VerifyRequest(keyId=$keyId, message=${message.size} bytes, " +
            "signature=${signature.size} bytes, signingAlgorithm=$signingAlgorithm, messageType=$messageType)"
}

/**
 * `Verify`'s result. A data class — it carries no bytes.
 *
 * [signatureValid] is **always true in a response this client returns**. KMS reports a bad
 * signature as `KMSInvalidSignatureException`, which arrives as [KmsInvalidSignatureException]
 * rather than as this type with the flag cleared. The field is modelled because it is on the wire
 * and because reading a `false` from it would mean AWS had changed that behaviour — not because a
 * caller should be branching on it. See [verifySignature].
 */
@Serializable
public data class VerifyResponse(
    @SerialName("SignatureValid") public val signatureValid: Boolean = false,
    @SerialName("KeyId") public val keyId: String? = null,
    @SerialName("SigningAlgorithm") public val signingAlgorithm: String? = null,
)

/**
 * `GetPublicKey`. A data class — it carries no bytes.
 *
 * Only an **asymmetric** key has a public half. Asking a symmetric or HMAC key is
 * [KmsUnsupportedOperationException], not an empty answer.
 */
@Serializable
public data class GetPublicKeyRequest(
    @SerialName("KeyId") val keyId: String,
    @SerialName("GrantTokens") val grantTokens: List<String>? = null,
)

/**
 * `GetPublicKey`'s result. **Not a data class** — it carries bytes.
 *
 * [publicKey] is a DER-encoded X.509 `SubjectPublicKeyInfo`, which is what every platform's key
 * parser takes directly (`X509EncodedKeySpec` on the JVM, `SecKeyCreateWithData` on Apple, or PEM it
 * by base64-encoding between `-----BEGIN PUBLIC KEY-----` lines). It is not secret — the point of
 * the operation is to hand it out — so unlike the other byte-carrying types here nothing is redacted
 * beyond keeping `toString` to a size, and that only because 300 bytes of DER in a log line is
 * noise, not a leak.
 *
 * The algorithm lists say what the key **can** do rather than what the caller asked: exactly one of
 * [encryptionAlgorithms] / [signingAlgorithms] / [keyAgreementAlgorithms] is populated, according
 * to [keyUsage]. Verifying a KMS signature locally means picking one of [signingAlgorithms] that
 * matches the one passed to `Sign` — the DER alone does not encode a padding scheme.
 */
@Serializable
public class GetPublicKeyResponse(
    @SerialName("PublicKey") @Serializable(with = Base64BlobSerializer::class)
    public val publicKey: ByteArray,
    @SerialName("KeyId") public val keyId: String? = null,
    /** See [KeySpec]. */
    @SerialName("KeySpec") public val keySpec: String? = null,
    /** See [KeyUsage]. */
    @SerialName("KeyUsage") public val keyUsage: String? = null,
    @SerialName("EncryptionAlgorithms") public val encryptionAlgorithms: List<String>? = null,
    @SerialName("SigningAlgorithms") public val signingAlgorithms: List<String>? = null,
    @SerialName("KeyAgreementAlgorithms") public val keyAgreementAlgorithms: List<String>? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is GetPublicKeyResponse &&
                publicKey.contentEquals(other.publicKey) &&
                keyId == other.keyId &&
                keySpec == other.keySpec &&
                keyUsage == other.keyUsage &&
                encryptionAlgorithms == other.encryptionAlgorithms &&
                signingAlgorithms == other.signingAlgorithms &&
                keyAgreementAlgorithms == other.keyAgreementAlgorithms
            )

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + (keyId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "GetPublicKeyResponse(publicKey=${publicKey.size} bytes, keyId=$keyId, keySpec=$keySpec, " +
            "keyUsage=$keyUsage, signingAlgorithms=$signingAlgorithms, " +
            "encryptionAlgorithms=$encryptionAlgorithms, keyAgreementAlgorithms=$keyAgreementAlgorithms)"
}
