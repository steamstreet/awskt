package com.steamstreet.awskt.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof against real AWS, through the real transport.
 *
 * This lives in `commonTest` deliberately, so it runs on **both** jvm (CIO) and macosArm64 (Curl).
 * That single placement covers two things the plan asks for separately:
 *
 * 1. the whole M2 stack — credential resolution, endpoint resolution, signing, Ktor, response
 *    handling — works against AWS's real verifier, not just against MockEngine;
 * 2. `awsHttpClient()`'s **default** CA configuration works off-Linux. Hardcoding the AL2023 bundle
 *    path would pass every hermetic test and fail here, which is exactly why `caInfo` defaults to
 *    null and lets libcurl use the system trust store.
 *
 * Self-skips without credentials, so the suite stays hermetic and offline by default:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 ./gradlew :aws:aws-core:jvmTest :aws:aws-core:macosArm64Test
 * ```
 */
class LiveTransportTest {

    @Test
    fun listsTablesThroughTheSignedTransport() = runTest {
        if (platformGetEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) {
            println("[live] skipped — no AWS credentials in the environment")
            return@runTest
        }

        val region = platformGetEnv("AWS_REGION") ?: "us-west-2"
        val client = AwsServiceClient(
            httpClient = awsHttpClient(),
            credentialsProvider = defaultCredentialsProvider(),
            endpoint = resolveEndpoint("dynamodb", region),
            region = region,
            protocol = AwsProtocol.awsJson1_0("dynamodb", "DynamoDB_20120810"),
        )

        val response = client.callRaw(
            method = "POST",
            body = "{}".encodeToByteArray(),
            operation = "ListTables",
            safety = OperationSafety.IDEMPOTENT,
        )

        assertEquals(200, response.status)
        assertTrue(
            response.body.decodeToString().contains("TableNames"),
            "expected a ListTables payload, got: ${response.body.decodeToString().take(200)}",
        )
        println("[live] signed transport round-tripped DynamoDB: HTTP ${response.status}")
    }

    /**
     * A permission-free negative control: AWS must reject a signature made with a bogus secret.
     *
     * Without it, the test above would also pass if the request silently never left the process.
     */
    @Test
    fun awsRejectsASignatureMadeWithTheWrongSecret() = runTest {
        val accessKeyId = platformGetEnv("AWS_ACCESS_KEY_ID")
        if (accessKeyId.isNullOrEmpty()) {
            println("[live] skipped — no AWS credentials in the environment")
            return@runTest
        }

        val region = platformGetEnv("AWS_REGION") ?: "us-west-2"
        val client = AwsServiceClient(
            httpClient = awsHttpClient(),
            credentialsProvider = StaticCredentialsProvider(
                com.steamstreet.awskt.signing.AwsCredentials(
                    accessKeyId = accessKeyId,
                    secretAccessKey = "this-is-not-the-real-secret-access-key",
                    sessionToken = platformGetEnv("AWS_SESSION_TOKEN"),
                ),
            ),
            endpoint = resolveEndpoint("dynamodb", region),
            region = region,
            protocol = AwsProtocol.awsJson1_0("dynamodb", "DynamoDB_20120810"),
        )

        val error = kotlin.runCatching {
            client.callRaw("POST", body = "{}".encodeToByteArray(), operation = "ListTables")
        }.exceptionOrNull()

        assertTrue(error is AwsServiceException, "expected a service error, got $error")
        assertTrue(
            error.code == "InvalidSignatureException" || error.code == "UnrecognizedClientException",
            "expected AWS to reject the signature, got code=${error.code}",
        )
        println("[live] AWS rejected a bad signature, as it must — ${error.code}")
    }
}
