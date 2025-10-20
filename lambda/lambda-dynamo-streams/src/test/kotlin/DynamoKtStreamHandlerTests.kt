package com.steamstreet.aws.lambda

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.model.GetRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.kinesis.model.Record
import com.steamstreet.dynamokt.DynamoKt
import com.steamstreet.dynamokt.DynamoStreamEvent
import io.mockk.coEvery
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.intellij.lang.annotations.Language
import java.io.ByteArrayOutputStream
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test

/**
 * Tests for DynamoKtStreamHandler functionality, particularly handling of DynamoDB stream events
 * delivered through various sources including direct streams, Kinesis, and Kinesis DLQ redrives via SQS.
 */
class DynamoKtStreamHandlerTests {
    /**
     * Tests the processing of a Kinesis DLQ (Dead Letter Queue) redrive scenario.
     *
     * When a Lambda function fails to process a batch of Kinesis records after multiple retries,
     * AWS can send the batch metadata to an SQS queue for later reprocessing. This test verifies
     * that the DynamoKtStreamHandler correctly:
     *
     * 1. Receives an SQS message containing KinesisBatchInfo metadata
     * 2. Uses the metadata to fetch the actual records from the Kinesis stream
     * 3. Decodes the DynamoDB stream events from the Kinesis records
     * 4. Processes each event through the handler
     *
     * This is a critical recovery mechanism for handling transient failures in stream processing.
     */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun testProcessKinesisBatchFromSQS() {
        // Create a mock DynamoDB stream event
        @Language("JSON")
        val dynamoStreamEvent = """{
            "eventID": "1",
            "eventName": "INSERT",
            "eventVersion": "1.1",
            "eventSource": "aws:dynamodb",
            "awsRegion": "us-east-1",
            "dynamodb": {
                "ApproximateCreationDateTime": 1445470140.000,
                "Keys": {
                    "Id": {
                        "N": "101"
                    }
                },
                "NewImage": {
                    "Id": {
                        "N": "101"
                    },
                    "Name": {
                        "S": "Test Item"
                    }
                },
                "SequenceNumber": "111",
                "SizeBytes": 26,
                "StreamViewType": "NEW_AND_OLD_IMAGES"
            },
            "eventSourceARN": "arn:aws:dynamodb:us-east-1:123456789012:table/TestTable/stream/2015-06-27T00:48:05.899"
        }"""

        // Prepare the Kinesis record data - this is what would be stored in the Kinesis stream
        // In real scenarios, DynamoDB streams are forwarded to Kinesis, so each Kinesis record
        // contains a JSON representation of a DynamoDB stream event
        val kinesisData = dynamoStreamEvent.toByteArray()

        // Create mock Kinesis client to simulate fetching records from the stream
        val mockKinesisClient = mockk<KinesisClient>()

        // Mock the shard iterator response - this is the first step in reading from Kinesis
        coEvery {
            mockKinesisClient.getShardIterator(any())
        } returns GetShardIteratorResponse {
            shardIterator = "mock-shard-iterator"
        }

        // Mock the actual record retrieval - return a Kinesis record matching the batch info
        // The sequence number must match what's in the KinesisBatchInfo below
        coEvery {
            mockKinesisClient.getRecords(any())
        } returns GetRecordsResponse {
            records = listOf(
                Record {
                    sequenceNumber = "49667874840842633204508412862208444494450743854959165570"
                    partitionKey = "test-partition-key"
                    data = kinesisData
                }
            )
        }

        // Create an SQS message that simulates what AWS sends when a Kinesis batch fails
        // The key part is the "body" which contains the KinesisBatchInfo - this tells us:
        // - Which shard to read from (shardId)
        // - Which records to retrieve (startSequenceNumber and endSequenceNumber)
        // - How many records are in the batch (batchSize)
        // - Which stream to read from (streamArn)
        @Language("JSON")
        val sqsMessage = """{
            "Records": [
                {
                    "messageId": "059f36b4-87a3-44ab-83d2-661975830a7d",
                    "receiptHandle": "AQEBwJnKyrHigUMZj6rYigCgxlaS3SLy0a...",
                    "body": "{\"requestContext\":{\"requestId\":\"84ac92c6-d610-460a-b6bc-63569097850c\",\"functionArn\":\"arn:aws:lambda:us-east-1:637423291883:function:app-uni-content-prod-catalog-relationship-dependencies\",\"condition\":\"RetryAttemptsExhausted\",\"approximateInvokeCount\":11},\"responseContext\":{\"statusCode\":200,\"executedVersion\":\"${'$'}LATEST\",\"functionError\":\"Unhandled\"},\"version\":\"1.0\",\"timestamp\":\"2025-10-20T07:32:49.839Z\",\"KinesisBatchInfo\":{\"shardId\":\"shardId-000000000008\",\"startSequenceNumber\":\"49667874840842633204508412862208444494450743854959165570\",\"endSequenceNumber\":\"49667874840842633204508412862208444494450743854959165570\",\"approximateArrivalOfFirstRecord\":\"2025-10-20T07:18:19.626Z\",\"approximateArrivalOfLastRecord\":\"2025-10-20T07:18:19.626Z\",\"batchSize\":1,\"streamArn\":\"arn:aws:kinesis:us-east-1:637423291883:stream/app-uni-content-prod-catalog-catalog-change-stream\"}}",
                    "attributes": {
                        "ApproximateReceiveCount": "1",
                        "SentTimestamp": "1545082649183",
                        "SenderId": "AIDAIENQZJOLO23YVJ4VO",
                        "ApproximateFirstReceiveTimestamp": "1545082649185"
                    },
                    "messageAttributes": {},
                    "md5OfBody": "e4e68fb7bd0e697a0ae8f1bb342846b3",
                    "eventSource": "aws:sqs",
                    "eventSourceARN": "arn:aws:sqs:us-east-2:123456789012:my-queue",
                    "awsRegion": "us-east-2"
                }
            ]
        }"""

        // Set up mock DynamoKt - required by the handler but not used in this test
        val mockDynamoKt = mockk<DynamoKt>()
        val mockSession = mockk<com.steamstreet.dynamokt.DynamoKtSession>(relaxed = true)
        coEvery { mockDynamoKt.session(any()) } returns mockSession

        // Track which records get processed - this is how we verify the test worked
        val processedRecords = mutableListOf<DynamoStreamEvent>()

        // Create a test handler with the mock Kinesis client
        // The kinesis parameter is critical - it's what allows the handler to fetch records
        // from the stream when it receives batch metadata from SQS
        val handler = object : DynamoKtStreamHandler(
            mockDynamoKt,
            async = false,
            enableBatchItemFailures = true,
            kinesis = mockKinesisClient
        ) {
            override suspend fun handleRecord(record: DynamoStreamEvent) {
                // Capture each processed record for verification
                processedRecords.add(record)
            }
        }

        // Execute the Lambda handler with the SQS message as input
        handler.execute(sqsMessage.byteInputStream(), ByteArrayOutputStream(), MockLambdaContext())

        // Verify that exactly one record was processed (the batch size was 1)
        processedRecords.size.shouldBeEqualTo(1)

        // Verify the record was correctly decoded from Kinesis and has the expected properties
        val record = processedRecords.first()
        record.eventName.shouldBeEqualTo("INSERT")
        record.eventSource.shouldBeEqualTo("aws:dynamodb")
    }
}
