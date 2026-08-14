package com.steamstreet.awskt.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
private const val TOKEN = "FQoGZXIvYXdzEExampleSessionTokenValue=="
private val CREDENTIALS = AwsCredentials("AKIDEXAMPLE", SECRET, TOKEN)
private const val AT = 1_440_938_160_000L // 2015-08-30T12:36:00Z

private fun sign(
    request: SigningRequest,
    config: SigV4Config,
    credentials: AwsCredentials = CREDENTIALS,
) = SigV4.sign(request, credentials, config, AT)

class SignerBehaviourTest {

    // -----------------------------------------------------------------------
    // Host and port
    // -----------------------------------------------------------------------

    /**
     * Every existing integration test in this repo runs against LocalStack on an ephemeral port,
     * and the entire AWS fixture corpus uses default ports. A signer that drops the port passes
     * 100% of both and fails 100% of production — so this is asserted explicitly.
     */
    @Test
    fun hostIncludesNonDefaultPort() {
        val signed = sign(
            SigningRequest("GET", "/", host = "localhost:4566"),
            SigV4Config(region = "us-east-1", service = "dynamodb"),
        )

        assertTrue(signed.canonicalRequest.contains("host:localhost:4566"))
        assertEquals("localhost:4566", signed.headers.first { it.first == "Host" }.second)
    }

    @Test
    fun hostIsSignedEvenWhenPresigning() {
        // smithy-kotlin omits this and relies on the caller's header set; when absent the result is
        // an empty X-Amz-SignedHeaders and a URL AWS rejects.
        val signed = sign(
            SigningRequest("GET", "/object.txt", host = "bucket.s3.us-east-1.amazonaws.com"),
            SigV4Config(
                region = "us-east-1",
                service = "s3",
                location = SignatureLocation.QUERY_STRING,
                expiresInSeconds = 300,
                payloadHash = PayloadHash.Unsigned,
                doubleUriEncode = false,
                normalizeUriPath = false,
            ),
        )

        assertEquals(listOf("host"), signed.signedHeaderNames)
        assertTrue(signed.canonicalRequest.contains("host:bucket.s3.us-east-1.amazonaws.com"))
    }

    // -----------------------------------------------------------------------
    // Presign shape
    // -----------------------------------------------------------------------

    @Test
    fun presignEmitsNoAuthorizationHeaderAndNoContentHashHeader() {
        val signed = sign(
            SigningRequest("GET", "/o", host = "b.s3.amazonaws.com"),
            SigV4Config(
                region = "us-east-1",
                service = "s3",
                location = SignatureLocation.QUERY_STRING,
                expiresInSeconds = 900,
                payloadHash = PayloadHash.Unsigned,
                signedBodyHeader = SignedBodyHeader.X_AMZ_CONTENT_SHA256,
            ),
        )

        val names = signed.headers.map { it.first.lowercase() }
        assertFalse("authorization" in names)
        assertFalse("x-amz-content-sha256" in names)
        assertTrue(signed.queryParameters.any { it.first == "X-Amz-Signature" })
        assertTrue(signed.canonicalRequest.endsWith(UNSIGNED_PAYLOAD))
    }

    @Test
    fun presignSignatureIsNeverCanonicalized() {
        val signed = sign(
            SigningRequest("GET", "/o", host = "b.s3.amazonaws.com"),
            SigV4Config(
                region = "us-east-1",
                service = "s3",
                location = SignatureLocation.QUERY_STRING,
                expiresInSeconds = 60,
                payloadHash = PayloadHash.Unsigned,
            ),
        )
        assertFalse(signed.canonicalRequest.contains("X-Amz-Signature"))
    }

    @Test
    fun presignedQueryStringNeedNotBeSorted() {
        val signed = sign(
            SigningRequest("GET", "/o", host = "b.s3.amazonaws.com"),
            SigV4Config(
                region = "us-east-1",
                service = "s3",
                location = SignatureLocation.QUERY_STRING,
                expiresInSeconds = 60,
                payloadHash = PayloadHash.Unsigned,
            ),
        )
        // Sorting is a canonicalization rule, not a URL-construction rule.
        assertTrue(signed.encodedQueryString().contains("X-Amz-Signature="))
        assertTrue(signed.encodedQueryString().contains("X-Amz-Credential=AKIDEXAMPLE%2F20150830"))
    }

    @Test
    fun presignExpiryIsCappedAtSevenDays() {
        val error = assertFailsWith<IllegalArgumentException> {
            SigV4Config(
                region = "us-east-1",
                service = "s3",
                location = SignatureLocation.QUERY_STRING,
                expiresInSeconds = SigV4Config.MAX_PRESIGN_EXPIRY_SECONDS + 1,
            )
        }
        assertTrue(error.message!!.contains("604800"))

        // The boundary itself is legal.
        SigV4Config(
            region = "us-east-1",
            service = "s3",
            location = SignatureLocation.QUERY_STRING,
            expiresInSeconds = SigV4Config.MAX_PRESIGN_EXPIRY_SECONDS,
        )
    }

    @Test
    fun expiryIsRejectedForHeaderSigning() {
        assertFailsWith<IllegalArgumentException> {
            SigV4Config(region = "us-east-1", service = "s3", expiresInSeconds = 60)
        }
        assertFailsWith<IllegalArgumentException> {
            SigV4Config(
                region = "us-east-1",
                service = "s3",
                location = SignatureLocation.QUERY_STRING,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Header handling
    // -----------------------------------------------------------------------

    @Test
    fun volatileHeadersAreNotSigned() {
        val signed = sign(
            SigningRequest(
                "POST",
                "/",
                host = "dynamodb.us-east-1.amazonaws.com",
                headers = listOf(
                    "Content-Type" to "application/x-amz-json-1.0",
                    "X-Amz-Target" to "DynamoDB_20120810.GetItem",
                    // All of the below must be excluded.
                    "User-Agent" to "ktor-client",
                    "Content-Length" to "42",
                    "Connection" to "keep-alive",
                    "X-Amzn-Trace-Id" to "Root=1-x",
                    "Transfer-Encoding" to "chunked",
                    "Expect" to "100-continue",
                ),
            ),
            SigV4Config(region = "us-east-1", service = "dynamodb"),
        )

        assertEquals(
            listOf("content-type", "host", "x-amz-date", "x-amz-security-token", "x-amz-target"),
            signed.signedHeaderNames,
        )
    }

    /**
     * `content-length` and `user-agent` are excluded for the same reason: Ktor writes both after we
     * sign, so signing a value we did not author breaks on any engine or version change.
     */
    @Test
    fun contentLengthIsExcludedDeliberately() {
        val withLength = sign(
            SigningRequest(
                "PUT", "/", host = "b.s3.amazonaws.com",
                headers = listOf("Content-Length" to "13"),
            ),
            SigV4Config(region = "us-east-1", service = "s3"),
        )
        val withoutLength = sign(
            SigningRequest("PUT", "/", host = "b.s3.amazonaws.com"),
            SigV4Config(region = "us-east-1", service = "s3"),
        )
        assertEquals(withoutLength.signature, withLength.signature)
    }

    @Test
    fun duplicateHeaderValuesJoinInEncounterOrder() {
        val signed = sign(
            SigningRequest(
                "GET", "/", host = "example.amazonaws.com",
                headers = listOf(
                    "My-Header1" to "value4",
                    "My-Header1" to "value1",
                    "My-Header1" to "value3",
                ),
            ),
            SigV4Config(region = "us-east-1", service = "service"),
        )
        assertTrue(signed.canonicalRequest.contains("my-header1:value4,value1,value3"))
    }

    @Test
    fun headerValuesAreTrimmedAndInternallyCollapsed() {
        assertEquals("a b c", canonicalHeaderValue("  \"a   b   c\"  ".trim('"', ' ')))
        assertEquals("value1", canonicalHeaderValue(" value1 "))
        assertEquals("\"a b c\"", canonicalHeaderValue("\"a   b   c\""))
    }

    @Test
    fun emptyPathCanonicalizesToSlash() {
        assertEquals("/", canonicalUri("", doubleUriEncode = true, normalize = true))
        assertEquals("/", canonicalUri("/", doubleUriEncode = true, normalize = true))
    }

    // -----------------------------------------------------------------------
    // Payload hashing
    // -----------------------------------------------------------------------

    @Test
    fun payloadHashModesProduceTheExpectedCanonicalTail() {
        val request = SigningRequest(
            "PUT", "/o", host = "b.s3.amazonaws.com", body = "hello".encodeToByteArray(),
        )
        fun tail(hash: PayloadHash) =
            sign(request, SigV4Config("us-east-1", "s3", payloadHash = hash))
                .canonicalRequest.substringAfterLast('\n')

        assertEquals(EMPTY_BODY_SHA256, tail(PayloadHash.EmptyBody))
        assertEquals(UNSIGNED_PAYLOAD, tail(PayloadHash.Unsigned))
        assertEquals("deadbeef", tail(PayloadHash.Precomputed("deadbeef")))
        // SHA-256("hello")
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            tail(PayloadHash.Compute),
        )
    }

    @Test
    fun signingNameOverridesAreApplied() {
        assertEquals("dynamodb", signingNameFor("dynamodb-streams"))
        assertEquals("appconfig", signingNameFor("appconfigdata"))
        assertEquals("s3", signingNameFor("s3"))
        assertEquals("dynamodb", signingNameFor("dynamodb"))
    }

    // -----------------------------------------------------------------------
    // Redaction
    // -----------------------------------------------------------------------

    /**
     * This module sits in the auth path of a published library, and `logging`'s `HttpLogPublisher`
     * PUTs log payloads to an arbitrary URL. Nothing here may carry credential material into a
     * string.
     */
    @Test
    fun credentialMaterialNeverAppearsInToString() {
        val credentials = AwsCredentials("AKIDEXAMPLE", SECRET, TOKEN)
        assertFalse(SECRET in credentials.toString())
        assertFalse(TOKEN in credentials.toString())
        assertTrue("AKIDEXAMPLE" in credentials.toString())

        val config = SigV4Config(region = "us-east-1", service = "s3")
        assertFalse(SECRET in config.toString())

        val request = SigningRequest("GET", "/", host = "h", body = SECRET.encodeToByteArray())
        assertFalse(SECRET in request.toString())
    }

    @Test
    fun signedRequestToStringLeaksNeitherSignatureNorToken() {
        val signed = sign(
            SigningRequest("GET", "/o", host = "b.s3.amazonaws.com"),
            SigV4Config(
                region = "us-east-1",
                service = "s3",
                location = SignatureLocation.QUERY_STRING,
                expiresInSeconds = 300,
                payloadHash = PayloadHash.Unsigned,
            ),
        )

        // A presigned URL *is* a credential; its rendered form must not appear via toString().
        val rendered = signed.toString()
        assertFalse(signed.signature in rendered)
        assertFalse(TOKEN in rendered)
        assertFalse(SECRET in rendered)
    }

    @Test
    fun configValidationErrorsCarryNoCredentialMaterial() {
        val error = assertFailsWith<IllegalArgumentException> {
            SigV4Config(
                region = "us-east-1",
                service = "s3",
                location = SignatureLocation.QUERY_STRING,
                expiresInSeconds = 999_999_999,
            )
        }
        assertFalse(SECRET in error.stackTraceToString())
        assertFalse(TOKEN in error.stackTraceToString())
    }

    // -----------------------------------------------------------------------
    // Signing key cache
    // -----------------------------------------------------------------------

    @Test
    fun cachedSigningKeyIsScopedToDateRegionServiceAndCredential() {
        val base = deriveSigningKey(SECRET, "20150830", "us-east-1", "dynamodb")

        assertFalse(base.contentEquals(deriveSigningKey(SECRET, "20150831", "us-east-1", "dynamodb")))
        assertFalse(base.contentEquals(deriveSigningKey(SECRET, "20150830", "us-west-2", "dynamodb")))
        assertFalse(base.contentEquals(deriveSigningKey(SECRET, "20150830", "us-east-1", "s3")))
        assertTrue(base.contentEquals(deriveSigningKey(SECRET, "20150830", "us-east-1", "dynamodb")))
    }

    /** A long-lived container crossing midnight must re-derive rather than sign with a stale key. */
    @Test
    fun signingAcrossMidnightUsesDifferentKeys() {
        val request = SigningRequest("GET", "/", host = "example.amazonaws.com")
        val config = SigV4Config(region = "us-east-1", service = "service")

        val before = SigV4.sign(request, CREDENTIALS, config, parseIso8601Utc("2015-08-30T23:59:59Z"))
        val after = SigV4.sign(request, CREDENTIALS, config, parseIso8601Utc("2015-08-31T00:00:01Z"))

        assertFalse(before.signature == after.signature)
        assertTrue(before.stringToSign.contains("20150830/us-east-1/service/aws4_request"))
        assertTrue(after.stringToSign.contains("20150831/us-east-1/service/aws4_request"))
    }

    /**
     * Rotation replaces both halves of a credential, so this alternates both — and then returns to
     * the first pair, which is what actually exercises the cache being evicted and re-derived
     * rather than merely never hit.
     */
    @Test
    fun rotatedCredentialsDoNotReuseACachedKey() {
        val request = SigningRequest("GET", "/", host = "example.amazonaws.com")
        val config = SigV4Config(region = "us-east-1", service = "service")
        val first = AwsCredentials("AKIDONE", SECRET)
        val second = AwsCredentials("AKIDTWO", "a-completely-different-secret-access-key")

        val a = SigV4.sign(request, first, config, AT)
        val b = SigV4.sign(request, second, config, AT)
        val c = SigV4.sign(request, first, config, AT)

        assertFalse(a.signature == b.signature)
        assertEquals(a.signature, c.signature)
    }

    /**
     * In header mode the access key id is carried only in the `Authorization` header, which is not
     * itself signed — so the id alone does not affect the signature. Asserted so that the
     * surprising-but-correct behaviour is recorded rather than rediscovered.
     */
    @Test
    fun headerModeSignatureDependsOnTheSecretNotTheAccessKeyId() {
        val request = SigningRequest("GET", "/", host = "example.amazonaws.com")
        val config = SigV4Config(region = "us-east-1", service = "service")

        val one = SigV4.sign(request, AwsCredentials("AKIDONE", SECRET), config, AT)
        val two = SigV4.sign(request, AwsCredentials("AKIDTWO", SECRET), config, AT)

        assertEquals(one.signature, two.signature)
        assertTrue(one.headers.any { it.second.contains("Credential=AKIDONE/") })
        assertTrue(two.headers.any { it.second.contains("Credential=AKIDTWO/") })
    }

    /**
     * Regression: the signing-key cache must key on the secret, not only the access key id.
     *
     * Keyed on the id alone, the second call here returns the first call's key and produces a
     * signature that is valid for a secret the caller did not supply. A live negative control found
     * this by signing with a deliberately-wrong secret and having AWS accept the request.
     */
    @Test
    fun sameAccessKeyIdWithADifferentSecretDoesNotReuseTheCachedKey() {
        val request = SigningRequest("GET", "/", host = "example.amazonaws.com")
        val config = SigV4Config(region = "us-east-1", service = "service")

        val real = SigV4.sign(request, AwsCredentials("AKIDEXAMPLE", SECRET), config, AT)
        val bogus = SigV4.sign(
            request,
            AwsCredentials("AKIDEXAMPLE", "this-is-not-the-real-secret-access-key"),
            config,
            AT,
        )

        assertFalse(
            real.signature == bogus.signature,
            "a different secret under the same access key id must produce a different signature",
        )
    }

    /**
     * The workload a single-slot cache could not serve at all.
     *
     * A Lambda that alternates DynamoDB and S3 calls evicted the other service's key on every sign,
     * so the cache had a 0% hit rate on exactly the container it exists to speed up and re-derived
     * four HMACs per request. The derivation count is the assertion that matters — signatures alone
     * stay correct however badly the cache behaves, which is what let the thrashing go unnoticed.
     */
    @Test
    fun alternatingServicesReuseBothCachedKeys() {
        resetSigningKeyCacheForTest()
        val request = SigningRequest("POST", "/", host = "example.amazonaws.com")
        val dynamo = SigV4Config(region = "us-east-1", service = "dynamodb")
        val s3 = SigV4Config(region = "us-east-1", service = "s3")

        val expectedDynamo = SigV4.sign(request, CREDENTIALS, dynamo, AT).signature
        val expectedS3 = SigV4.sign(request, CREDENTIALS, s3, AT).signature
        assertFalse(expectedDynamo == expectedS3, "the two services must not sign identically")
        assertEquals(2, signingKeyDerivations, "one derivation per service to prime the cache")

        repeat(10) {
            assertEquals(expectedDynamo, SigV4.sign(request, CREDENTIALS, dynamo, AT).signature)
            assertEquals(expectedS3, SigV4.sign(request, CREDENTIALS, s3, AT).signature)
        }

        assertEquals(2, signingKeyDerivations, "alternating services must not evict each other")
    }

    /**
     * More live scopes than slots: entries are evicted, and every signature stays correct.
     *
     * The behavioural floor for the cache as a whole — a wrong eviction shows up here as a wrong
     * signature rather than as a missed optimisation.
     */
    @Test
    fun moreServicesThanSlotsStillSignCorrectly() {
        resetSigningKeyCacheForTest()
        val request = SigningRequest("POST", "/", host = "example.amazonaws.com")
        val configs = listOf("dynamodb", "s3", "sqs", "sns", "kinesis")
            .associateWith { SigV4Config(region = "us-east-1", service = it) }

        val expected = configs.mapValues { (_, config) ->
            SigV4.sign(request, CREDENTIALS, config, AT).signature
        }
        assertEquals(configs.size, expected.values.toSet().size, "each service must sign differently")

        repeat(3) {
            for ((service, config) in configs) {
                assertEquals(
                    expected[service],
                    SigV4.sign(request, CREDENTIALS, config, AT).signature,
                    "$service signed differently after an eviction",
                )
            }
        }
    }

    /**
     * A credentials provider that hands back a *new* object carrying the same values — a refresh
     * that changed nothing — must not re-derive.
     *
     * The reference comparison on the fast path cannot answer this, so the digest-keyed identity
     * does. The cost is one SHA-256 on that call and no HMACs; see [signingKey]'s KDoc.
     */
    @Test
    fun aFreshCredentialsObjectCarryingTheSameValuesDoesNotReDerive() {
        resetSigningKeyCacheForTest()
        val request = SigningRequest("GET", "/", host = "example.amazonaws.com")
        val config = SigV4Config(region = "us-east-1", service = "service")

        val first = SigV4.sign(request, AwsCredentials("AKIDEXAMPLE", SECRET), config, AT)
        assertEquals(1, signingKeyDerivations)

        val second = SigV4.sign(request, AwsCredentials("AKIDEXAMPLE", SECRET), config, AT)
        assertEquals(first.signature, second.signature)
        assertEquals(1, signingKeyDerivations, "identical credentials must not re-derive")
    }

    /** Presigning *does* bind the access key id, because `X-Amz-Credential` is canonicalized. */
    @Test
    fun presignSignatureDependsOnTheAccessKeyId() {
        val request = SigningRequest("GET", "/o", host = "b.s3.amazonaws.com")
        val config = SigV4Config(
            region = "us-east-1",
            service = "s3",
            location = SignatureLocation.QUERY_STRING,
            expiresInSeconds = 300,
            payloadHash = PayloadHash.Unsigned,
        )

        val one = SigV4.sign(request, AwsCredentials("AKIDONE", SECRET), config, AT)
        val two = SigV4.sign(request, AwsCredentials("AKIDTWO", SECRET), config, AT)

        assertFalse(one.signature == two.signature)
    }
}
