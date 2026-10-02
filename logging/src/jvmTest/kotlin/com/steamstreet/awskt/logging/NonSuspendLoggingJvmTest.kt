package com.steamstreet.awskt.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the JSON line each non-suspending function writes on the JVM, through Logstash configured as
 * consumers configure it (Vegasful's `logback-ecs.xml`, for example), so that a change to the common
 * declarations cannot silently change the JVM output that CloudWatch metric filters match.
 */
class NonSuspendLoggingJvmTest {
    private val output = ByteArrayOutputStream()
    private lateinit var appender: OutputStreamAppender<ILoggingEvent>
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeTest
    fun attach() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val encoder = LogstashEncoder().apply {
            this.context = context
            fieldNames.levelValue = "[ignore]"
            fieldNames.thread = "[ignore]"
            fieldNames.logger = "[ignore]"
            fieldNames.version = "[ignore]"
            start()
        }
        appender = OutputStreamAppender<ILoggingEvent>().apply {
            this.context = context
            this.encoder = encoder
            outputStream = output
            start()
        }
        root.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        root.detachAppender(appender)
        appender.stop()
    }

    private val events: List<JsonObject>
        get() = output.toString().lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun infoWritesTheMessageAndMetadataAsTopLevelStrings() {
        logInfo("Order placed", "orderId" to 123, "express" to true, "note" to null)

        val event = events.single()
        assertEquals(setOf("@timestamp", "message", "level", "orderId", "express"), event.keys)
        assertEquals("INFO", event.string("level"))
        assertEquals("Order placed", event.string("message"))
        assertEquals(JsonPrimitive("123"), event["orderId"])
        assertEquals(JsonPrimitive("true"), event["express"])
    }

    @Test
    fun warningAndErrorKeepTheirLevelAndStackTrace() {
        logWarning("Cache miss", "key" to "a")
        logError("Write failed", IllegalStateException("disk full"), "table" to "data")

        val (warning, error) = events
        assertEquals("WARN", warning.string("level"))
        assertEquals("a", warning.string("key"))
        assertEquals("ERROR", error.string("level"))
        assertEquals("data", error.string("table"))
        assertTrue(error.string("stack_trace")!!.contains("disk full"))
    }

    @Serializable
    data class Payload(val id: String, val count: Int)

    @Test
    fun logValueWritesRawJsonUnderTheField() {
        logValue("AppSync call", "request", Payload("e1", 2))

        val event = events.single()
        assertEquals(setOf("@timestamp", "message", "level", "request"), event.keys)
        val request = event["request"]!!.jsonObject
        assertEquals("e1", request.string("id"))
        assertEquals(JsonPrimitive(2), request["count"])
    }

    @Test
    fun mdcContextFieldsAppearAndEndWithTheBlock() = runTest {
        mdcContext("requestId" to "r1", "userId" to null) {
            logInfo("Inside")
        }
        logInfo("After")

        val (inside, after) = events
        assertEquals("r1", inside.string("requestId"))
        assertFalse("userId" in inside)
        assertFalse("requestId" in after)
    }
}
