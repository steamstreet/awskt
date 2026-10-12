package com.steamstreet.aws.lambda.eventbridge

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.steamstreet.aws.lambda.MockLambdaContext
import com.steamstreet.aws.sqs.BatchResponse
import com.steamstreet.aws.sqs.SQSEvent
import com.steamstreet.aws.sqs.SQSRecord
import com.steamstreet.events.eventSchema
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.amshove.kluent.coInvoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldThrow
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.test.Test

@Serializable
class TestData(
    val something: String? = null
)

@ExperimentalCoroutinesApi
class EventBridgeTests {
    /**
     * Simple test to confirm that the direct calls are working properly.
     */
    @Test
    fun testDirectCall() = runTest {
        var something: String? = null
        val schema = eventSchema<TestData>("Test Event")
        val function = object : EventBridgeFunction {
            override suspend fun EventBridgeHandlerConfig.onEvent() {
                schema {
                    something = it.something
                }
            }
        }

        function.processEvent(schema, TestData("Deep breath"))
        something.shouldBeEqualTo("Deep breath")
    }

    /**
     * Test that batch events are returning the correct response.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun testBatch() {
        runTest {
            val schema = eventSchema<TestData>("Test Event")

            /**
             * A failing function.
             */
            class FailingFunction(override val batchRetries: Boolean) : EventBridgeFunction {
                override suspend fun EventBridgeHandlerConfig.onEvent() {
                    schema {
                        check(false)
                    }
                }
            }

            val recordId = UUID.randomUUID().toString()
            val event = SQSEvent(
                listOf(
                    SQSRecord(
                        recordId,
                        "receipt-1",
                        Json.encodeToString(
                            EventBridgeEvent(
                                detailType = schema.type,
                                detail = Json.encodeToJsonElement(TestData("Deep breath")).jsonObject,
                                source = "aws-kt"
                            )
                        ),
                        eventSource = "eventbridge",
                        eventSourceARN = "arn",
                        awsRegion = "us-west-2"
                    )
                )
            )


            coInvoking {
                FailingFunction(false).execute(
                    Json.encodeToString(event).byteInputStream(),
                    ByteArrayOutputStream(),
                    MockLambdaContext()
                )
            } shouldThrow IllegalStateException::class

            // now a failing function that is configured with batch fails.

            val output = ByteArrayOutputStream()
            FailingFunction(true).execute(Json.encodeToString(event).byteInputStream(), output, MockLambdaContext())

            val response = Json.decodeFromStream(BatchResponse.serializer(), output.toByteArray().inputStream())
            response.batchItemFailures.shouldHaveSingleItem().itemIdentifier.shouldBeEqualTo(recordId)
        }
    }

    private val schema = eventSchema<TestData>("Test Event")

    private val envelope = EventBridgeEvent(
        id = "event-1",
        detailType = schema.type,
        detail = Json.encodeToJsonElement(TestData("Deep breath")).jsonObject,
        source = "aws-kt",
        account = "123456789012"
    )

    /**
     * The same event as it arrives directly from EventBridge.
     */
    private fun direct(): String = Json.encodeToString(envelope)

    /**
     * The same event as it arrives when the rule targets an SQS queue.
     */
    private fun sqsWrapped(messageId: String): String = Json.encodeToString(
        SQSEvent(
            listOf(
                SQSRecord(
                    messageId,
                    "receipt-1",
                    Json.encodeToString(envelope),
                    eventSource = "aws:sqs",
                    eventSourceARN = "arn",
                    awsRegion = "us-west-2"
                )
            )
        )
    )

    private fun function(handler: suspend context(Event) (TestData) -> Unit): EventBridgeFunction =
        object : EventBridgeFunction {
            override val batchRetries: Boolean = true

            override suspend fun EventBridgeHandlerConfig.onEvent() {
                schema(handler)
            }
        }

    /**
     * Captures the MDC of each log line by message, since logback only reads the MDC of an
     * event lazily.
     */
    private class MdcAppender : AppenderBase<ILoggingEvent>() {
        val events = mutableListOf<Pair<String, Map<String, String?>>>()

        override fun append(event: ILoggingEvent) {
            events.add(event.message to event.mdcPropertyMap.toMap())
        }

        fun mdcOf(message: String): Map<String, String?> = events.single { it.first == message }.second
    }

    private fun capturingLogs(block: () -> Unit): MdcAppender {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = MdcAppender().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender
    }

    /**
     * A handler should see the same logging context for an event however it was delivered.
     */
    @Test
    fun testLogContextMatchesAcrossDeliveryPaths() {
        val contexts = mutableListOf<Map<String, String>>()
        val function = function {
            contexts.add(MDC.getCopyOfContextMap())
        }

        val logs = capturingLogs {
            function.processEvent(direct())
            function.processEvent(sqsWrapped("message-1"))
        }

        val (directContext, sqsContext) = contexts
        directContext["event-detail-type"].shouldBeEqualTo(schema.type)
        sqsContext["event-detail-type"].shouldBeEqualTo(schema.type)
        sqsContext.keys.shouldBeEqualTo(directContext.keys)

        // the line the library logs for each event carries the detail type on both paths
        logs.events.filter { it.first == "Processing event" }.map { it.second["event-detail-type"] }
            .shouldBeEqualTo(listOf(schema.type, schema.type))
    }

    /**
     * The id of an event is the EventBridge id on both paths, and the SQS message id is
     * available separately.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun testEventIdIsTheEventBridgeId() {
        val ids = mutableListOf<Pair<String, String?>>()
        val function = function {
            val event = contextOf<Event>()
            ids.add(event.id to event.messageId)
            check(event.messageId == null) { "Fail the SQS delivery" }
        }

        function.processEvent(direct())
        val output = ByteArrayOutputStream()
        function.execute(sqsWrapped("message-1").byteInputStream(), output, MockLambdaContext())

        ids.shouldBeEqualTo(listOf("event-1" to null, "event-1" to "message-1"))

        // failures are still reported by the SQS message id
        val response = Json.decodeFromStream(BatchResponse.serializer(), output.toByteArray().inputStream())
        response.batchItemFailures.shouldHaveSingleItem().itemIdentifier.shouldBeEqualTo("message-1")
    }

    /**
     * A failed SQS delivery logs everything a failed direct invocation does, along with the
     * message id.
     */
    @Test
    fun testFailureLogMatchesAcrossDeliveryPaths() {
        // A failure in the handler configuration is what the direct path logs.
        val failing = object : EventBridgeFunction {
            override val batchRetries: Boolean = true

            override suspend fun EventBridgeHandlerConfig.onEvent() {
                if (allEvents().single().messageId == null) error("Failed")
                schema { error("Failed") }
            }
        }

        val logs = capturingLogs {
            runCatching { failing.processEvent(direct()) }
            failing.processEvent(sqsWrapped("message-1"))
        }

        // the request id differs for every invocation
        val requestIds = setOf("requestId", "@requestId")
        val directFailure = logs.mdcOf("Failure handling event") - requestIds
        val sqsFailure = logs.mdcOf("Event processing failed") - requestIds

        sqsFailure["messageId"].shouldBeEqualTo("message-1")
        directFailure["messageId"].shouldBeNull()
        (sqsFailure - "messageId").shouldBeEqualTo(directFailure)

        listOf("event-detail-type", "detail-type", "source", "time", "account", "detail").forEach {
            sqsFailure.keys.contains(it).shouldBeEqualTo(true)
        }
    }
}
