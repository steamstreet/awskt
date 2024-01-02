package com.steamstreet.aws.lambda.eventbridge

import com.steamstreet.aws.lambda.MockLambdaContext
import com.steamstreet.aws.sqs.BatchResponse
import com.steamstreet.aws.sqs.SQSEvent
import com.steamstreet.aws.sqs.SQSRecord
import com.steamstreet.events.eventSchema
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.amshove.kluent.coInvoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldThrow
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
            context(EventBridgeHandlerConfig) override suspend fun onEvent() {
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
                context(EventBridgeHandlerConfig) override suspend fun onEvent() {
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
}