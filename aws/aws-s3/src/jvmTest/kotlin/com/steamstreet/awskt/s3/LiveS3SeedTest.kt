package com.steamstreet.awskt.s3

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Writes a handful of objects and **deliberately leaves them in the bucket**, so the results of the
 * hand-written client can be inspected in the S3 console.
 *
 * Separate from [LiveS3Test] and gated on its own `AWSKT_LIVE_SEED` flag, because everything else
 * in this module cleans up after itself and that property should not be weakened by a shared
 * switch. Nothing in a normal build, or in the live suite, can reach this.
 *
 * The keys are chosen to be legible evidence rather than a torture test: one ordinary key, one
 * nested prefix, and the awkward key whose encoding is the whole point of the milestone. Everything
 * lands under `awskt-demo/` so it is trivial to find and to delete.
 *
 * Run with:
 * ```
 * AWSKT_LIVE_BUCKET=<bucket> AWSKT_LIVE_SEED=1 ./gradlew :aws:aws-s3:jvmTest --tests '*LiveS3SeedTest*'
 * ```
 */
class LiveS3SeedTest {

    private val bucket: String? = System.getenv("AWSKT_LIVE_BUCKET")?.takeIf { it.isNotBlank() }
    private val seed: Boolean = System.getenv("AWSKT_LIVE_SEED")?.isNotBlank() == true
    private val profileName: String = System.getenv("AWSKT_LIVE_PROFILE") ?: "vegasful-test"
    private val region: String = System.getenv("AWS_REGION") ?: "us-west-2"

    @Test
    fun seedObjectsForConsoleInspection() {
        val bucket = bucket ?: return
        if (!seed) return

        val objects = listOf(
            "awskt-demo/hello.txt" to
                "Written by the hand-written awskt S3 client — no AWS SDK in this path.\n",
            "awskt-demo/nested/prefix/report.json" to
                """{"client":"awskt","sdk":false,"operations":["PutObject","GetObject","HeadObject"]}""",
            // The key the milestone exists for: a space, `..`, `+` and non-ASCII, all signed and
            // encoded exactly once. If the console shows this intact, the S3-mode encoding is right.
            "awskt-demo/a b/c..d/e+f/日本語.txt" to
                "Spaces, dot segments, a plus sign and non-ASCII — all round-tripped.\n",
        )

        runBlocking {
            S3 {
                region = this@LiveS3SeedTest.region
                credentialsProvider = profileCredentials(profileName)
            }.use { s3 ->
                for ((key, text) in objects) {
                    val body = text.encodeToByteArray()
                    s3.putObject(
                        PutObjectRequest(
                            bucket = bucket,
                            key = key,
                            body = body,
                            contentType = "text/plain; charset=utf-8",
                            metadata = mapOf("written-by" to "awskt-live-seed"),
                        ),
                    )

                    // Read each one back, so "it appears in the console" is backed by a real
                    // round trip rather than just a 200 on the write.
                    val got = s3.getObject(GetObjectRequest(bucket, key))
                    assertContentEquals(body, got.body, "round trip failed for '$key'")
                    assertEquals("awskt-live-seed", got.metadata["written-by"])
                    println("seeded s3://$bucket/$key (${body.size} bytes, etag=${got.eTag})")
                }
            }
        }
    }
}
