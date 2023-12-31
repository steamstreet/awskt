package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
        jsonString.shouldContain("\"SS\"")
        jsonString.shouldContain("\"L\"")

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

    @Test
    fun testEmptyMap() {
        val attr = AttributeValue.M(emptyMap())
        val str = Json.encodeToString(AttributeValueSerializer(), attr)

        str.shouldBeEqualTo("""{"M":{}}""")
    }

    @Test
    fun testEmptyList() {
        val attr = AttributeValue.L(emptyList())
        val str = Json.encodeToString(AttributeValueSerializer(), attr)

        str.shouldBeEqualTo("""{"L":[]}""")
    }

    @Test
    fun testJsonToItem() {
        val jsonElement = Json.parseToJsonElement(complexJson)

        val item = jsonElement.fromJsonToItem()
        item.get("venue").shouldNotBeNull().asS().shouldBeEqualTo("the-strip")
    }
}

val complexJson = """
    {"venue":{"value":"the-strip"},"images":{"value":[]},"_link":{"value":"event"},"_linkRange":{"value":"INACTIVE"},"${'$'}disabled":{"value":{"inherit":{"value":false}}},"${'$'}_resolvedLinks":{"value":{"inherit":{"value":false}}},"${'$'}images":{"value":{"inherit":{"value":false}}},"_resolvedLinks":{"value":[]},"segment":{"value":"resolved"},"name":{"value":"Event A"},"disabled":{"value":true},"id":{"value":"event:5e2a62ee-08f0-437a-8549-cca7312ec76e"}}
""".trimIndent()

