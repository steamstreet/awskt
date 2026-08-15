package com.steamstreet.awskt.secretsmanager

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end proof against real Secrets Manager.
 *
 * See `aws-kms`'s `LiveKmsTest` for why these exist alongside the MockEngine suite. Self-skips
 * without credentials:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 SMOKE_SECRET_ID=awskt-smoke ./gradlew :aws:aws-secretsmanager:jvmTest
 * ```
 *
 * **These only read.** `PutSecretValue` is deliberately not exercised live: it creates a new secret
 * version every call, versions are retained, and a test that quietly accumulates them against an
 * account quota is a test that eventually breaks something else. The idempotency-token behaviour it
 * would prove is asserted hermetically instead, where the assertion is about *our* request rather
 * than about the service's storage.
 */
class LiveSecretsManagerTest {

    private fun secretId(): String? {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) return null
        return awsEnv("SMOKE_SECRET_ID")?.takeIf { it.isNotBlank() }
    }

    private fun secrets() = SecretsManager { caInfo = awsEnv("SMOKE_CA_BUNDLE") }

    /**
     * Reads a secret, and asserts the thing the mock suite cannot: that the value came back at all.
     *
     * A `GetSecretValue` transitively exercises the `secretsmanager` target prefix, AWS-JSON 1.1,
     * SigV4, **and KMS**, since Secrets Manager decrypts with a key on the caller's behalf. A
     * `DecryptionFailureException` here is a KMS permission problem wearing a Secrets Manager code.
     */
    @Test
    fun readsASecretThroughRealSecretsManager() = runTest {
        val id = secretId() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_SECRET_ID")
            return@runTest
        }

        secrets().use { sm ->
            val response = sm.getSecretValue(GetSecretValueRequest(id))
            val value = response.secretString ?: response.secretBinary?.decodeToString()

            assertTrue(!value.isNullOrEmpty(), "the secret came back empty")
            assertTrue(response.versionStages.orEmpty().contains("AWSCURRENT"))
            // The redaction rule, against a real response rather than a constructed one.
            assertFalse(response.toString().contains(value!!), "toString leaked a real secret")
            println("[live] Secrets Manager read '${response.name}' (${value.length} chars, redacted)")
        }
    }

    /** `getSecretString` must find the value whichever way the secret happens to be stored. */
    @Test
    fun getSecretStringResolvesEitherStorageForm() = runTest {
        val id = secretId() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_SECRET_ID")
            return@runTest
        }

        secrets().use { sm ->
            assertTrue(!sm.getSecretString(id).isNullOrEmpty())
        }
    }

    /**
     * The batch path, and specifically the accounting that makes a short result impossible.
     *
     * Asking for the real secret plus one that certainly does not exist proves the per-entry error
     * arrives inside an HTTP 200 and that `getSecretValues` refuses to report success on it — the
     * behaviour the whole helper exists for, asserted against AWS's own 200 rather than a mock's.
     */
    @Test
    fun batchReadRaisesRatherThanReturningAShortList() = runTest {
        val id = secretId() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_SECRET_ID")
            return@runTest
        }

        secrets().use { sm ->
            assertEquals(1, sm.getSecretValues(listOf(id)).size)

            var raised = false
            try {
                sm.getSecretValues(listOf(id, "awskt-definitely-no-such-secret-93f2a1"))
            } catch (partial: BatchGetSecretValuePartialFailureException) {
                raised = true
                assertEquals(200, partial.statusCode, "the failure arrived inside a successful HTTP call")
                assertEquals(1, partial.resolved.size, "the secret that did exist should still be carried")
                assertTrue(
                    partial.errors.isNotEmpty() || partial.missingSecretIds.isNotEmpty(),
                    "the missing secret was accounted for neither as an error nor as missing",
                )
            }
            assertTrue(raised, "a batch with a missing secret must not report success")
            println("[live] Secrets Manager batch correctly refused a partial result")
        }
    }
}
