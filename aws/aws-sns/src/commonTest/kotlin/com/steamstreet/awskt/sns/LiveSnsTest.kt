package com.steamstreet.awskt.sns

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof against real SNS.
 *
 * **The most valuable live test in this repository**, and the reason is in the plan's M9 status
 * note: `aws-sns` carries the only hand-written form encoder and the only hand-written XML reader
 * in the library. Every other module's protocol was proven by an earlier milestone's differential;
 * this one's was written from the wire format and checked only against tests written alongside it.
 * A real 200 from SNS is the first independent confirmation that either half is right.
 *
 * Self-skips without credentials:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 \
 *      SMOKE_TOPIC_ARN=arn:aws:sns:us-west-2:123456789012:awskt-smoke \
 *      ./gradlew :aws:aws-sns:jvmTest
 * ```
 *
 * A topic with no subscriptions is the right fixture: publishing to one is a complete exercise of
 * the request and response path and delivers nothing to anybody.
 */
class LiveSnsTest {

    private fun topicArn(): String? {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) return null
        return awsEnv("SMOKE_TOPIC_ARN")?.takeIf { it.isNotBlank() }
    }

    private fun sns() = Sns { caInfo = awsEnv("SMOKE_CA_BUNDLE") }

    /**
     * One publish, with the characters that break a form encoder.
     *
     * The message deliberately contains a space, an ampersand, a plus, an equals and non-ASCII —
     * every character whose encoding the query protocol treats differently from HTML form
     * submission. A `+`-for-space encoder produces a message SNS accepts and mangles, so the
     * returned message id proves only that the request parsed; the encoding assertion that matters
     * is hermetic. What this proves is that SNS **accepts** what we send at all.
     */
    @Test
    fun publishesThroughRealSns() = runTest {
        val topic = topicArn() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_TOPIC_ARN")
            return@runTest
        }

        sns().use { sns ->
            val messageId = sns.publish(
                PublishRequest(
                    message = "awskt live smoke: a b & c + d = e — 日本語 \"quoted\"",
                    topicArn = topic,
                    subject = "awskt live smoke",
                    messageAttributes = mapOf(
                        "kind" to MessageAttributeValue("String", stringValue = "live-smoke"),
                        "blob" to MessageAttributeValue("Binary", binaryValue = byteArrayOf(9, 8, 7)),
                    ),
                ),
            ).messageId

            assertTrue(!messageId.isNullOrBlank(), "SNS returned no MessageId")
            println("[live] SNS published $messageId")
        }
    }

    /**
     * `PublishBatch`, which is where the hand-written **XML reader** earns its keep.
     *
     * The response is the only place this module parses a non-trivial document: `Successful` and
     * `Failed` as sibling lists of `<member>` elements. A reader that scopes to the wrong block, or
     * that mis-handles an empty `Failed`, produces a wrong answer here and nowhere else.
     */
    @Test
    fun publishesABatchLargerThanOneRequestAndReadsTheXmlBack() = runTest {
        val topic = topicArn() ?: run {
            println("[live] skipped — set AWS credentials and SMOKE_TOPIC_ARN")
            return@runTest
        }

        sns().use { sns ->
            // Twelve, so `publishAll` has to chunk — the boundary is 10.
            val entries = (1..12).map {
                PublishBatchRequestEntry(id = "e$it", message = "awskt live batch #$it")
            }
            val results = sns.publishAll(topic, entries)

            assertEquals(12, results.size)
            assertEquals(entries.map { it.id }, results.map { it.id }, "results should be in request order")
            assertTrue(
                results.all { !it.messageId.isNullOrBlank() },
                "every published entry should carry a MessageId read out of the XML",
            )
            println("[live] SNS published a batch of 12 across 2 requests")
        }
    }
}
