package com.steamstreet.awskt.s3

import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.awsHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The credentialed live smoke — M3.5a's exit criteria (d) and (e), and M3.5b's (e).
 *
 * **This is the milestone gate.** Everything else in this module proves our bytes are identical to
 * the AWS SDK's, which is strong evidence and is *not* the same as proving S3 accepts them. Only
 * this file talks to real S3.
 *
 * ### Gating
 *
 * Skipped unless `AWSKT_LIVE_BUCKET` names a real bucket, so the default build and CI stay green
 * without credentials. Matches the `AWSKT_LIVE_TABLE` convention `LiveDynamoDbTest` already uses.
 *
 * ### Credentials
 *
 * Resolved from a **named profile** through the AWS SDK, which is already on this source set's
 * classpath, and bridged into our own provider. Our `defaultCredentialsProvider()` reads environment
 * variables only — no profile file, no SSO — so without this bridge a profile-based live run would
 * be impossible. Doing it here rather than by exporting keys also means the secret is read inside
 * this JVM by the SDK and never passes through a shell, a log or a build file.
 *
 * ### What it leaves behind
 *
 * Nothing. Every object is written under a unique `awskt-live/<uuid>/` prefix and deleted in a
 * `finally`. The bucket itself is never created or removed by these tests.
 */
class LiveS3Test {

    private val bucket: String? = System.getenv("AWSKT_LIVE_BUCKET")?.takeIf { it.isNotBlank() }
    private val profileName: String = System.getenv("AWSKT_LIVE_PROFILE") ?: "vegasful-test"
    private val region: String = System.getenv("AWS_REGION") ?: "us-west-2"

    /** The key the milestone is actually about: spaces, `..`, `+`, `//`-adjacent segments, non-ASCII. */
    private val awkwardLeaf = "a b/c..d/e+f/日本語"

    private fun credentials(): AwsCredentialsProvider = profileCredentials(profileName)

    private fun s3() = S3 {
        region = this@LiveS3Test.region
        credentialsProvider = credentials()
    }

    private fun presigner() = S3Presigner {
        region = this@LiveS3Test.region
        credentialsProvider = credentials()
    }

    private fun prefix() = "awskt-live/${UUID.randomUUID()}"

    /**
     * M3.5b (e): put and get the awkward key byte-identically, a ranged read, a matching ETag from
     * HEAD, then delete and confirm the key is gone.
     *
     * **A `SignatureDoesNotMatch` on this key fails the milestone** — it is the exact input the
     * `doubleUriEncode` / `normalizeUriPath` flags change.
     */
    @Test
    fun liveObjectRoundTrip() {
        val bucket = bucket ?: return
        val key = "${prefix()}/$awkwardLeaf"
        val payload = "hello 日本語 body, with punctuation: +/..%".encodeToByteArray()

        runBlocking {
            s3().use { s3 ->
                try {
                    s3.putObject(
                        PutObjectRequest(
                            bucket = bucket,
                            key = key,
                            body = payload,
                            contentType = "text/plain; charset=utf-8",
                            metadata = mapOf("owner" to "awskt-live-test"),
                        ),
                    )

                    val got = s3.getObject(GetObjectRequest(bucket, key))
                    assertContentEquals(payload, got.body, "round-tripped bytes must be identical")
                    assertEquals(payload.size.toLong(), got.contentLength)
                    assertEquals("awskt-live-test", got.metadata["owner"])

                    val ranged = s3.getObject(GetObjectRequest(bucket, key, range = "bytes=0-9"))
                    assertEquals(10, ranged.body.size, "a ranged read must return exactly the range")
                    assertContentEquals(payload.copyOfRange(0, 10), ranged.body)
                    assertNotNull(ranged.contentRange, "a 206 must carry Content-Range")

                    val head = s3.headObject(HeadObjectRequest(bucket, key))
                    assertEquals(got.eTag, head.eTag, "HEAD and GET must agree on the ETag")
                    assertEquals(payload.size.toLong(), head.contentLength)

                    s3.deleteObject(DeleteObjectRequest(bucket, key))
                    assertFailsWith<NoSuchKeyException> { s3.getObject(GetObjectRequest(bucket, key)) }
                } finally {
                    runCatching { s3.deleteObject(DeleteObjectRequest(bucket, key)) }
                }
            }
        }
    }

    /**
     * M3.5a (d), read half: a presigned GET fetched by a client carrying **no credentials and no
     * signer** must return the exact bytes.
     */
    @Test
    fun livePresignedGetIsFetchableWithoutCredentials() {
        val bucket = bucket ?: return
        val key = "${prefix()}/presigned-get/$awkwardLeaf"
        val payload = "presigned get payload 日本語".encodeToByteArray()

        runBlocking {
            s3().use { s3 ->
                try {
                    s3.putObject(PutObjectRequest(bucket, key, payload))

                    val url = presigner().presignGetObject(bucket, key, 15.minutesDuration())

                    awsHttpClient().use { bare ->
                        val response = bare.get(url.url)
                        assertEquals(200, response.status.value, "presigned GET must succeed unauthenticated")
                        assertContentEquals(payload, response.readRawBytes())
                    }
                } finally {
                    runCatching { s3.deleteObject(DeleteObjectRequest(bucket, key)) }
                }
            }
        }
    }

    /** M3.5a (d), write half: a presigned PUT accepted from an unauthenticated client. */
    @Test
    fun livePresignedPutIsAcceptedWithoutCredentials() {
        val bucket = bucket ?: return
        val key = "${prefix()}/presigned-put/$awkwardLeaf"
        val payload = "presigned put payload 日本語".encodeToByteArray()

        runBlocking {
            s3().use { s3 ->
                try {
                    val url = presigner().presignPutObject(bucket, key, 15.minutesDuration())

                    awsHttpClient().use { bare ->
                        val response = bare.put(url.url) { setBody(payload) }
                        assertTrue(
                            response.status.value in 200..299,
                            "presigned PUT failed: ${response.status} ${response.bodyAsText()}",
                        )
                    }

                    val got = s3.getObject(GetObjectRequest(bucket, key))
                    assertContentEquals(payload, got.body, "the object read back must match what was PUT")
                } finally {
                    runCatching { s3.deleteObject(DeleteObjectRequest(bucket, key)) }
                }
            }
        }
    }

    /** M3.5a (e): a one-second URL is refused once it has expired. */
    @Test
    fun liveExpiredPresignedUrlIsRejected() {
        val bucket = bucket ?: return
        val key = "${prefix()}/expiring/object.txt"

        runBlocking {
            s3().use { s3 ->
                try {
                    s3.putObject(PutObjectRequest(bucket, key, "short lived".encodeToByteArray()))

                    val url = presigner().presignGetObject(bucket, key, 1.secondsDuration())
                    Thread.sleep(3_000)

                    awsHttpClient().use { bare ->
                        val response = bare.get(url.url)
                        assertEquals(403, response.status.value, "an expired URL must be refused")
                        assertContains(response.bodyAsText(), "Request has expired")
                    }
                } finally {
                    runCatching { s3.deleteObject(DeleteObjectRequest(bucket, key)) }
                }
            }
        }
    }
}

private fun Int.minutesDuration() = kotlin.time.Duration.parse("${this}m")
private fun Int.secondsDuration() = kotlin.time.Duration.parse("${this}s")
