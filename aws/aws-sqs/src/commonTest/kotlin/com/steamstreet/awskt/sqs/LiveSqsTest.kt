package com.steamstreet.awskt.sqs

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof against real SQS.
 *
 * See `aws-kms`'s `LiveKmsTest` for why these exist alongside the MockEngine suite and why they
 * live in `commonTest`. Self-skips without credentials, so the suite stays hermetic and offline by
 * default:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 \
 *      SMOKE_QUEUE_URL=https://sqs.us-west-2.amazonaws.com/123456789012/awskt-smoke \
 *      ./gradlew :aws:aws-sqs:jvmTest
 * ```
 *
 * **Every message these tests send is deleted before they return**, so a shared smoke queue does
 * not accumulate. A standard (non-FIFO) queue is assumed — the FIFO fields are exercised by the
 * hermetic suite, and a FIFO queue would reject the sends below for want of a `MessageGroupId`.
 */
class LiveSqsTest {

    private fun queueUrl(): String? {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) return null
        return awsEnv("SMOKE_QUEUE_URL")?.takeIf { it.isNotBlank() }
    }

    private fun sqs() = Sqs { caInfo = awsEnv("SMOKE_CA_BUNDLE") }

    /**
     * Send, receive, delete — the whole data plane in one round trip.
     *
     * Proves the AWS-JSON **1.0** dialect and the `AmazonSQS` target prefix together: 1.1 or a
     * wrong prefix is a 400 on the first call. It also proves the point the module KDoc makes about
     * `QueueUrl` being a body field rather than a destination, since the request goes to the
     * regional endpoint and still reaches the queue.
     */
    @Test
    fun sendsReceivesAndDeletesAMessage() = runTest {
        val queue = queueUrl() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_QUEUE_URL")
            return@runTest
        }

        val marker = "awskt-live-${awsEnv("USER") ?: "ci"}-${(0..Int.MAX_VALUE).random()}"
        sqs().use { sqs ->
            sqs.sendMessage(
                SendMessageRequest(
                    queueUrl = queue,
                    messageBody = marker,
                    messageAttributes = mapOf(
                        "kind" to MessageAttributeValue("String", stringValue = "live-smoke"),
                        "blob" to MessageAttributeValue("Binary", binaryValue = byteArrayOf(1, 2, 3)),
                    ),
                ),
            )

            // Long polling, because a message sent a millisecond ago is frequently not visible to
            // the very next short poll — SQS samples its servers, and a short poll here is the
            // classic flaky-test shape.
            val ours = mutableListOf<Message>()
            repeat(3) {
                if (ours.isEmpty()) {
                    ours += sqs.receiveMessages(
                        queue,
                        waitTimeSeconds = 20,
                        maxNumberOfMessages = 10,
                    ).filter { it.body == marker }
                }
            }

            assertTrue(ours.isNotEmpty(), "the message we sent never came back")
            val received = ours.first()
            assertEquals(marker, received.body)

            // Attributes survive the round trip, binary included — which is the base64 codec again.
            val blob = received.messageAttributes["blob"]?.binaryValue
            if (blob != null) {
                assertTrue(byteArrayOf(1, 2, 3).contentEquals(blob), "the binary attribute changed")
            }

            ours.forEach { sqs.deleteMessage(queue, it) }
            println("[live] SQS round-tripped and deleted ${ours.size} message(s)")
        }
    }

    /**
     * The batch helper against a real queue, including the delete half.
     *
     * `sendMessagesAll` and `deleteMessagesAll` are the two places this module reads per-entry
     * failures out of an HTTP 200, and this is the only test where the 200 comes from AWS.
     */
    @Test
    fun sendsAndDeletesABatchLargerThanOneRequest() = runTest {
        val queue = queueUrl() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_QUEUE_URL")
            return@runTest
        }

        val marker = "awskt-batch-${(0..Int.MAX_VALUE).random()}"
        sqs().use { sqs ->
            // Twelve, so the helper has to chunk — the boundary is 10.
            val entries = (1..12).map {
                SendMessageBatchRequestEntry(id = "e$it", messageBody = "$marker#$it")
            }
            val sent = sqs.sendMessagesAll(queue, entries)
            assertEquals(12, sent.size)
            assertTrue(sent.all { it.messageId.isNotBlank() })

            val collected = mutableListOf<Message>()
            repeat(6) {
                if (collected.size < 12) {
                    collected += sqs.receiveMessages(queue, waitTimeSeconds = 20)
                        .filter { it.body?.startsWith(marker) == true }
                }
            }

            // Delete whatever came back, even if fewer than twelve did — leaving messages behind is
            // worse than a soft assertion, because the next run would receive them.
            if (collected.isNotEmpty()) {
                sqs.deleteMessagesAll(
                    queue,
                    collected.mapIndexed { i, m ->
                        DeleteMessageBatchRequestEntry("d$i", m.receiptHandle!!)
                    },
                )
            }
            println("[live] SQS batch sent 12, received and deleted ${collected.size}")
            assertEquals(12, collected.size, "not every batched message came back within the polls")
        }
    }
}
