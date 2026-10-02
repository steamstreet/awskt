package com.steamstreet.awskt.logging

import com.steamstreet.exceptions.MDCExceptionMixin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The non-suspending logging functions as every non-JVM platform implements them: one JSON object
 * per line, in [JsonLogPublisher]'s format. Runs on the host's native target.
 */
class NonSuspendLoggingNativeTest {
    private val lines = mutableListOf<String>()
    private val events: List<JsonObject> get() = lines.map { Json.parseToJsonElement(it).jsonObject }
    private var previousLog = log

    @BeforeTest
    fun capture() {
        lineLogOutput = { lines += it }
        previousLog = log
        log = Log(JsonLogPublisher { lines += it })
    }

    @AfterTest
    fun restore() {
        lineLogOutput = ::println
        log = previousLog
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun infoWritesOneJsonLineWithStringMetadata() {
        logInfo("Order placed", "orderId" to 123, "express" to true, "note" to null)

        val event = events.single()
        assertEquals("INFO", event.string("level"))
        assertEquals("Order placed", event.string("message"))
        // Written as strings, as the JVM's MDC writes them, and a null value is left out.
        assertEquals(JsonPrimitive("123"), event["orderId"])
        assertEquals(JsonPrimitive("true"), event["express"])
        assertFalse("note" in event)
        assertTrue("@timestamp" in event)
        assertFalse(lines.single().contains('\n'))
    }

    @Test
    fun warningAndErrorCarryTheirLevelAndStackTrace() {
        logWarning("Cache miss", "key" to "a")
        logError("Write failed", IllegalStateException("disk full"), "table" to "data")
        logWarning("Plain warning")

        val (warning, error, plain) = events
        assertEquals("WARN", warning.string("level"))
        assertEquals("a", warning.string("key"))
        assertEquals("ERROR", error.string("level"))
        assertEquals("data", error.string("table"))
        assertTrue(error.string("stack_trace")!!.contains("disk full"))
        assertFalse("stack_trace" in plain)
    }

    @Test
    fun throwableMdcAttributesAreAddedAndMetadataWins() {
        val failure = object : Exception("rejected"), MDCExceptionMixin {
            override val mdcAttributes: MutableMap<String, Any?> = mutableMapOf("itemId" to "x1", "stage" to "old")
        }
        logError("Rejected", failure, "stage" to "new")

        val event = events.single()
        assertEquals("x1", event.string("itemId"))
        assertEquals("new", event.string("stage"))
    }

    @Serializable
    data class Payload(val id: String, val count: Int)

    @Test
    fun logValueNestsStructuredJsonUnderTheField() {
        logValue("AppSync call", "request", Payload("e1", 2))

        val event = events.single()
        assertEquals("AppSync call", event.string("message"))
        val request = event["request"]!!.jsonObject
        assertEquals("e1", request.string("id"))
        assertEquals(JsonPrimitive(2), request["count"])
    }

    @Test
    fun mdcContextFieldsReachBothApisAndEndWithTheBlock() = runTest {
        mdcContext("requestId" to "r1", "userId" to null) {
            logInfo("Non-suspending")
            log.info("Suspending")
        }
        logInfo("After")

        val (nonSuspending, suspending, after) = events
        assertEquals("r1", nonSuspending.string("requestId"))
        assertEquals("r1", suspending.string("requestId"))
        assertFalse("userId" in nonSuspending)
        assertFalse("requestId" in after)
    }

    @Test
    fun nestedContextsMergeAndTheInnerValueWins() = runTest {
        mdcContext("requestId" to "r1", "entity" to "outer") {
            mdcContext("entity" to "inner") {
                logInfo("Inner")
            }
            logInfo("Outer")
        }

        val (inner, outer) = events
        assertEquals("r1", inner.string("requestId"))
        assertEquals("inner", inner.string("entity"))
        assertEquals("outer", outer.string("entity"))
    }

    @Test
    fun aContextIsRemovedWhenItsBlockThrows() = runTest {
        assertFailsWith<IllegalStateException> {
            mdcContext("requestId" to "r1") { error("boom") }
        }
        logInfo("After the failure")

        assertFalse("requestId" in events.single())
    }

    @Test
    fun concurrentContextsEachRemoveOnlyTheirOwnFields() = runTest {
        coroutineScope {
            listOf("a" to 20L, "b" to 10L).map { (id, wait) ->
                async {
                    mdcContext("show-$id" to id) {
                        delay(wait)
                    }
                }
            }.awaitAll()
        }
        logInfo("After both")

        val event = events.single()
        assertFalse("show-a" in event)
        assertFalse("show-b" in event)
    }
}
