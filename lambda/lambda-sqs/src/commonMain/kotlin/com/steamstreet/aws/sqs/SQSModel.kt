package com.steamstreet.aws.sqs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The SQS event envelope.
 *
 * These are in `commonMain` so a Kotlin/Native Lambda can decode the same payload the JVM handlers
 * decode. The handler base classes stay on the JVM — they are built on `InputStream`/`OutputStream`
 * and the AWS `Context`, none of which exist on Native.
 */
@Serializable
public class SQSRecord(
    public val messageId: String,
    public val receiptHandle: String,
    public val body: String,
    public val attributes: SQSRecordAttributes? = null,
    public val messageAttributes: JsonObject? = null,
    public val md5OfBody: String? = null,
    public val eventSource: String,
    public val eventSourceARN: String,
    public val awsRegion: String
)

@Serializable
public class SQSRecordAttributes(
    public val ApproximateReceiveCount: String? = null,
    public val SentTimestamp: String? = null,
    public val SenderId: String? = null,
    public val ApproximateFirstReceiveTimestamp: String? = null
)

@Serializable
public class SQSEvent(
    public val Records: List<SQSRecord>
)

/**
 * The partial-batch-failure response. Returning this lets SQS redrive only the failed records
 * instead of the whole batch.
 */
@Serializable
public class BatchResponse(
    public val batchItemFailures: List<RecordResponse>
)

@Serializable
public class RecordResponse(
    public val itemIdentifier: String
)
