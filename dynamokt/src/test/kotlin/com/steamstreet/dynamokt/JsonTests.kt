package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import org.amshove.kluent.*
import kotlin.test.Test

/**
 * Tests for converting to and from json strings for serialization tasks that are Kotlin optimized.
 */
class JsonTests {
    @Test
    fun simple() {
        val jsonString = mapOf(
            "name" to "Jon".attributeValue()
        ).toJsonItemString()

        // just a dumb check to make sure something got written
        jsonString.shouldContain("Jon")

        with(jsonString.fromJsonToItem()) {
            keys.shouldHaveSingleItem().shouldBeEqualTo("name")

            this["name"].shouldNotBeNull().asS().shouldBeEqualTo("Jon")
        }
    }

    @Test
    fun listsVsSets() {
        val jsonString = mapOf(
            "name-set" to setOf("Jon", "Steve", "Joe").attributeValue(),
            "name-list" to listOf("Joe", "Gord", "Frank").map { it.attributeValue() }.attributeValue()
        ).toJsonItemString()

        // make sure there is an 'ss' and 'l' attribute
        jsonString.shouldContain("\"ss\"")
        jsonString.shouldContain("\"l\"")

        with(jsonString.fromJsonToItem()) {
            with(this["name-set"].shouldNotBeNull()) {
                asSs().shouldHaveSize(3).shouldContainAll(listOf("Jon", "Steve", "Joe"))
            }
            with(this["name-list"].shouldNotBeNull()) {
                asL().shouldHaveSize(3)
            }
        }
    }

    @Test
    fun testSerializer() {
        val encoded =
            Json.encodeToString(AttributeValueSerializer(), AttributeValue.S("Jon"))
        println(encoded)

       val mapEncoded = Json.encodeToString(MapSerializer(String.serializer(),
            AttributeValueSerializer()), mapOf(
                "name" to AttributeValue.S("Jon"),
                "ago" to AttributeValue.N("48")
            ))
        println(mapEncoded)
    }
}