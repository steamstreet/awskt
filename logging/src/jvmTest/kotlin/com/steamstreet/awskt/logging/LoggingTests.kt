package com.steamstreet.awskt.logging

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class LoggingTests {
    @Test
    fun basics() = runTest {
        log.info("First log")

        log.info("Second log") {
            "name" `is` "Jon"
        }

        log.ctx({
            "name" `is` "Jon"
        }) {
            log.info("Third log")
        }

        log.ctx({
            "name" `is` "Steve"
        }) {
            launch {
                log.info("Launched log")
            }
        }

        log.ctx({
            "person" `is` buildJsonObject {
                put("name", "Jon")
                put("age", 20)
            }
        }) {
            launch {
                log.info("Complex context")
            }
        }

        log.info("Person log") {
            put("person", Person(name = "Jon", age = 20))
        }

        log.ctx({
            put("person", Person(name = "Jon", age = 20))
        }) {
            try {
                throw IllegalArgumentException("Whoops")
            } catch (e: Throwable) {
                log.error("Error log", e)
            }
        }
    }
}

@Serializable
data class Person(val name: String, val age: Int)