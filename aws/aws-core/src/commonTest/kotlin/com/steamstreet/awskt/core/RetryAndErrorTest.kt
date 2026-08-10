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
