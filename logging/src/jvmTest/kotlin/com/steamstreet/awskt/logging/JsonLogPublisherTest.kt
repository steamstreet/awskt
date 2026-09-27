package com.steamstreet.awskt.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JsonLogPublisherTest {
    private val publisher = JsonLogPublisher()
    private val now = Instant.parse("2026-09-26T17:04:05.123456789Z")

    private fun format(
        level: Log.Level = Log.Level.INFO,
        message: String? = "hello",
        context: Log.LoggingContext = Log.LoggingContext()
    ): String = publisher.format(level, message, context, now)

    private fun formatToJson(
        level: Log.Level = Log.Level.INFO,
        message: String? = "hello",
        context: Log.LoggingContext = Log.LoggingContext()
    ): JsonObject = Json.parseToJsonElement(format(level, message, context)).jsonObject

    @Test
    fun writesTheReservedFieldsFirst() {
        val line = format()
        assertEquals("""{"@timestamp":"2026-09-26T17:04:05.123Z","level":"INFO","message":"hello"}""", line)
    }

    @Test
    fun writesContextEntriesAtTheTopLevel() {
        val json =
            formatToJson(
                context =
                    Log.LoggingContext(
                        mapOf(
                            "requestId" to JsonPrimitive("req-1"),
                            "count" to JsonPrimitive(3),
                            "person" to buildJsonObject { put("name", "Jon") },
                        )
                    )
            )
        assertEquals("req-1", json["requestId"]?.jsonPrimitive?.content)
        assertEquals(3, json["count"]?.jsonPrimitive?.content?.toInt())
        assertEquals("Jon", json["person"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun skipsNullContextValues() {
        val json = formatToJson(context = Log.LoggingContext(mapOf("missing" to null)))
        assertFalse("missing" in json)
    }

    @Test
    fun keepsAnExplicitJsonNullInTheContext() {
        val json = formatToJson(context = Log.LoggingContext(mapOf("cleared" to JsonNull)))
        assertEquals(JsonNull, json["cleared"])
    }

    @Test
    fun reservedFieldsWinOverContextEntries() {
        val json =
            formatToJson(
                level = Log.Level.WARN,
                message = "real message",
                context =
                    Log.LoggingContext(
                        mapOf(
                            "@timestamp" to JsonPrimitive("fake"),
                            "level" to JsonPrimitive("DEBUG"),
                            "message" to JsonPrimitive("fake message"),
                            "stack_trace" to JsonPrimitive("fake trace"),
                        )
                    ),
            )
        assertEquals("2026-09-26T17:04:05.123Z", json["@timestamp"]?.jsonPrimitive?.content)
        assertEquals("WARN", json["level"]?.jsonPrimitive?.content)
        assertEquals("real message", json["message"]?.jsonPrimitive?.content)
        assertFalse("stack_trace" in json)
    }

    @Test
    fun omitsTheMessageWhenThereIsNone() {
        val json = formatToJson(message = null)
        assertFalse("message" in json)
    }

    @Test
    fun mapsEventToInfo() {
        assertEquals("INFO", formatToJson(level = Log.Level.EVENT)["level"]?.jsonPrimitive?.content)
        assertEquals("ERROR", formatToJson(level = Log.Level.ERROR)["level"]?.jsonPrimitive?.content)
    }

    @Test
    fun writesTheFirstExceptionAsAStackTraceIncludingItsCause() {
        val cause = IllegalStateException("root cause")
        val first = IllegalArgumentException("first", cause)
        val second = RuntimeException("second")
        val json = formatToJson(context = Log.LoggingContext(exceptions = listOf(first, second)))

        val trace = json["stack_trace"]?.jsonPrimitive?.content ?: error("no stack_trace")
        assertTrue(trace.startsWith("java.lang.IllegalArgumentException: first"))
        assertTrue("root cause" in trace)
        assertFalse("second" in trace)
    }

    @Test
    fun keepsMultiLineContentOnOneLine() {
        val line =
            format(
                message = "line one\nline two",
                context = Log.LoggingContext(exceptions = listOf(RuntimeException("boom"))),
            )
        assertFalse('\n' in line)
        assertEquals(
            "line one\nline two",
            Json.parseToJsonElement(line).jsonObject["message"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun omitsTheFractionAtAWholeSecond() {
        val json =
            Json.parseToJsonElement(
                    publisher.format(
                        Log.Level.INFO,
                        "hello",
                        Log.LoggingContext(),
                        Instant.parse("2026-09-26T17:04:05Z"),
                    )
                )
                .jsonObject
        assertEquals("2026-09-26T17:04:05Z", json["@timestamp"]?.jsonPrimitive?.content)
    }

    @Test
    fun publishesThroughTheLogApiWithContextAndException() = runTest {
        val lines = mutableListOf<String>()
        val log = Log(JsonLogPublisher { lines += it })

        log.ctx({ "requestId" `is` "req-1" }) {
            log.error("Handler failed", IllegalStateException("boom")) { "attempt" `is` 2 }
        }

        assertEquals(1, lines.size)
        val json = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("ERROR", json["level"]?.jsonPrimitive?.content)
        assertEquals("Handler failed", json["message"]?.jsonPrimitive?.content)
        assertEquals("req-1", json["requestId"]?.jsonPrimitive?.content)
        assertEquals("2", json["attempt"]?.jsonPrimitive?.content)
        assertTrue(json["stack_trace"]?.jsonPrimitive?.content?.contains("boom") == true)
    }

    @Test
    fun eventIsLoggedAtTheEventLevel() = runTest {
        var published: Log.Level? = null
        val log =
            Log(
                object : LogPublisher {
                    override suspend fun publish(
                        level: Log.Level,
                        message: String?,
                        context: Log.LoggingContext,
                    ) {
                        published = level
                        assertNull(message)
                    }
                }
            )

        log.event { "orderId" `is` "123" }

        assertEquals(Log.Level.EVENT, published)
    }
}
