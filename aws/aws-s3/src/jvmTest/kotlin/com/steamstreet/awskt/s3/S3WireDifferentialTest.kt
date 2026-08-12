package com.steamstreet.awskt.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The request half of the differential, for all four operations.
 *
 * ### What is compared, precisely
 *
 * **The request line** — method, encoded path and sorted query — as an exact string. That is where
 * S3-mode key encoding and the `x-id` literal live, so it is where the two flags this module exists
 * to get right (`doubleUriEncode = false`, `normalizeUriPath = false`) actually show up.
 *
 * **`x-amz-content-sha256`**, because a payload-hash divergence is a 403 that reads like a signer
 * bug.
 *
 * The full header *set* is deliberately not compared: the SDK attaches its own user-agent,
 * telemetry and retry-token headers that are no part of the protocol, so asserting set equality
 * would be asserting on SDK internals and would break on every SDK bump.
 */
class S3WireDifferentialTest {

    private val bucket = "example-bucket"

    private class ShortCircuit : RuntimeException("captured; not sending")

    private class CapturingInterceptor : HttpInterceptor {
        var captured: HttpRequest? = null
        override fun readBeforeTransmit(context: ProtocolRequestInterceptorContext<Any, HttpRequest>) {
            captured = context.protocolRequest
            throw ShortCircuit()
        }
    }

    /** Runs an SDK operation and returns its request line plus content hash. */
    private fun sdkRequest(block: suspend (S3Client) -> Unit): Pair<String, String?> {
        val interceptor = CapturingInterceptor()
        val client = S3Client {
            region = "us-west-2"
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "AKIDEXAMPLE"
                secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
            }
            interceptors += interceptor
        }
        client.use {
            runBlocking {
                try {
                    block(it)
                } catch (e: Throwable) {
                    if (generateSequence(e) { it.cause }.none { it is ShortCircuit }) throw e
                }
            }
        }
        val request = interceptor.captured ?: error("the SDK request was never captured")
        return requestLine(request.method.name, request.url.toString()) to
            request.headers["x-amz-content-sha256"]
    }

    /** Runs the same operation against ours and returns the same two things. */
    private fun ourRequest(block: suspend (S3) -> Unit): Pair<String, String?> {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                "",
                HttpStatusCode.OK,
                headersOf("Content-Length" to listOf("0"), "ETag" to listOf("\"abc\"")),
            )
        }
        val s3 = S3 {
            region = "us-west-2"
            credentialsProvider = AwsCredentialsProvider {
                AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
            }
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false }
        }
        runBlocking { s3.use { block(it) } }
        val request = captured ?: error("no request was made")
        return requestLine(request.method.value, request.url.toString()) to
            request.headers["x-amz-content-sha256"]
    }

    /** `METHOD origin/path?sorted&query` — query order is not a wire-correctness property. */
    private fun requestLine(method: String, url: String): String {
        val prefix = url.substringBefore('?')
        val query = if ('?' in url) url.substringAfter('?').split("&").sorted().joinToString("&") else ""
        return "$method $prefix?$query"
    }

    private fun assertSame(label: String, sdk: Pair<String, String?>, ours: Pair<String, String?>) {
        assertEquals(sdk.first, ours.first, "$label: request line")
        assertEquals(sdk.second, ours.second, "$label: x-amz-content-sha256")
    }

    private val awkwardKey = "a b/c..d/e+f/日本語"

    @Test
    fun getObjectMatchesTheSdk() = assertSame(
        "GetObject",
        sdkRequest { it.getObject(aws.sdk.kotlin.services.s3.model.GetObjectRequest {
            bucket = this@S3WireDifferentialTest.bucket
            key = awkwardKey
        }) {} },
        ourRequest { it.getObject(GetObjectRequest(bucket, awkwardKey)) },
    )

    @Test
    fun putObjectMatchesTheSdk() = assertSame(
        "PutObject",
        sdkRequest {
            it.putObject {
                bucket = this@S3WireDifferentialTest.bucket
                key = awkwardKey
                body = ByteStream.fromBytes("hello".encodeToByteArray())
            }
        },
        ourRequest { it.putObject(PutObjectRequest(bucket, awkwardKey, "hello".encodeToByteArray())) },
    )

    /** HEAD must carry **no** `x-id`; the SDK is the oracle for that too. */
    @Test
    fun headObjectMatchesTheSdk() = assertSame(
        "HeadObject",
        sdkRequest {
            it.headObject {
                bucket = this@S3WireDifferentialTest.bucket
                key = awkwardKey
            }
        },
        ourRequest { it.headObject(HeadObjectRequest(bucket, awkwardKey)) },
    )

    @Test
    fun deleteObjectMatchesTheSdk() = assertSame(
        "DeleteObject",
        sdkRequest {
            it.deleteObject {
                bucket = this@S3WireDifferentialTest.bucket
                key = awkwardKey
            }
        },
        ourRequest { it.deleteObject(DeleteObjectRequest(bucket, awkwardKey)) },
    )
}
