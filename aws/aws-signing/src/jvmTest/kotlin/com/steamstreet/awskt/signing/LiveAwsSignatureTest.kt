package com.steamstreet.awskt.signing

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The live-AWS oracle: does AWS's real verifier accept a signature this library produced?
 *
 * Every other test in this module compares against AWS's *published expectations*. That is strong,
 * but it is still our reading of their fixtures. This is the only test where AWS itself is the
 * judge, and it is the one the plan identifies as missing from every LocalStack-based suite —
 * LocalStack cannot verify a signature for a secret it does not hold, and ships with IAM
 * enforcement off by default, so "integration tests green" is fully compatible with a signer that
 * fails every production request.
 *
 * **Read-only and permission-free by design.** AWS validates the signature *before* it evaluates
 * IAM policy, so `AccessDeniedException` is a passing result: it proves the signature was accepted
 * and only the authorization failed. A credential with no permissions at all is sufficient.
 *
 * Skips itself when no credentials are present, so CI and offline builds stay green. To run it:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && ./gradlew :aws:aws-signing:jvmTest
 * ```
 */
class LiveAwsSignatureTest {

    private class Response(val status: Int, val body: String) {
        /** True when AWS rejected the *signature*, as opposed to the caller's permissions. */
        val signatureRejected: Boolean
            get() = SIGNATURE_ERRORS.any { body.contains(it, ignoreCase = true) }

        /**
         * True when AWS did not recognise the credential at all. Distinguished from
         * [signatureRejected] deliberately: an expired session looks superficially like a signing
         * failure and would otherwise send someone hunting a bug in correct code.
         */
        val credentialRejected: Boolean
            get() = CREDENTIAL_ERRORS.any { body.contains(it, ignoreCase = true) }

        override fun toString() = "HTTP $status: ${body.take(300)}"
    }

    @Test
    fun awsAcceptsASignatureWeProduced() {
        val credentials = environmentCredentials() ?: return skip()

        val signed = signListTables(credentials)
        val response = send(signed, credentials, corruptSignature = false)

        if (response.credentialRejected) {
            fail(
                "AWS did not recognise the credential, so this run proves nothing either way. " +
                    "The session has most likely expired — refresh it and re-run.\n$response",
            )
        }
        assertTrue(
            !response.signatureRejected,
            buildString {
                appendLine("AWS rejected our signature. This is a signer bug, not a permissions problem.")
                appendLine(response.toString())
                appendLine()
                appendLine("--- canonical request we signed (credentials redacted) ---")
                appendLine(redact(signed.canonicalRequest, credentials))
                appendLine("--- string to sign ---")
                appendLine(redact(signed.stringToSign, credentials))
            },
        )
        println("[live] AWS accepted the signature — $response")
    }

    /** Never let a session token or secret reach an assertion message or a CI log. */
    private fun redact(text: String, credentials: AwsCredentials): String {
        var out = text
        credentials.sessionToken?.let { out = out.replace(it, "<session-token>") }
        return out.replace(credentials.secretAccessKey, "<secret>")
    }

    /**
     * Negative control.
     *
     * Without this, [awsAcceptsASignatureWeProduced] would also pass if the request never reached
     * AWS, if the endpoint 404'd, or if the response body were empty — a test that cannot fail is
     * not evidence. Corrupting one hex digit of the signature must produce a signature error.
     */
    @Test
    fun awsRejectsATamperedSignature() {
        val credentials = environmentCredentials() ?: return skip()

        val response = send(signListTables(credentials), credentials, corruptSignature = true)

        assertTrue(
            response.signatureRejected,
            "AWS accepted a deliberately corrupted signature, so the positive test proves nothing " +
                "about signing. Check that the request is really reaching AWS.\n$response",
        )
        println("[live] AWS rejected the tampered signature, as it must — HTTP ${response.status}")
    }

    // -----------------------------------------------------------------------

    private fun signListTables(credentials: AwsCredentials): SignedRequest {
        val region = region()
        return SigV4.sign(
            request = SigningRequest(
                method = "POST",
                path = "/",
                host = "dynamodb.$region.amazonaws.com",
                headers = listOf(
                    "Content-Type" to "application/x-amz-json-1.0",
                    "X-Amz-Target" to "DynamoDB_20120810.ListTables",
                ),
                body = BODY,
            ),
            credentials = credentials,
            config = SigV4Config(region = region, service = "dynamodb"),
            signingInstantMillis = System.currentTimeMillis(),
        )
    }

    private fun send(
        signed: SignedRequest,
        credentials: AwsCredentials,
        corruptSignature: Boolean,
    ): Response {
        val host = "dynamodb.${region()}.amazonaws.com"
        val body = BODY

        // Diagnostic escape hatch: writes a curl config so the *identical* signed request can be
        // replayed by a different HTTP client. That is what distinguishes a signer bug from a
        // client that rewrites a signed header behind our back.
        System.getenv("AWSKT_DUMP_CURL")?.let { path ->
            val file = java.io.File(path)
            file.writeText(
                buildString {
                    appendLine("url = \"https://$host/\"")
                    appendLine("request = \"POST\"")
                    for ((name, value) in signed.headers) {
                        if (name.equals("Host", ignoreCase = true)) continue
                        appendLine("header = \"$name: $value\"")
                    }
                    appendLine("data = \"${body.decodeToString()}\"")
                },
            )
            file.setReadable(false, false)
            file.setReadable(true, true)
        }

        // Deliberately java.net.http.HttpClient and NOT the legacy HttpURLConnection. The legacy
        // client rewrites headers it considers its own, and a signed request that it has quietly
        // edited fails as InvalidSignatureException — which reads exactly like a signer bug and
        // sent this very test down a blind alley once already. Verified by replaying the identical
        // signed request through curl: HTTP 200 where HttpURLConnection got 400.
        val builder = HttpRequest.newBuilder(URI("https://$host/"))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))

        for ((name, value) in signed.headers) {
            // The client owns Host, and it sets Content-Length and User-Agent itself — which is
            // precisely why this library refuses to sign any of the three. If that exclusion set
            // were wrong, this request would fail, so this test quietly validates it too.
            if (name.equals("Host", ignoreCase = true)) continue
            builder.header(
                name,
                if (corruptSignature && name == "Authorization") tamper(value) else value,
            )
        }

        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build()
            .send(builder.build(), HttpResponse.BodyHandlers.ofString())

        return Response(response.statusCode(), response.body())
    }

    /** Flips the final hex digit of the signature, leaving the request otherwise well-formed. */
    private fun tamper(authorization: String): String {
        val last = authorization.last()
        return authorization.dropLast(1) + if (last == '0') '1' else '0'
    }

    private fun environmentCredentials(): AwsCredentials? {
        val accessKeyId = System.getenv("AWS_ACCESS_KEY_ID") ?: return null
        val secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY") ?: return null
        return AwsCredentials(accessKeyId, secretAccessKey, System.getenv("AWS_SESSION_TOKEN"))
    }

    private fun region(): String =
        System.getenv("AWS_REGION") ?: System.getenv("AWS_DEFAULT_REGION") ?: "us-east-1"

    private fun skip() {
        println(
            "[live] skipped — no AWS credentials in the environment. " +
                "Run with: eval \"\$(aws configure export-credentials --profile <p> --format env)\"",
        )
    }

    private companion object {
        /**
         * Signature-layer rejections. Anything else — including `AccessDeniedException` — means the
         * signature itself was accepted, which is all this test set out to prove.
         */
        val SIGNATURE_ERRORS = listOf(
            "InvalidSignatureException",
            "SignatureDoesNotMatch",
            "IncompleteSignature",
            "MissingAuthenticationToken",
        )

        val BODY = "{}".encodeToByteArray()

        /** The credential was not recognised — an environment problem, not a signing one. */
        val CREDENTIAL_ERRORS = listOf(
            "InvalidClientTokenId",
            "UnrecognizedClientException",
            "ExpiredToken",
            "ExpiredTokenException",
        )
    }
}
