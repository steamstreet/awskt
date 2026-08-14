package com.steamstreet.awskt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryClassificationTest {

    @Test
    fun throttlingIsRetriedAndValidationIsNot() {
        assertEquals(RetryErrorType.THROTTLING, classifyRetry("ProvisionedThroughputExceededException", 400))
        assertNull(classifyRetry("ValidationException", 400))
    }

    /**
     * The deny-list runs before the code table, so a later addition to the retryable codes cannot
     * make an atomic write replayable.
     */
    @Test
    fun neverRetryCodesWinEvenWhenTheStatusLooksRetryable() {
        assertNull(classifyRetry("TransactionCanceledException", 500))
        assertNull(classifyRetry("ConditionalCheckFailedException", 503))
        assertNull(classifyRetry("IdempotentParameterMismatchException", 500))
        // ...while an unrelated code at the same status still retries on the status.
        assertEquals(RetryErrorType.TRANSIENT, classifyRetry("SomeUnknownError", 500))
    }

    /**
     * Code beats status, and it changes the pacing: S3 sheds load with `503 SlowDown`, which must
     * back off on the throttling base (1s), not the transient one (25ms).
     */
    @Test
    fun codeBeatsStatus() {
        assertEquals(RetryErrorType.THROTTLING, classifyRetry("SlowDown", 503))
        assertEquals(RetryErrorType.TRANSIENT, classifyRetry(null, 503))

        val config = RetryConfig()
        val throttling = backoffMillis(RetryErrorType.THROTTLING, 0, config) { 1.0 }
        val transient = backoffMillis(RetryErrorType.TRANSIENT, 0, config) { 1.0 }
        assertEquals(1_000, throttling)
        assertEquals(25, transient)
    }

    @Test
    fun conditionalRequestConflictIsRetryable() {
        // Absent from the AWS SDK's table entirely; added because v1 ships PutObject ifNoneMatch.
        assertEquals(RetryErrorType.TRANSIENT, classifyRetry("ConditionalRequestConflict", 409))
        // A 409 with no recognised code is still not retried on status alone.
        assertNull(classifyRetry(null, 409))
    }

    @Test
    fun unknownCodesAndStatusesAreNotRetried() {
        assertNull(classifyRetry("NoSuchKey", 404))
        assertNull(classifyRetry(null, 400))
        assertNull(classifyRetry("AccessDeniedException", 403))
    }

    @Test
    fun backoffIsExponentialAndCapped() {
        val config = RetryConfig()
        assertEquals(1_000, backoffMillis(RetryErrorType.THROTTLING, 0, config) { 1.0 })
        assertEquals(2_000, backoffMillis(RetryErrorType.THROTTLING, 1, config) { 1.0 })
        assertEquals(4_000, backoffMillis(RetryErrorType.THROTTLING, 2, config) { 1.0 })
        // Capped, and never negative even at absurd attempt counts.
        assertEquals(20_000, backoffMillis(RetryErrorType.THROTTLING, 20, config) { 1.0 })
        assertTrue(backoffMillis(RetryErrorType.THROTTLING, 60, config) { 1.0 } in 0..20_000)
    }

    @Test
    fun jitterScalesTheWholeDelay() {
        val config = RetryConfig()
        assertEquals(0, backoffMillis(RetryErrorType.THROTTLING, 3, config) { 0.0 })
        assertEquals(4_000, backoffMillis(RetryErrorType.THROTTLING, 3, config) { 0.5 })
    }

    /** The header is milliseconds. Reading it as seconds turns a 3s pause into ~50 minutes. */
    @Test
    fun retryAfterIsMillisecondsAndClamped() {
        assertEquals(3_000, applyRetryAfter(100, "3000"))
        assertEquals(100, applyRetryAfter(100, null))
        assertEquals(100, applyRetryAfter(100, "not-a-number"))
        // Never shorter than our own computation, never more than 5s longer.
        assertEquals(500, applyRetryAfter(500, "10"))
        assertEquals(5_500, applyRetryAfter(500, "99999"))
    }

    @Test
    fun tokenBucketBlocksAfterDepletion() {
        val bucket = RetryTokenBucket(capacity = 30)
        assertTrue(bucket.tryAcquire(RetryErrorType.TRANSIENT))  // 30 -> 16
        assertTrue(bucket.tryAcquire(RetryErrorType.TRANSIENT))  // 16 -> 2
        assertFalse(bucket.tryAcquire(RetryErrorType.TRANSIENT)) // 2 < 14
        assertEquals(2, bucket.available)
    }

    @Test
    fun tokenBucketRefillsOnSuccessAndRefund() {
        val bucket = RetryTokenBucket(capacity = 20)
        bucket.tryAcquire(RetryErrorType.THROTTLING) // 20 -> 15
        bucket.refund(RetryErrorType.THROTTLING)     // 15 -> 20
        assertEquals(20, bucket.available)
        bucket.onCleanSuccess()
        assertEquals(20, bucket.available, "must never exceed capacity")
    }

    /**
     * One call can spend on more than one kind of failure — a throttle, then a 503 — so what a
     * successful call hands back is a summed amount rather than a type. The two must agree on the
     * price of a retry, which is what [RetryTokenBucket.costOf] is for.
     */
    @Test
    fun refundCostReturnsExactlyTheSummedCostOfAMixedCall() {
        val bucket = RetryTokenBucket(capacity = 100)
        assertEquals(5, bucket.costOf(RetryErrorType.THROTTLING))
        assertEquals(14, bucket.costOf(RetryErrorType.TRANSIENT))

        assertTrue(bucket.tryAcquire(RetryErrorType.THROTTLING)) // 100 -> 95
        assertTrue(bucket.tryAcquire(RetryErrorType.TRANSIENT))  // 95 -> 81
        assertEquals(81, bucket.available)

        bucket.refundCost(5 + 14)
        assertEquals(100, bucket.available, "a call that succeeds returns everything it acquired")
        // A no-op rather than a spin or a stray credit, which is what a call that never retried does.
        bucket.refundCost(0)
        assertEquals(100, bucket.available)
    }
}

class TransportFailureClassificationTest {

    private class Named(name: String, override val message: String?) : Exception(message) {
        private val name = name
        override fun toString() = name
    }

    @Test
    fun ambiguousIsTheDefault() {
        // An unrecognised failure might have reached AWS, so it must not be treated as safe.
        assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(Exception("something odd")))
        assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(Exception("read timed out")))
        assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(Exception("connection reset")))
    }

    @Test
    fun provablyNotSentFailuresAreRecognised() {
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(Exception("Connection refused")))
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(Exception("Failed to connect to host")))
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(Exception("unresolved address")))
    }

    @Test
    fun causesAreInspected() {
        val wrapped = Exception("wrapper", Exception("Connection refused"))
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(wrapped))
    }

    @Test
    fun cyclicCausesTerminate() {
        val a = Exception("a")
        val b = Exception("b", a)
        // Self-referential chains must not spin.
        assertEquals(TransportFailure.AMBIGUOUS, classifyTransportFailure(b))
    }

    /**
     * Verbatim shape of what `ktor-client-curl` 3.5.2 throws: a bare `IllegalStateException`
     * carrying `"Connection failed for request: $request. Reason: ${strerror} ($CURLE_NAME)"`
     * (`CurlMultiApiHandler.kt:353`, `CurlAdapters.kt:61`). Built here as a `RuntimeException`
     * because the classifier is looking at the message, not the class.
     */
    private fun curlFailure(reason: String): Throwable = RuntimeException(
        "Connection failed for request: CurlRequestData(url='https://dynamodb.us-west-2." +
            "amazonaws.com/', method='POST', content: 61 bytes). Reason: $reason",
    )

    /**
     * Curl is the only engine Kotlin/Native has, and none of its wording matched anything before
     * these markers existed — so a DNS failure that provably never reached AWS was classified
     * AMBIGUOUS, and a `NOT_IDEMPOTENT` write refused to retry it.
     */
    @Test
    fun curlPreSendFailuresAreNotSent() {
        // DNS. Both prose spellings, because curl 7.x and 8.x disagree, plus the enum name that
        // ktor appends and which does not drift between curl releases.
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(curlFailure("Couldn't resolve host name (CURLE_COULDNT_RESOLVE_HOST)")),
        )
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(curlFailure("Could not resolve hostname (CURLE_COULDNT_RESOLVE_HOST)")),
        )
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(curlFailure("Could not resolve proxy name (CURLE_COULDNT_RESOLVE_PROXY)")),
        )
        // Connect.
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(curlFailure("Could not connect to server (CURLE_COULDNT_CONNECT)")),
        )
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(curlFailure("QUIC connection error (CURLE_QUIC_CONNECT_ERROR)")),
        )
        // TLS handshake — completed before the first HTTP byte is written.
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(curlFailure("SSL connect error (CURLE_SSL_CONNECT_ERROR)")),
        )
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(
                curlFailure("SSL peer certificate or SSH remote key was not OK (CURLE_PEER_FAILED_VERIFICATION)"),
            ),
        )
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(curlFailure("Problem with the local SSL certificate (CURLE_SSL_CERTPROBLEM)")),
        )
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(curlFailure("Could not use specified SSL cipher (CURLE_SSL_CIPHER)")),
        )
        // Curl's CURLOPT_ERRORBUFFER wording, which ktor 3.5.2 does not wire but a later one might.
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(
                RuntimeException("SSL certificate problem: unable to get local issuer certificate"),
            ),
        )
    }

    /** ktor's two dedicated branches, which do not go through the generic "Connection failed" text. */
    @Test
    fun curlHandshakeBranchesAreNotSent() {
        // CurlMultiApiHandler.kt:338-344.
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(
                RuntimeException(
                    "TLS verification failed for request: CurlRequestData(url='https://s3.amazonaws.com/', " +
                        "method='PUT', content: 4 bytes). Reason: SSL peer certificate or SSH remote key was " +
                        "not OK (CURLE_PEER_FAILED_VERIFICATION)",
                ),
            ),
        )
        // CurlMultiApiHandler.kt:346-350 — interpolates a CURLproxycode, so no `curle_` marker
        // can catch this one and it has to be listed on its own.
        assertEquals(
            TransportFailure.NOT_SENT,
            classifyTransportFailure(
                RuntimeException(
                    "Proxy handshake error for request: CurlRequestData(url='https://s3.amazonaws.com/', " +
                        "method='PUT', content: 4 bytes). Reason: CURLPX_BAD_ADDRESS_TYPE",
                ),
            ),
        )
    }

    @Test
    fun aCurlDnsFailureIsFoundThroughNestedCauses() {
        val nested = Exception(
            "call failed",
            IllegalStateException(
                "wrapped",
                RuntimeException("Could not resolve host: dynamodb.us-west-2.amazonaws.com"),
            ),
        )
        assertEquals(TransportFailure.NOT_SENT, classifyTransportFailure(nested))
    }

    /**
     * The load-bearing half. "Connection failed for request:" is curl's *fallback* branch for every
     * unhandled `CURLcode`, send and receive errors included — so the sentence must never match on
     * its own, only the specific codes inside it. Every case here reached the wire, and answering
     * NOT_SENT for any of them would let a `PutEvents` or an `UpdateItem` be applied twice.
     */
    @Test
    fun curlPostSendFailuresStayAmbiguous() {
        assertEquals(
            TransportFailure.AMBIGUOUS,
            classifyTransportFailure(curlFailure("Failed sending data to the peer (CURLE_SEND_ERROR)")),
        )
        assertEquals(
            TransportFailure.AMBIGUOUS,
            classifyTransportFailure(curlFailure("Failure when receiving data from the peer (CURLE_RECV_ERROR)")),
        )
        assertEquals(
            TransportFailure.AMBIGUOUS,
            classifyTransportFailure(curlFailure("Timeout was reached (CURLE_OPERATION_TIMEDOUT)")),
        )
        assertEquals(
            TransportFailure.AMBIGUOUS,
            classifyTransportFailure(
                curlFailure("Server returned nothing (no headers, no data) (CURLE_GOT_NOTHING)"),
            ),
        )
        assertEquals(
            TransportFailure.AMBIGUOUS,
            classifyTransportFailure(
                RuntimeException("transfer closed with outstanding read data remaining"),
            ),
        )
        assertEquals(
            TransportFailure.AMBIGUOUS,
            classifyTransportFailure(RuntimeException("connection reset by peer")),
        )
    }
}

class JsonErrorParsingTest {

    private fun parse(body: String, status: Int = 400, headers: Map<String, String> = emptyMap()) =
        AwsJsonErrorParser.parse(status, headers, body.encodeToByteArray())

    @Test
    fun readsTypeAndSanitisesTheNamespace() {
        val details = parse("""{"__type":"com.amazon.coral.service#InvalidSignatureException","message":"nope"}""")
        assertEquals("InvalidSignatureException", details.code)
        assertEquals("nope", details.message)
    }

    @Test
    fun readsCapitalMMessageWhichDynamoDbUses() {
        assertEquals("boom", parse("""{"__type":"X","Message":"boom"}""").message)
        assertEquals("boom", parse("""{"__type":"X","message":"boom"}""").message)
        assertEquals("boom", parse("""{"__type":"X","errorMessage":"boom"}""").message)
    }

    @Test
    fun headerErrorTypeWins() {
        val details = parse(
            """{"__type":"FromBody"}""",
            headers = mapOf("x-amzn-errortype" to "FromHeader:http://internal"),
        )
        assertEquals("FromHeader", details.code)
    }

    /**
     * The single most dangerous parsing bug available here.
     *
     * A `TransactionCanceledException` carries `CancellationReasons[].Code` with values like
     * `ThrottlingError`. A recursive or case-insensitive search finds one, classifies a
     * permanently-failed atomic transaction as retryable, and replays a write AWS already refused.
     */
    @Test
    fun nestedTypeDoesNotShadowTheTopLevelOne() {
        val body = """
            {
              "__type": "com.amazonaws.dynamodb.v20120810#TransactionCanceledException",
              "Message": "Transaction cancelled",
              "CancellationReasons": [
                {"Code": "ThrottlingError", "Message": "Throttled"},
                {"Code": "None"}
              ],
              "ErrorDetails": [{"__type": "some.other.namespace#ProvisionedThroughputExceededException"}]
            }
        """.trimIndent()

        val details = parse(body)
        assertEquals("TransactionCanceledException", details.code)
        assertNull(classifyRetry(details.code, 400), "a cancelled transaction must never be retried")
    }

    @Test
    fun cancellationReasonsAreAvailableForDiagnosticsOnly() {
        val body = """{"__type":"X","CancellationReasons":[{"Code":"ThrottlingError"},{"Code":"None"}]}"""
        assertEquals(listOf("ThrottlingError", "None"), transactionCancellationReasons(body.encodeToByteArray()))
    }

    @Test
    fun malformedBodiesDegradeInsteadOfThrowing() {
        assertNull(parse("not json at all").code)
        assertNull(parse("").code)
        assertNull(AwsJsonErrorParser.parse(500, emptyMap(), null).code)
        assertEquals(emptyList(), transactionCancellationReasons("garbage".encodeToByteArray()))
    }
}

class XmlErrorParsingTest {

    private fun parse(body: String?, status: Int = 400) =
        RestXmlErrorParser.parse(status, emptyMap(), body?.encodeToByteArray())

    @Test
    fun readsCodeAndMessage() {
        val details = parse("<Error><Code>SlowDown</Code><Message>Please reduce</Message></Error>")
        assertEquals("SlowDown", details.code)
        assertEquals("Please reduce", details.message)
        assertEquals(RetryErrorType.THROTTLING, classifyRetry(details.code, 503))
    }

    @Test
    fun toleratesUnknownChildren() {
        // The Intelligent-Tiering InvalidObjectState variant adds elements we do not model.
        val details = parse(
            "<Error><Code>InvalidObjectState</Code><StorageClass>GLACIER</StorageClass>" +
                "<AccessTier>ARCHIVE</AccessTier><Message>bad state</Message></Error>",
        )
        assertEquals("InvalidObjectState", details.code)
        assertEquals("bad state", details.message)
    }

    @Test
    fun unescapesEntitiesWithAmpersandLast() {
        val details = parse("<Error><Code>C</Code><Message>a &amp;lt; b &lt; c &quot;q&quot;</Message></Error>")
        // &amp;lt; must become "&lt;" literally, not "<".
        assertEquals("a &lt; b < c \"q\"", details.message)
    }

    /** Throwing here would replace a useful service error with a parse error. */
    @Test
    fun malformedOrEmptyBodiesDegradeToNull() {
        assertNull(parse("<Error><Code>Unterminated").code)
        assertNull(parse("").code)
        assertNull(parse(null).code)
        assertNull(parse("<Error></Error>").code)
        // ...and classification then falls back to the status.
        assertEquals(RetryErrorType.TRANSIENT, classifyRetry(parse(null, 503).code, 503))
    }
}
