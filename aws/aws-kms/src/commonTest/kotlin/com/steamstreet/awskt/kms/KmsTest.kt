package com.steamstreet.awskt.kms

import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class KmsHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessKms(
    harness: KmsHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): Kms {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultKms(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("kms", "us-west-2", "https://kms.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = KMS_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

private const val ENCRYPT_OK = """{"CiphertextBlob":"Y2lwaGVy","KeyId":"arn:key","EncryptionAlgorithm":"SYMMETRIC_DEFAULT"}"""
private const val DECRYPT_OK = """{"Plaintext":"cGxhaW4=","KeyId":"arn:key"}"""

class KmsProtocolTest {

    @Test
    fun targetsTrentServiceOverAwsJson1_1() = runTest {
        val h = KmsHarness()
        harnessKms(h) { ENCRYPT_OK to HttpStatusCode.OK }
            .encrypt(EncryptRequest("alias/k", "hello".encodeToByteArray()))

        val request = h.requests.single()
        // TrentService, not "kms" — KMS's target prefix is its original internal service name and
        // cannot be derived from the endpoint prefix. Getting it wrong is a 400 from every call.
        assertEquals("TrentService.Encrypt", request.headers["X-Amz-Target"])
        // Content type lives on the body, not in `headers` — Ktor moves it there.
        assertEquals("application/x-amz-json-1.1", request.body.contentType?.toString())
    }

    @Test
    fun eachOperationSendsItsOwnTarget() = runTest {
        val cases: List<Pair<String, suspend (Kms) -> Any>> = listOf(
            "TrentService.Encrypt" to { k -> k.encrypt(EncryptRequest("k", "hello".encodeToByteArray())) },
            "TrentService.Decrypt" to { k -> k.decrypt(DecryptRequest("cipher".encodeToByteArray())) },
            "TrentService.ReEncrypt" to { k ->
                k.reEncrypt(ReEncryptRequest("cipher".encodeToByteArray(), "dest"))
            },
            "TrentService.GenerateDataKey" to { k -> k.generateDataKey(GenerateDataKeyRequest("k")) },
            "TrentService.GenerateDataKeyWithoutPlaintext" to { k ->
                k.generateDataKeyWithoutPlaintext(GenerateDataKeyWithoutPlaintextRequest("k"))
            },
            "TrentService.GenerateRandom" to { k -> k.generateRandom(GenerateRandomRequest(32)) },
            "TrentService.Sign" to { k ->
                k.sign(SignRequest("k", "msg".encodeToByteArray(), SigningAlgorithm.ECDSA_SHA_256))
            },
            "TrentService.Verify" to { k ->
                k.verify(
                    VerifyRequest(
                        "k", "msg".encodeToByteArray(), "sig-bytes".encodeToByteArray(),
                        SigningAlgorithm.ECDSA_SHA_256,
                    ),
                )
            },
        )

        // One response body that satisfies every operation's required fields at once. The
        // deserializer ignores the keys an operation does not declare.
        val everything = """
            {"CiphertextBlob":"Y2lwaGVy","Plaintext":"cGxhaW4=","Signature":"c2lnLWJ5dGVz",
             "SignatureValid":true,"KeyId":"arn:key"}
        """.trimIndent()

        for ((expectedTarget, invoke) in cases) {
            val h = KmsHarness()
            invoke(harnessKms(h) { everything to HttpStatusCode.OK })
            assertEquals(expectedTarget, h.requests.single().headers["X-Amz-Target"])
        }
    }
}

class KmsBlobEncodingTest {

    @Test
    fun sendsBlobsAsBase64Strings() = runTest {
        val h = KmsHarness()
        harnessKms(h) { ENCRYPT_OK to HttpStatusCode.OK }
            .encrypt(EncryptRequest("alias/k", "hello".encodeToByteArray()))

        val body = bodyJson(h.bodies.single())
        // A blob is a base64 *string* on the wire, not an array of numbers. Emitting the byte array
        // directly is the natural mistake and produces a 400 that names no field.
        assertEquals("aGVsbG8=", body["Plaintext"]?.jsonPrimitive?.content)
        assertEquals("alias/k", body["KeyId"]?.jsonPrimitive?.content)
    }

    @Test
    fun decodesBlobsFromBase64Strings() = runTest {
        val h = KmsHarness()
        val response = harnessKms(h) { DECRYPT_OK to HttpStatusCode.OK }
            .decrypt(DecryptRequest("cipher".encodeToByteArray(), keyId = "arn:key"))

        assertEquals("plain", response.plaintext.decodeToString())
        assertEquals("arn:key", response.keyId)
    }

    @Test
    fun roundTripsEveryBlobFieldOfGenerateDataKey() = runTest {
        val h = KmsHarness()
        val response = harnessKms(h) {
            """{"CiphertextBlob":"c2VhbGVk","Plaintext":"ZGF0YWtleS1wbGFpbnRleHQ=","KeyId":"arn:key"}""" to
                HttpStatusCode.OK
        }.generateDataKey(GenerateDataKeyRequest("k", keySpec = DataKeySpec.AES_256))

        assertEquals("sealed", response.ciphertextBlob.decodeToString())
        assertEquals("datakey-plaintext", response.plaintext.decodeToString())
        assertEquals("AES_256", bodyJson(h.bodies.single())["KeySpec"]?.jsonPrimitive?.content)
    }

    /**
     * `encodeDefaults = false` means a null field is absent rather than `"KeyId":null`. The
     * distinction is wire-correctness, not tidiness: KMS rejects an explicit null.
     */
    @Test
    fun omitsNullFieldsEntirely() = runTest {
        val h = KmsHarness()
        harnessKms(h) { DECRYPT_OK to HttpStatusCode.OK }
            .decrypt(DecryptRequest("cipher".encodeToByteArray()))

        assertEquals(setOf("CiphertextBlob"), bodyJson(h.bodies.single()).keys)
    }

    @Test
    fun sendsEncryptionContextAsAnObject() = runTest {
        val h = KmsHarness()
        harnessKms(h) { ENCRYPT_OK to HttpStatusCode.OK }.encrypt(
            EncryptRequest("k", "hello".encodeToByteArray(), mapOf("tenant" to "acme")),
        )

        val context = bodyJson(h.bodies.single())["EncryptionContext"]!!.jsonObject
        assertEquals("acme", context["tenant"]?.jsonPrimitive?.content)
    }
}

/**
 * The redaction rule from `Model.kt`, asserted rather than trusted.
 *
 * These are not cosmetic assertions. The failure they prevent is a data key written to CloudWatch
 * Logs by an innocuous `logger.debug("$response")`, where it outlives the incident by the log
 * group's retention period and forces a key rotation.
 */
class KmsRedactionTest {

    @Test
    fun decryptResponseNeverPrintsPlaintext() {
        val response = DecryptResponse("super-secret-key".encodeToByteArray(), keyId = "arn:key")
        val printed = response.toString()

        assertFalse(printed.contains("super-secret-key"), "toString leaked the plaintext: $printed")
        assertContains(printed, "<redacted")
        assertContains(printed, "arn:key")
    }

    @Test
    fun generateDataKeyResponseNeverPrintsPlaintext() {
        val printed = GenerateDataKeyResponse(
            ciphertextBlob = "sealed".encodeToByteArray(),
            plaintext = "super-secret-key".encodeToByteArray(),
            keyId = "arn:key",
        ).toString()

        assertFalse(printed.contains("super-secret-key"), "toString leaked the data key: $printed")
        assertContains(printed, "<redacted")
    }

    @Test
    fun generateRandomResponseNeverPrintsBytes() {
        val printed = GenerateRandomResponse("super-secret-key".encodeToByteArray()).toString()
        assertFalse(printed.contains("super-secret-key"), "toString leaked the random bytes: $printed")
    }

    @Test
    fun encryptRequestNeverPrintsPlaintext() {
        val printed = EncryptRequest("k", "super-secret-key".encodeToByteArray()).toString()
        assertFalse(printed.contains("super-secret-key"), "toString leaked the plaintext: $printed")
    }

    /** Ciphertext is not secret, so it is summarized rather than redacted — but never printed raw. */
    @Test
    fun encryptResponseReportsCiphertextSizeRatherThanContent() {
        val printed = EncryptResponse("distinctive-ciphertext".encodeToByteArray(), keyId = "arn:key").toString()
        assertContains(printed, "22 bytes")
        assertFalse(printed.contains("distinctive-ciphertext"))
    }

    /**
     * Byte-carrying types compare by content, not by reference. A `data class` would have failed
     * this, which is why none of them is one.
     */
    @Test
    fun byteCarryingTypesCompareByContent() {
        assertEquals(
            DecryptResponse("plain".encodeToByteArray(), keyId = "k"),
            DecryptResponse("plain".encodeToByteArray(), keyId = "k"),
        )
        assertEquals(
            DecryptResponse("plain".encodeToByteArray(), keyId = "k").hashCode(),
            DecryptResponse("plain".encodeToByteArray(), keyId = "k").hashCode(),
        )
    }
}

class KmsErrorMappingTest {

    private fun errorBody(code: String, message: String = "nope") =
        """{"__type":"com.amazonaws.kms#$code","message":"$message"}"""

    @Test
    fun mapsKnownCodesOntoTypedExceptions() = runTest {
        suspend fun decryptFailingWith(code: String, status: HttpStatusCode): Throwable =
            runCatching {
                harnessKms(KmsHarness()) { errorBody(code) to status }
                    .decrypt(DecryptRequest("cipher".encodeToByteArray()))
            }.exceptionOrNull()!!

        assertTrue(decryptFailingWith("NotFoundException", HttpStatusCode.BadRequest) is NotFoundException)
        assertTrue(decryptFailingWith("DisabledException", HttpStatusCode.BadRequest) is DisabledException)
        assertTrue(
            decryptFailingWith("InvalidCiphertextException", HttpStatusCode.BadRequest)
                is InvalidCiphertextException,
        )
        assertTrue(
            decryptFailingWith("IncorrectKeyException", HttpStatusCode.BadRequest) is IncorrectKeyException,
        )
        assertTrue(
            decryptFailingWith("KMSInvalidStateException", HttpStatusCode.Conflict) is KmsInvalidStateException,
        )
    }

    /** An unrecognised code still arrives as a [KmsException] rather than as a raw transport failure. */
    @Test
    fun unknownCodesFallThroughToTheBaseType() = runTest {
        val failure = assertFailsWith<KmsException> {
            harnessKms(KmsHarness()) { errorBody("SomethingAwsAddedLater") to HttpStatusCode.BadRequest }
                .decrypt(DecryptRequest("cipher".encodeToByteArray()))
        }
        assertEquals("SomethingAwsAddedLater", failure.code)
        assertEquals(400, failure.statusCode)
    }

    @Test
    fun carriesTheCauseSoTheStackTraceSurvives() = runTest {
        val failure = assertFailsWith<NotFoundException> {
            harnessKms(KmsHarness()) { errorBody("NotFoundException") to HttpStatusCode.BadRequest }
                .decrypt(DecryptRequest("cipher".encodeToByteArray()))
        }
        assertTrue(failure.cause != null, "the transport exception was discarded")
    }
}

/**
 * The module's headline trap: KMS reports a bad signature as an error, not as a boolean.
 */
class KmsVerifyTest {

    private val request = VerifyRequest(
        keyId = "k",
        message = "msg".encodeToByteArray(),
        signature = "sig-bytes".encodeToByteArray(),
        signingAlgorithm = SigningAlgorithm.ECDSA_SHA_256,
    )

    @Test
    fun verifyThrowsRatherThanReportingSignatureValidFalse() = runTest {
        val kms = harnessKms(KmsHarness()) {
            """{"__type":"KMSInvalidSignatureException","message":"bad"}""" to HttpStatusCode.BadRequest
        }
        assertFailsWith<KmsInvalidSignatureException> { kms.verify(request) }
    }

    @Test
    fun verifySignatureAnswersFalseForAnInvalidSignature() = runTest {
        val kms = harnessKms(KmsHarness()) {
            """{"__type":"KMSInvalidSignatureException","message":"bad"}""" to HttpStatusCode.BadRequest
        }
        assertFalse(kms.verifySignature(request))
    }

    @Test
    fun verifySignatureAnswersTrueForAValidSignature() = runTest {
        val kms = harnessKms(KmsHarness()) {
            """{"SignatureValid":true,"KeyId":"arn:key","SigningAlgorithm":"ECDSA_SHA_256"}""" to
                HttpStatusCode.OK
        }
        assertTrue(kms.verifySignature(request))
    }

    /**
     * The distinction that makes this worth writing by hand instead of `runCatching { }`: an
     * unreachable KMS is *not* evidence that the signature is bad, and answering `false` would
     * reject a request that was never actually checked.
     */
    @Test
    fun verifySignatureDoesNotSwallowUnrelatedFailures() = runTest {
        val kms = harnessKms(KmsHarness()) {
            """{"__type":"DisabledException","message":"key disabled"}""" to HttpStatusCode.BadRequest
        }
        assertFailsWith<DisabledException> { kms.verifySignature(request) }
    }
}

class KmsConvenienceTest {

    @Test
    fun encryptConvenienceReturnsOnlyTheCiphertext() = runTest {
        val h = KmsHarness()
        val blob = harnessKms(h) { ENCRYPT_OK to HttpStatusCode.OK }
            .encrypt("alias/k", "hello".encodeToByteArray(), mapOf("tenant" to "acme"))

        assertEquals("cipher", blob.decodeToString())
        assertEquals("aGVsbG8=", bodyJson(h.bodies.single())["Plaintext"]?.jsonPrimitive?.content)
    }

    /**
     * The convenience overload requires a key id where the wire form makes it optional. That is the
     * point: a decrypt that names no key decrypts under whatever key the ciphertext names.
     */
    @Test
    fun decryptConvenienceAlwaysSendsTheKeyId() = runTest {
        val h = KmsHarness()
        val plaintext = harnessKms(h) { DECRYPT_OK to HttpStatusCode.OK }
            .decrypt("arn:key", "cipher".encodeToByteArray())

        assertEquals("plain", plaintext.decodeToString())
        assertEquals("arn:key", bodyJson(h.bodies.single())["KeyId"]?.jsonPrimitive?.content)
    }
}
