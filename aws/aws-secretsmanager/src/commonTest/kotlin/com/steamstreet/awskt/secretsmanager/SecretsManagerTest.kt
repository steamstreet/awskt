package com.steamstreet.awskt.secretsmanager

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class SecretsHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessSecrets(
    harness: SecretsHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): SecretsManager {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultSecretsManager(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint(
                "secretsmanager", "us-west-2", "https://secretsmanager.us-west-2.amazonaws.com",
            ),
            region = "us-west-2",
            protocol = SECRETS_MANAGER_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

private const val GET_OK =
    """{"ARN":"arn:aws:secretsmanager:us-west-2:1:secret:db-AbCdEf","Name":"db",""" +
        """"VersionId":"v1","SecretString":"{\"user\":\"root\"}","VersionStages":["AWSCURRENT"],""" +
        """"CreatedDate":1.7e9}"""

class SecretsManagerProtocolTest {

    @Test
    fun targetsSecretsManagerOverAwsJson1_1() = runTest {
        val h = SecretsHarness()
        harnessSecrets(h) { GET_OK to HttpStatusCode.OK }.getSecretValue(GetSecretValueRequest("db"))

        val request = h.requests.single()
        assertEquals("secretsmanager.GetSecretValue", request.headers["X-Amz-Target"])
        // Content type lives on the body, not in `headers` — Ktor moves it there.
        assertEquals("application/x-amz-json-1.1", request.body.contentType?.toString())
    }

    @Test
    fun serializesPascalCaseFieldNamesAndOmitsNulls() = runTest {
        val h = SecretsHarness()
        harnessSecrets(h) { GET_OK to HttpStatusCode.OK }
            .getSecretValue(GetSecretValueRequest("db", versionStage = "AWSPREVIOUS"))

        val body = bodyJson(h.bodies.single())
        assertEquals("db", body["SecretId"]?.jsonPrimitive?.content)
        assertEquals("AWSPREVIOUS", body["VersionStage"]?.jsonPrimitive?.content)
        // `encodeDefaults = false`: an unset VersionId is absent, not an explicit null, which
        // Secrets Manager rejects.
        assertEquals(setOf("SecretId", "VersionStage"), body.keys)
    }

    @Test
    fun parsesTheSecretAndItsMetadata() = runTest {
        val response = harnessSecrets(SecretsHarness()) { GET_OK to HttpStatusCode.OK }
            .getSecretValue(GetSecretValueRequest("db"))

        assertEquals("""{"user":"root"}""", response.secretString)
        assertEquals("db", response.name)
        assertEquals("v1", response.versionId)
        assertEquals(listOf("AWSCURRENT"), response.versionStages)
        // CreatedDate is epoch *seconds* on the wire. Reading it as milliseconds dates every secret
        // to January 1970.
        assertEquals(1_700_000_000_000, response.createdDateEpochMillis)
    }

    @Test
    fun decodesABinarySecretFromBase64() = runTest {
        val response = harnessSecrets(SecretsHarness()) {
            """{"Name":"db","SecretBinary":"YmluYXJ5LXNlY3JldA=="}""" to HttpStatusCode.OK
        }.getSecretValue(GetSecretValueRequest("db"))

        assertNull(response.secretString)
        assertEquals("binary-secret", response.secretBinary?.decodeToString())
    }
}

/**
 * The redaction rule from `Model.kt`, asserted rather than trusted.
 *
 * A `data class` here would print the secret in full from any `logger.info("$response")` — which is
 * why none of the secret-bearing types is one.
 */
class SecretsRedactionTest {

    @Test
    fun getSecretValueResponseNeverPrintsTheSecret() {
        val printed = GetSecretValueResponse(
            secretString = "hunter2-the-real-password",
            name = "db",
            versionId = "v1",
        ).toString()

        assertFalse(printed.contains("hunter2"), "toString leaked the secret: $printed")
        assertContains(printed, "<redacted>")
        assertContains(printed, "db")
    }

    @Test
    fun secretValueEntryNeverPrintsTheSecret() {
        val printed = SecretValueEntry(secretString = "hunter2-the-real-password", name = "db").toString()
        assertFalse(printed.contains("hunter2"), "toString leaked the secret: $printed")
    }

    @Test
    fun putSecretValueRequestNeverPrintsTheSecret() {
        val printed = PutSecretValueRequest("db", secretString = "hunter2-the-real-password").toString()
        assertFalse(printed.contains("hunter2"), "toString leaked the secret: $printed")
    }

    @Test
    fun binarySecretsAreRedactedTooAndNotMerelyUnreadable() {
        val printed = GetSecretValueResponse(
            secretBinary = "hunter2-the-real-password".encodeToByteArray(),
            name = "db",
        ).toString()
        assertFalse(printed.contains("hunter2"), "toString leaked the secret: $printed")
        assertContains(printed, "<redacted>")
    }

    /**
     * The batch response *is* a data class, and is allowed to be precisely because its generated
     * `toString()` delegates to the redacting one. Asserted, because that is a property of a
     * generated method and nothing else would catch it regressing.
     */
    @Test
    fun theBatchResponseInheritsRedactionFromItsEntries() {
        val printed = BatchGetSecretValueResponse(
            secretValues = listOf(SecretValueEntry(secretString = "hunter2-the-real-password", name = "db")),
        ).toString()
        assertFalse(printed.contains("hunter2"), "the data class toString leaked a secret: $printed")
    }

    @Test
    fun secretCarryingTypesCompareByContent() {
        assertEquals(
            GetSecretValueResponse(secretBinary = "s".encodeToByteArray(), name = "db"),
            GetSecretValueResponse(secretBinary = "s".encodeToByteArray(), name = "db"),
        )
    }
}

class GetSecretStringTest {

    @Test
    fun returnsTheStringForm() = runTest {
        val value = harnessSecrets(SecretsHarness()) { GET_OK to HttpStatusCode.OK }.getSecretString("db")
        assertEquals("""{"user":"root"}""", value)
    }

    /**
     * The trap the convenience exists for: a text secret that happens to be *stored* as binary
     * arrives with a null `secretString`, which reads exactly like "no secret". A caller doing
     * `?: error("missing")` would report a secret that plainly exists as absent.
     */
    @Test
    fun fallsBackToDecodingTheBinaryForm() = runTest {
        val value = harnessSecrets(SecretsHarness()) {
            """{"Name":"db","SecretBinary":"YmluYXJ5LXNlY3JldA=="}""" to HttpStatusCode.OK
        }.getSecretString("db")

        assertEquals("binary-secret", value)
    }

    /**
     * A missing secret is an exception, not a null. Collapsing the two is how a typo in a secret
     * name becomes a silent default.
     */
    @Test
    fun aMissingSecretThrowsRatherThanReturningNull() = runTest {
        assertFailsWith<ResourceNotFoundException> {
            harnessSecrets(SecretsHarness()) {
                """{"__type":"ResourceNotFoundException","message":"Secrets Manager can't find it"}""" to
                    HttpStatusCode.BadRequest
            }.getSecretString("typo")
        }
    }
}

private const val PUT_OK =
    """{"ARN":"arn:secret","Name":"db","VersionId":"v2","VersionStages":["AWSCURRENT"]}"""

class PutSecretValueTest {

    @Test
    fun generatesAClientRequestTokenWhenTheCallerSuppliesNone() = runTest {
        val h = SecretsHarness()
        harnessSecrets(h) { PUT_OK to HttpStatusCode.OK }
            .putSecretValue(PutSecretValueRequest("db", secretString = "new"))

        val token = bodyJson(h.bodies.single())["ClientRequestToken"]?.jsonPrimitive?.content
        // UUID-shaped, per `aws-core`'s randomUuidString.
        assertEquals(36, token?.length, "expected a generated 36-character token, got $token")
    }

    /**
     * The property that makes `PutSecretValue` safe to mark `IDEMPOTENT`: the token is minted once
     * per **call**, not once per **attempt**. A token generated inside the retry loop would make
     * every retry a fresh write — burning a secret version per attempt, which is the exact thing
     * the idempotency token exists to prevent.
     */
    @Test
    fun reusesTheSameTokenAcrossRetriesOfOneCall() = runTest {
        val h = SecretsHarness()
        harnessSecrets(h) { call ->
            // A 500 is TRANSIENT in aws-core's status table, so the first attempt is retried.
            if (call == 0) """{"__type":"InternalServiceError"}""" to HttpStatusCode.InternalServerError
            else PUT_OK to HttpStatusCode.OK
        }.putSecretValue(PutSecretValueRequest("db", secretString = "new"))

        assertEquals(2, h.bodies.size, "expected the 500 to be retried")
        val tokens = h.bodies.map { bodyJson(it)["ClientRequestToken"]?.jsonPrimitive?.content }
        assertEquals(tokens[0], tokens[1], "the retry minted a second token and wrote a second version")
    }

    @Test
    fun honoursACallerSuppliedToken() = runTest {
        val h = SecretsHarness()
        harnessSecrets(h) { PUT_OK to HttpStatusCode.OK }.putSecretValue(
            PutSecretValueRequest("db", secretString = "new", clientRequestToken = "workflow-step-7"),
        )

        assertEquals(
            "workflow-step-7",
            bodyJson(h.bodies.single())["ClientRequestToken"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun sendsTheSecretAndStagesInPascalCase() = runTest {
        val h = SecretsHarness()
        harnessSecrets(h) { PUT_OK to HttpStatusCode.OK }.putSecretValue(
            PutSecretValueRequest("db", secretString = "new", versionStages = listOf("AWSPENDING")),
        )

        val body = bodyJson(h.bodies.single())
        assertEquals("db", body["SecretId"]?.jsonPrimitive?.content)
        assertEquals("new", body["SecretString"]?.jsonPrimitive?.content)
        assertEquals("AWSPENDING", body["VersionStages"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("secretsmanager.PutSecretValue", h.requests.last().headers["X-Amz-Target"])
    }

    @Test
    fun sendsABinarySecretAsBase64() = runTest {
        val h = SecretsHarness()
        harnessSecrets(h) { PUT_OK to HttpStatusCode.OK }.putSecretValue(
            PutSecretValueRequest("db", secretBinary = "binary-secret".encodeToByteArray()),
        )

        assertEquals(
            "YmluYXJ5LXNlY3JldA==",
            bodyJson(h.bodies.single())["SecretBinary"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun putSecretStringReturnsTheNewVersionId() = runTest {
        val versionId = harnessSecrets(SecretsHarness()) { PUT_OK to HttpStatusCode.OK }
            .putSecretString("db", "new")
        assertEquals("v2", versionId)
    }
}

class SecretsErrorMappingTest {

    @Test
    fun mapsKnownCodesOntoTypedExceptions() = runTest {
        suspend fun getFailingWith(code: String): Throwable = runCatching {
            harnessSecrets(SecretsHarness()) {
                """{"__type":"$code","message":"nope"}""" to HttpStatusCode.BadRequest
            }.getSecretValue(GetSecretValueRequest("db"))
        }.exceptionOrNull()!!

        assertTrue(getFailingWith("ResourceNotFoundException") is ResourceNotFoundException)
        assertTrue(getFailingWith("InvalidParameterException") is InvalidParameterException)
        assertTrue(getFailingWith("InvalidRequestException") is InvalidRequestException)
        assertTrue(getFailingWith("ResourceExistsException") is ResourceExistsException)
        // No `Exception` suffix on the wire — the class name and the code deliberately differ.
        assertTrue(getFailingWith("DecryptionFailure") is DecryptionFailureException)
        assertTrue(getFailingWith("EncryptionFailure") is EncryptionFailureException)
    }

    @Test
    fun unknownCodesFallThroughToTheBaseType() = runTest {
        val failure = assertFailsWith<SecretsManagerException> {
            harnessSecrets(SecretsHarness()) {
                """{"__type":"AccessDeniedException","message":"no"}""" to HttpStatusCode.BadRequest
            }.getSecretValue(GetSecretValueRequest("db"))
        }
        assertEquals("AccessDeniedException", failure.code)
    }
}

class GetSecretValuesTest {

    private fun entry(name: String, value: String) =
        """{"Name":"$name","ARN":"arn:$name","SecretString":"$value","VersionId":"v1"}"""

    @Test
    fun returnsEntriesInRequestOrderRegardlessOfResponseOrder() = runTest {
        val secrets = harnessSecrets(SecretsHarness()) {
            // Answered back to front, which Secrets Manager is entitled to do.
            """{"SecretValues":[${entry("c", "3")},${entry("a", "1")},${entry("b", "2")}]}""" to
                HttpStatusCode.OK
        }.getSecretValues(listOf("a", "b", "c"))

        assertEquals(listOf("a", "b", "c"), secrets.map { it.name })
        assertEquals(listOf("1", "2", "3"), secrets.map { it.secretString })
    }

    @Test
    fun matchesRequestedIdsGivenAsFullArns() = runTest {
        val secrets = harnessSecrets(SecretsHarness()) {
            """{"SecretValues":[${entry("a", "1")}]}""" to HttpStatusCode.OK
        }.getSecretValues(listOf("arn:a"))

        assertEquals(listOf("a"), secrets.map { it.name })
    }

    @Test
    fun chunksAtTwentyIds() = runTest {
        val h = SecretsHarness()
        val ids = (1..25).map { "s$it" }
        val bodiesByChunk = { call: Int ->
            val chunk = if (call == 0) (1..20) else (21..25)
            """{"SecretValues":[${chunk.joinToString(",") { entry("s$it", "$it") }}]}""" to HttpStatusCode.OK
        }

        val secrets = harnessSecrets(h, bodiesByChunk).getSecretValues(ids)

        assertEquals(2, h.bodies.size, "expected 25 ids to be split into two requests")
        assertEquals(20, bodyJson(h.bodies[0])["SecretIdList"]!!.jsonArray.size)
        assertEquals(5, bodyJson(h.bodies[1])["SecretIdList"]!!.jsonArray.size)
        assertEquals(ids, secrets.map { it.name })
    }

    @Test
    fun followsNextTokenUntilThePagesRunOut() = runTest {
        val h = SecretsHarness()
        val secrets = harnessSecrets(h) { call ->
            when (call) {
                0 -> """{"SecretValues":[${entry("a", "1")}],"NextToken":"page-2"}""" to HttpStatusCode.OK
                else -> """{"SecretValues":[${entry("b", "2")}]}""" to HttpStatusCode.OK
            }
        }.getSecretValues(listOf("a", "b"))

        assertEquals(2, h.bodies.size)
        assertNull(bodyJson(h.bodies[0])["NextToken"])
        assertEquals("page-2", bodyJson(h.bodies[1])["NextToken"]?.jsonPrimitive?.content)
        assertEquals(listOf("a", "b"), secrets.map { it.name })
    }

    /**
     * The headline reason this helper exists: a per-secret failure arrives inside an **HTTP 200**,
     * so a caller reading `response.secretValues` gets a short list and no signal that it is short.
     */
    @Test
    fun raisesRatherThanReturningAShortListWhenASecretFailed() = runTest {
        val failure = assertFailsWith<BatchGetSecretValuePartialFailureException> {
            harnessSecrets(SecretsHarness()) {
                """{"SecretValues":[${entry("a", "1")}],""" +
                    """"Errors":[{"SecretId":"b","ErrorCode":"ResourceNotFoundException","Message":"gone"}]}""" to
                    HttpStatusCode.OK
            }.getSecretValues(listOf("a", "b"))
        }

        assertEquals(200, failure.statusCode)
        assertEquals(listOf("a"), failure.resolved.map { it.name })
        assertEquals(listOf("b"), failure.errors.map { it.secretId })
        assertContains(failure.message!!, "ResourceNotFoundException")
    }

    /**
     * A partial ARN matches neither `Name` nor `ARN` in the response, so it cannot be paired back
     * up. Reported rather than silently dropped — a dropped id is a caller starting up without a
     * credential it asked for.
     */
    @Test
    fun reportsIdsThatWereNeitherReturnedNorRejected() = runTest {
        val failure = assertFailsWith<BatchGetSecretValuePartialFailureException> {
            harnessSecrets(SecretsHarness()) {
                """{"SecretValues":[${entry("a", "1")}]}""" to HttpStatusCode.OK
            }.getSecretValues(listOf("a", "arn:aws:secretsmanager:us-west-2:1:secret:b"))
        }

        assertEquals(listOf("arn:aws:secretsmanager:us-west-2:1:secret:b"), failure.missingSecretIds)
        assertContains(failure.message!!, "partial ARNs")
    }

    @Test
    fun readsDuplicateIdsOnceAndReturnsThemOnce() = runTest {
        val h = SecretsHarness()
        val secrets = harnessSecrets(h) {
            """{"SecretValues":[${entry("a", "1")}]}""" to HttpStatusCode.OK
        }.getSecretValues(listOf("a", "a"))

        assertEquals(1, bodyJson(h.bodies.single())["SecretIdList"]!!.jsonArray.size)
        assertEquals(listOf("a"), secrets.map { it.name })
    }

    @Test
    fun sendsNoRequestForAnEmptyIdList() = runTest {
        val h = SecretsHarness()
        val secrets = harnessSecrets(h) { "{}" to HttpStatusCode.OK }.getSecretValues(emptyList())

        assertTrue(secrets.isEmpty())
        assertTrue(h.requests.isEmpty(), "an empty batch should not reach the network")
    }

    /**
     * A `NextToken` that never clears is a service-side bug shaped like an infinite loop inside
     * somebody's cold start. The page bound stops it; the accounting then reports the short chunk
     * rather than pretending it succeeded.
     */
    @Test
    fun boundsPaginationRatherThanLoopingForever() = runTest {
        val h = SecretsHarness()
        assertFailsWith<BatchGetSecretValuePartialFailureException> {
            harnessSecrets(h) {
                """{"SecretValues":[${entry("a", "1")}],"NextToken":"never-ends"}""" to HttpStatusCode.OK
            }.getSecretValues(listOf("a", "b"), maxPagesPerChunk = 3)
        }

        assertEquals(3, h.bodies.size, "pagination was not bounded")
    }
}
