package com.steamstreet.aws.lambda.eventbridge

import com.steamstreet.events.eventSchema
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.amshove.kluent.coInvoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
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

    @Test
    fun testBatch() = runTest {
        val schema = eventSchema<TestData>("Test Event")
        val function = object : EventBridgeFunction {
            context(EventBridgeHandlerConfig) override suspend fun onEvent() {
                schema {
                    check(false)
                }
            }
        }
        coInvoking {
            function.processEvent(schema, TestData("Deep breath"))
        } shouldThrow IllegalStateException::class

        val batchFunction = object : EventBridgeFunction {
            override val batchRetries: Boolean = true
            context(EventBridgeHandlerConfig) override suspend fun onEvent() {
                schema {
                    check(false)
                }
            }
        }
        function.processEvent(
            """
            {
            "Records": [
                {
                }
            ]
        """.trimIndent()
        )
    }
}