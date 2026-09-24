package com.steamstreet.env

import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.secretsmanager.SecretsManager
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.posix.unsetenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records the ids it was asked for and answers from a map; a value of [Throw] throws instead. */
private class FakeSecrets(private val values: Map<String, Any?>) : SecretsProvider {
    val requested = mutableListOf<String>()

    override suspend fun getSecretValue(secretId: String): String? {
        requested += secretId
        val value = values[secretId]
        if (value is Throwable) throw value
        return value as String?
    }
}

@OptIn(ExperimentalForeignApi::class)
class NativeSecretsTest {
    private val originalProvider = secrets
    private val keys = mutableListOf<String>()

    private fun set(key: String, value: String) {
        keys += key
        registerEnvironmentVariable(key, value)
    }

    @AfterTest
    fun restore() {
        secrets = originalProvider
        keys.forEach { unsetenv(it) }
    }

    @Test
    fun aPlainValuePassesThroughWithoutTouchingSecrets() {
        val fake = FakeSecrets(emptyMap()).also { secrets = it }
        set("AWSKT_PLAIN", "hello")

        assertEquals("hello", getEnvironmentVariable("AWSKT_PLAIN"))
        assertTrue(fake.requested.isEmpty())
    }

    @Test
    fun aSecretValueIsReplacedByTheSecret() {
        secrets = FakeSecrets(mapOf("prod/token" to "s3cret"))
        set("AWSKT_TOKEN", "Secret_prod/token")

        assertEquals("s3cret", getEnvironmentVariable("AWSKT_TOKEN"))
    }

    @Test
    fun aJsonKeyIsReadFromTheSecretAndTheIdMayBeAnArn() {
        val arn = "arn:aws:secretsmanager:us-west-2:123456789012:secret:app-AbCdEf"
        val fake = FakeSecrets(mapOf(arn to """{"apiKey":"k-123","other":"x"}""")).also { secrets = it }
        set("AWSKT_API_KEY", "Secret_$arn.apiKey")

        assertEquals("k-123", getEnvironmentVariable("AWSKT_API_KEY"))
        assertEquals(listOf(arn), fake.requested)
    }

    /** The data-loaders stack's form: `Secret_SlackToken = vegasful/prod/slack.token`. */
    @Test
    fun aSeparateSecretVariableTakesPrecedence() {
        secrets = FakeSecrets(mapOf("vegasful/prod/slack" to """{"token":"xoxb"}"""))
        set("AWSKT_SLACK", "ignored")
        set("Secret_AWSKT_SLACK", "vegasful/prod/slack.token")

        assertEquals("xoxb", getEnvironmentVariable("AWSKT_SLACK"))
    }

    @Test
    fun aSeparateSecretVariableWorksWithoutTheVariableItself() {
        secrets = FakeSecrets(mapOf("only/secret" to "v"))
        set("Secret_AWSKT_ONLY", "only/secret")

        assertEquals("v", getEnvironmentVariable("AWSKT_ONLY"))
    }

    @Test
    fun noValueReadsAsNull() {
        set("AWSKT_NONE", "_NoValue")

        assertNull(getEnvironmentVariable("AWSKT_NONE"))
    }

    @Test
    fun aMissingOrFailingSecretReadsAsNull() {
        secrets = FakeSecrets(mapOf("absent" to null, "broken" to IllegalStateException("boom")))
        set("AWSKT_ABSENT", "Secret_absent")
        set("AWSKT_BROKEN", "Secret_broken")

        assertNull(getEnvironmentVariable("AWSKT_ABSENT"))
        assertNull(getEnvironmentVariable("AWSKT_BROKEN"))
    }

    @Test
    fun aMissingJsonKeyReadsAsNull() {
        secrets = FakeSecrets(mapOf("s" to """{"a":"1"}"""))
        set("AWSKT_MISSING_KEY", "Secret_s.b")

        assertNull(getEnvironmentVariable("AWSKT_MISSING_KEY"))
    }

    @Test
    fun theIntReaderResolvesSecretsToo() {
        secrets = FakeSecrets(mapOf("port" to "8080"))
        set("AWSKT_PORT", "Secret_port")

        assertEquals(8080, Env.int("AWSKT_PORT"))
    }

    /**
     * A native handler is itself a coroutine, so the read happens inside one: `runBlocking` nested
     * in `runBlocking`, as on the JVM, and on a worker thread when the handler has switched to IO.
     */
    @Test
    fun resolvesFromInsideACoroutine() {
        secrets = FakeSecrets(mapOf("nested" to "ok"))
        set("AWSKT_NESTED", "Secret_nested")

        val onCaller = runBlocking { getEnvironmentVariable("AWSKT_NESTED") }
        val onIo = runBlocking { withContext(Dispatchers.IO) { getEnvironmentVariable("AWSKT_NESTED") } }

        assertEquals("ok", onCaller)
        assertEquals("ok", onIo)
    }

    /** The real provider, over the real awskt client and a mocked Secrets Manager. */
    @Test
    fun theDefaultProviderCallsGetSecretValue() {
        val requests = mutableListOf<Pair<HttpRequestData, String>>()
        val engine = MockEngine { request ->
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            requests += request to body
            if ("missing" in body) {
                respond(
                    """{"__type":"ResourceNotFoundException","message":"not found"}""",
                    HttpStatusCode.BadRequest,
                    headersOf("Content-Type", "application/x-amz-json-1.1"),
                )
            } else {
                respond(
                    """{"Name":"prod/db","SecretString":"{\"password\":\"pw\"}"}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/x-amz-json-1.1"),
                )
            }
        }
        secrets = SecretsManagerSecretsProvider {
            SecretsManager {
                region = "us-west-2"
                credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET"))
                httpClient = HttpClient(engine) { expectSuccess = false }
            }
        }
        set("AWSKT_DB_PASSWORD", "Secret_prod/db.password")
        set("AWSKT_MISSING", "Secret_missing")

        assertEquals("pw", getEnvironmentVariable("AWSKT_DB_PASSWORD"))
        assertNull(getEnvironmentVariable("AWSKT_MISSING"))

        val (request, body) = requests.first()
        assertEquals("secretsmanager.GetSecretValue", request.headers["X-Amz-Target"])
        assertTrue(""""SecretId":"prod/db"""" in body, body)
    }
}
