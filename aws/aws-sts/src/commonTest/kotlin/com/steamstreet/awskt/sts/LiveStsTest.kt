package com.steamstreet.awskt.sts

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof against real STS.
 *
 * Like `aws-sns`'s live suite, this is the first independent check of a hand-written form encoder
 * and XML reader: everything hermetic here was written alongside the code it tests.
 *
 * `GetCallerIdentity` needs nothing but credentials. The assume-role test also needs a role the
 * caller may assume:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 \
 *      SMOKE_STS_ROLE_ARN=arn:aws:iam::123456789012:role/awskt-smoke \
 *      ./gradlew :aws:aws-sts:jvmTest
 * ```
 */
class LiveStsTest {

    private fun hasCredentials() = !awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()

    private fun roleArn(): String? =
        if (hasCredentials()) awsEnv("SMOKE_STS_ROLE_ARN")?.takeIf { it.isNotBlank() } else null

    private val caInfo get() = awsEnv("SMOKE_CA_BUNDLE")

    @Test
    fun getsTheCallerIdentityThroughRealSts() = runTest {
        if (!hasCredentials()) {
            println("[live] skipped — no AWS credentials in the environment")
            return@runTest
        }

        Sts { caInfo = this@LiveStsTest.caInfo }.use { sts ->
            val identity = sts.getCallerIdentity()
            assertEquals(12, identity.account.length, identity.toString())
            assertTrue(identity.arn.startsWith("arn:aws"), identity.arn)
        }
    }

    /**
     * Assumes the role through the provider and proves the credentials it returns really are that
     * role's, by asking STS who they belong to. The session policy is there for the encoder: JSON
     * with spaces and quotes is exactly what a `+`-for-space encoder gets wrong.
     */
    @Test
    fun assumesARoleThroughTheProvider() = runTest {
        val role = roleArn() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_STS_ROLE_ARN")
            return@runTest
        }

        val request = AssumeRoleRequest(
            roleArn = role,
            roleSessionName = "awskt-live-smoke",
            policy = """{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": "sts:GetCallerIdentity", "Resource": "*"}]}""",
        )
        AssumeRoleCredentialsProvider(request) { caInfo = this@LiveStsTest.caInfo }.use { provider ->
            val credentials = provider.resolve()
            assertTrue(credentials.sessionToken != null)
            assertTrue(credentials.expiresAtEpochMillis != null, "Expiration was not read")

            Sts { caInfo = this@LiveStsTest.caInfo; credentialsProvider = provider }.use { assumed ->
                val identity = assumed.getCallerIdentity()
                assertContains(identity.arn, ":assumed-role/")
                assertTrue(identity.arn.endsWith("/awskt-live-smoke"), identity.arn)
            }
        }
    }
}
