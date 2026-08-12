package com.steamstreet.awskt.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Addressing-style resolution.
 *
 * The theme of every case here: a name that is not virtual-host-compatible is a **routing
 * decision**, never a rejection. Legacy `us-east-1` buckets with uppercase letters, underscores and
 * 255-character names still exist and are still reachable path-style, so refusing to call them
 * would be this library inventing a restriction AWS does not have.
 */
class S3EndpointTest {

    private val noEnv: (String) -> String? = { null }

    @Test
    fun dnsCompatibleBucketIsVirtualHosted() {
        val e = resolveS3Endpoint("my-bucket", "us-west-2", getEnv = noEnv)
        assertEquals("https://my-bucket.s3.us-west-2.amazonaws.com", e.origin)
        assertEquals("", e.basePath)
        assertEquals(S3AddressingStyle.VIRTUAL_HOSTED, e.style)
    }

    /**
     * `us-east-1` resolves to the **regional** endpoint, never the legacy global
     * `s3.amazonaws.com`. Buckets in regions launched after 2019-03-20 return HTTP 400 from the
     * global endpoint, so the friendly-looking alias is a region-dependent landmine.
     */
    @Test
    fun usEast1UsesTheRegionalEndpoint() {
        val e = resolveS3Endpoint("my-bucket", "us-east-1", getEnv = noEnv)
        assertEquals("https://my-bucket.s3.us-east-1.amazonaws.com", e.origin)
    }

    @Test
    fun forcePathStyleIsHonoured() {
        val e = resolveS3Endpoint("my-bucket", "us-west-2", forcePathStyle = true, getEnv = noEnv)
        assertEquals("https://s3.us-west-2.amazonaws.com", e.origin)
        assertEquals("/my-bucket", e.basePath)
        assertEquals(S3AddressingStyle.PATH, e.style)
    }

    /**
     * A dotted bucket is legal and common — AWS's own documented path-style example is
     * `example.com` — but `my.bucket.s3.region.amazonaws.com` is two labels deep and AWS's
     * wildcard certificate is single-label, so virtual-hosted HTTPS fails hostname verification.
     * Falling back is strictly better than an opaque TLS error.
     */
    @Test
    fun dottedBucketFallsBackToPathStyle() {
        val e = resolveS3Endpoint("my.bucket", "us-west-2", getEnv = noEnv)
        assertEquals(S3AddressingStyle.PATH, e.style)
        assertEquals("/my.bucket", e.basePath)
    }

    @Test
    fun legacyUppercaseNameResolvesToPathStyleAndDoesNotThrow() {
        val e = resolveS3Endpoint("MyLegacyBucket", "us-east-1", getEnv = noEnv)
        assertEquals(S3AddressingStyle.PATH, e.style)
        assertEquals("/MyLegacyBucket", e.basePath)
    }

    @Test
    fun legacyUnderscoreNameResolvesToPathStyle() {
        assertEquals(S3AddressingStyle.PATH, resolveS3Endpoint("my_bucket", "us-east-1", getEnv = noEnv).style)
    }

    @Test
    fun overlongLegacyNameResolvesToPathStyle() {
        val name = "a".repeat(120)
        assertEquals(S3AddressingStyle.PATH, resolveS3Endpoint(name, "us-east-1", getEnv = noEnv).style)
    }

    @Test
    fun shortNameResolvesToPathStyle() {
        assertEquals(S3AddressingStyle.PATH, resolveS3Endpoint("ab", "us-east-1", getEnv = noEnv).style)
    }

    /** Every local S3 implementation is path-style, and an override host must not gain a label. */
    @Test
    fun endpointOverrideForcesPathStyle() {
        val e = resolveS3Endpoint(
            "my-bucket",
            "us-west-2",
            endpointOverride = "https://minio.internal:9000",
            getEnv = noEnv,
        )
        assertEquals("https://minio.internal:9000", e.origin)
        assertEquals("/my-bucket", e.basePath)
        assertEquals(S3AddressingStyle.PATH, e.style)
    }

    @Test
    fun awsEndpointUrlS3EnvironmentVariableIsHonoured() {
        val e = resolveS3Endpoint("my-bucket", "us-west-2") { name ->
            if (name == "AWS_ENDPOINT_URL_S3") "https://s3.example.internal" else null
        }
        assertEquals("https://s3.example.internal", e.origin)
    }

    @Test
    fun serviceSpecificEnvironmentVariableBeatsTheGeneralOne() {
        val e = resolveS3Endpoint("my-bucket", "us-west-2") { name ->
            when (name) {
                "AWS_ENDPOINT_URL_S3" -> "https://specific.example"
                "AWS_ENDPOINT_URL" -> "https://general.example"
                else -> null
            }
        }
        assertEquals("https://specific.example", e.origin)
    }

    // -- The only names that are actually refused ------------------------------------------

    @Test
    fun blankBucketIsRejected() {
        assertFailsWith<InvalidBucketNameException> { resolveS3Endpoint("  ", "us-west-2", getEnv = noEnv) }
    }

    @Test
    fun bucketWithASlashIsRejected() {
        assertFailsWith<InvalidBucketNameException> { resolveS3Endpoint("a/b", "us-west-2", getEnv = noEnv) }
    }

    // -- HTTPS ------------------------------------------------------------------------------

    @Test
    fun plaintextEndpointIsRejectedByDefault() {
        assertFailsWith<InsecureEndpointException> {
            resolveS3Endpoint("b", "us-west-2", endpointOverride = "http://s3.example.com", getEnv = noEnv)
        }
    }

    @Test
    fun plaintextLoopbackIsAllowedWhenOptedIn() {
        val e = resolveS3Endpoint(
            "b",
            "us-west-2",
            endpointOverride = "http://localhost:4566",
            allowInsecureEndpoint = true,
            getEnv = noEnv,
        )
        assertEquals("http://localhost:4566", e.origin)
    }

    /** The opt-in is for LocalStack, not for plaintext in general. */
    @Test
    fun plaintextNonLoopbackIsRejectedEvenWhenOptedIn() {
        assertFailsWith<InsecureEndpointException> {
            resolveS3Endpoint(
                "b",
                "us-west-2",
                endpointOverride = "http://s3.example.com",
                allowInsecureEndpoint = true,
                getEnv = noEnv,
            )
        }
    }
}
