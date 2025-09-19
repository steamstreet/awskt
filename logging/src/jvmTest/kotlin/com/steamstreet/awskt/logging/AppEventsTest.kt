package com.steamstreet.awskt.logging

import com.steamstreet.awskt.logging.AppEvents.context
import com.steamstreet.awskt.logging.AppEvents.event
import com.steamstreet.awskt.logging.AppEvents.info
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import kotlin.test.BeforeTest
import kotlin.test.Test

class AppEventsTest {
    private val capturedEvents = mutableListOf<JsonObject>()

    @BeforeTest
    fun setup() {
        capturedEvents.clear()
        AppEvents.output { event, _ ->
            capturedEvents.add(event)
            println(event)
        }
    }

    @Test
    fun `context propagates to child coroutines`() = runTest {
        context({
            "requestId" to "req-123"
            "userId" to "user-456"
        }) {
            // Log in parent
            event {
                message("Parent event")
            }

            // Launch child coroutines
            coroutineScope {
                launch {
                    info {
                        message("Child 1 event")
                    }
                }

                async {
                    info {
                        message("Child 2 event")
                    }
                }.await()
            }

            // Nested context
            context({
                "operation" to "nested"
            }) {
                info {
                    message("Nested context event")
                }
            }
        }

        // Verify all events have the context
        capturedEvents.size shouldBeEqualTo 4
        capturedEvents.forEach { event ->
            event["requestId"] shouldBeEqualTo JsonPrimitive("req-123")
            event["userId"] shouldBeEqualTo JsonPrimitive("user-456")
        }

        // Verify nested context has additional field
        val nestedEvent = capturedEvents.find {
            it["message"] == JsonPrimitive("Nested context event")
        }
        nestedEvent?.get("operation") shouldBeEqualTo JsonPrimitive("nested")
    }

    @Test
    fun `context isolation between parallel coroutines`() = runTest {
        coroutineScope {
            // Two parallel coroutines with different contexts
            launch {
                context({
                    "flow" to "flow-A"
                }) {
                    delay(10)
                    info {
                        message("Event from flow A")
                    }
                }
            }

            launch {
                context({
                    "flow" to "flow-B"
                }) {
                    delay(5)
                    info {
                        message("Event from flow B")
                    }
                }
            }
        }

        // Each should have only its own context
        val flowAEvent = capturedEvents.find {
            it["message"] == JsonPrimitive("Event from flow A")
        }
        val flowBEvent = capturedEvents.find {
            it["message"] == JsonPrimitive("Event from flow B")
        }

        flowAEvent?.get("flow") shouldBeEqualTo JsonPrimitive("flow-A")
        flowBEvent?.get("flow") shouldBeEqualTo JsonPrimitive("flow-B")
    }

    @Test
    fun `conditional logging buffers debug until warn or error`() = runTest {
        // Test 1: Debug logs without warn/error should not output
        capturedEvents.clear()
        context({
            "scenario" to "no-warnings"
        }) {
            AppEvents.conditionalLog {
                AppEvents.debug {
                    message("Debug message 1")
                }
                AppEvents.debug {
                    message("Debug message 2")
                }
                info {
                    message("Info message")
                }
            }
        }

        // Only info should be output (debug logs are buffered but not output)
        capturedEvents.size shouldBeEqualTo 1
        capturedEvents[0]["message"] shouldBeEqualTo JsonPrimitive("Info message")

        // Test 2: Debug logs before warn should be output when warn occurs
        capturedEvents.clear()
        context({
            "scenario" to "with-warning"
        }) {
            AppEvents.conditionalLog {
                AppEvents.debug {
                    message("Debug before warn")
                }
                info {
                    message("Info before warn")
                }
                AppEvents.warn {
                    message("Warning occurred")
                }
            }
        }

        // Should have info, warn, and the buffered debug before warn
        capturedEvents.size shouldBeEqualTo 3
        capturedEvents.any { it["message"] == JsonPrimitive("Debug before warn") } shouldBeEqualTo true
        capturedEvents.any { it["message"] == JsonPrimitive("Info before warn") } shouldBeEqualTo true
        capturedEvents.any { it["message"] == JsonPrimitive("Warning occurred") } shouldBeEqualTo true

        // Test 3: Without wrapper, debug logs are now always output
        capturedEvents.clear()
        context({
            "scenario" to "no-wrapper"
        }) {
            AppEvents.debug {
                message("Debug without wrapper")
            }
            info {
                message("Info without wrapper")
            }
        }

        // Both should be output since no conditional wrapper
        capturedEvents.size shouldBeEqualTo 2
    }

    @Test
    fun `conditionalLog wrapper controls debug output`() = runTest {
        // Test 1: Happy path - no debug output
        capturedEvents.clear()
        AppEvents.conditionalLog {
            AppEvents.debug { message("Debug in happy path") }
            AppEvents.info { message("Info in happy path") }
        }

        capturedEvents.size shouldBeEqualTo 1
        capturedEvents[0]["message"] shouldBeEqualTo JsonPrimitive("Info in happy path")

        // Test 2: Error path - debug logs are output
        capturedEvents.clear()
        AppEvents.conditionalLog {
            AppEvents.debug { message("Debug before error") }
            AppEvents.info { message("Info before error") }
            AppEvents.error { message("Something went wrong") }
            AppEvents.debug { message("Debug after error") }
        }

        // Should have all logs including debug
        capturedEvents.size shouldBeEqualTo 4
        capturedEvents.any { it["message"] == JsonPrimitive("Debug before error") } shouldBeEqualTo true
        capturedEvents.any { it["message"] == JsonPrimitive("Debug after error") } shouldBeEqualTo true

        // Test 3: Nested conditionalLog blocks
        capturedEvents.clear()
        AppEvents.conditionalLog {
            AppEvents.debug { message("Outer debug") }

            // Inner block with error
            AppEvents.conditionalLog {
                AppEvents.debug { message("Inner debug") }
                AppEvents.warn { message("Inner warning") }
            }

            AppEvents.info { message("Outer info") }
        }

        // Inner block should output its debug due to warning
        // Outer block debug will NOT be output because outer block had no warnings
        capturedEvents.any { it["message"] == JsonPrimitive("Inner debug") } shouldBeEqualTo true
        capturedEvents.any { it["message"] == JsonPrimitive("Inner warning") } shouldBeEqualTo true
        capturedEvents.any { it["message"] == JsonPrimitive("Outer debug") } shouldBeEqualTo false // Changed expectation
        capturedEvents.any { it["message"] == JsonPrimitive("Outer info") } shouldBeEqualTo true

        // Test 4: Works with coroutine context
        capturedEvents.clear()
        context({
            "requestId" to "test-123"
        }) {
            AppEvents.conditionalLog {
                AppEvents.debug { message("Debug with context") }
                AppEvents.warn { message("Warning with context") }
            }
        }

        // Both should have the context
        capturedEvents.forEach { event ->
            event["requestId"] shouldBeEqualTo JsonPrimitive("test-123")
        }
    }

    @Test
    fun `deeply nested coroutine contexts`() = runTest {
        context({
            "level1" to "value1"
        }) {
            context({
                "level2" to "value2"
            }) {
                coroutineScope {
                    launch {
                        context({
                            "level3" to "value3"
                        }) {
                            info {
                                message("Deeply nested event")
                            }
                        }
                    }
                }
            }
        }

        val event = capturedEvents.find {
            it["message"] == JsonPrimitive("Deeply nested event")
        }

        // Should have all three levels
        event?.get("level1") shouldBeEqualTo JsonPrimitive("value1")
        event?.get("level2") shouldBeEqualTo JsonPrimitive("value2")
        event?.get("level3") shouldBeEqualTo JsonPrimitive("value3")
    }
}