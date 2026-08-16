package com.steamstreet.awskt.ses

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end proof against real Amazon SES v2.
 *
 * See `aws-kms`'s `LiveKmsTest` for why these exist. Self-skips without credentials:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 \
 *      SMOKE_SES_FROM=no-reply@example.com \
 *      SMOKE_SES_TO=you@example.com \
 *      ./gradlew :aws:aws-ses:jvmTest
 * ```
 *
 * ### This suite is the one that actually sends something
 *
 * Every other live suite in this repository creates a resource and deletes it, or reads without
 * writing. **An email cannot be un-sent.** Two consequences, both deliberate:
 *
 * - It needs `SMOKE_SES_TO` as well as credentials, so it cannot mail anyone by accident. Point it
 *   at an address you own.
 * - There is exactly one sending test, and it sends one message.
 *
 * ### What it proves that the hermetic tests cannot
 *
 * The signature. SES's endpoint prefix is `email` and its signing name is `ses`, and getting that
 * pair wrong is a `SignatureDoesNotMatch` that `MockEngine` cannot detect because `MockEngine`
 * accepts whatever it is handed. A 200 from SES is the first evidence [SES_PROTOCOL] is right.
 *
 * ### If it fails with [MessageRejected]
 *
 * Check whether the account is still in the **SES sandbox**, where the recipient must be a verified
 * identity too, not only the sender. That is the likely cause and it is an account state rather than
 * a defect in this client.
 */
class LiveSesTest {

    private fun addresses(): Pair<String, String>? {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) return null
        val from = awsEnv("SMOKE_SES_FROM")?.takeIf { it.isNotBlank() } ?: return null
        val to = awsEnv("SMOKE_SES_TO")?.takeIf { it.isNotBlank() } ?: return null
        return from to to
    }

    private fun ses() = Ses { caInfo = awsEnv("SMOKE_CA_BUNDLE") }

    /** Sends one real email and asserts SES accepted it. */
    @Test
    fun sendsASimpleEmail() = runTest {
        val (from, to) = addresses() ?: run {
            println("[live] skipped — set AWS credentials, SMOKE_SES_FROM and SMOKE_SES_TO")
            return@runTest
        }

        ses().use { ses ->
            val response = ses.sendSimpleEmail(
                // The display-name form, which is what the consuming application uses and which SES
                // accepts even though the *verified identity* is the bare address inside it.
                from = "awskt smoke <$from>",
                to = listOf(to),
                subject = "awskt live smoke — em dash — and a curly quote’s worth of UTF-8",
                text = "Sent by aws-ses's LiveSesTest. Nothing to do; you can delete this.",
                html = "<p>Sent by <code>aws-ses</code>'s LiveSesTest. Nothing to do.</p>",
            )

            // A MessageId means accepted, not delivered — see SendEmailResponse. Delivery is only
            // observable through a configuration set's events, which this suite does not require.
            assertTrue(!response.messageId.isNullOrBlank(), "no MessageId came back")
            println("[live] SES accepted message ${response.messageId}")
        }
    }

    /**
     * A send that SES refuses, proving the error path end to end.
     *
     * The unverified `From` is the reliable way to get a real modelled failure without sending
     * anything: an account can only send as an identity it has verified, and `example.com` is
     * reserved by RFC 2606 so it cannot be verified by accident in anyone's account. The assertion
     * is deliberately on [SesException] rather than on a specific subtype — which one comes back
     * depends on account state, and pinning it here would make the test fail for the wrong reason.
     */
    @Test
    fun reportsARefusedSendAsATypedFailure() = runTest {
        addresses() ?: run {
            println("[live] skipped — set AWS credentials, SMOKE_SES_FROM and SMOKE_SES_TO")
            return@runTest
        }

        ses().use { ses ->
            val failure = runCatching {
                ses.sendSimpleEmail(
                    from = "awskt-not-a-verified-identity@example.com",
                    to = listOf("awskt-nobody@example.com"),
                    subject = "awskt live smoke — this send is expected to fail",
                    text = "If this arrived, the test's premise is wrong.",
                )
            }.exceptionOrNull()

            assertTrue(
                failure is SesException,
                "expected SES to refuse an unverified From, got: $failure",
            )
            println("[live] SES refused the unverified sender with ${failure.code}: ${failure.message}")
        }
    }
}
