package com.steamstreet.dynamokt

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Moved here from `dynamokt`'s JVM test source set, because the code it covers
 * (`toJsonItemString` / `fromJsonToItem`) moved to this module along with `AttributeValue`.
 *
 * The move earns something concrete rather than being tidiness: `dynamo` has native targets, so
 * these now execute on `macosArm64` as well as the JVM. They previously could not — and the
 * assertions were rewritten from kluent to `kotlin.test` for exactly that reason, kluent being
 * JVM-only.
 */
class JsonItemTest {

    @Test
    fun singleAttributeRoundTrips() {
        val json = mapOf("name" to "Jon".attributeValue()).toJsonItemString()
        assertTrue(json.contains("Jon"))

        val item = json.fromJsonToItem()
        assertEquals(setOf("name"), item.keys)
        assertEquals("Jon", assertNotNull(item["name"]).asS())
    }

    /** A set and a list are different DynamoDB types and must not collapse into each other. */
    @Test
    fun setsAndListsStaySeparateTypes() {
        val json = mapOf(
            "name-set" to setOf("Jon", "Steve", "Joe").attributeValue(),
            "name-list" to listOf("Joe", "Gord", "Frank").map { it.attributeValue() }.attributeValue(),
        ).toJsonItemString()

        assertTrue(json.contains("\"SS\""), "a Set must serialize as SS")
        assertTrue(json.contains("\"L\""), "a List must serialize as L")

        val item = json.fromJsonToItem()
        assertEquals(setOf("Jon", "Steve", "Joe"), assertNotNull(item["name-set"]).asSs().toSet())
        assertEquals(3, assertNotNull(item["name-list"]).asL().size)
    }

    @Test
    fun theSerializerIsUsableDirectlyAndThroughAMap() {
        assertEquals("""{"S":"Jon"}""", Json.encodeToString(AttributeValueSerializer, AttributeValue.S("Jon")))
        assertEquals(
            """{"name":{"S":"Jon"},"age":{"N":"48"}}""",
            Json.encodeToString(
                MapSerializer(String.serializer(), AttributeValueSerializer),
                mapOf("name" to AttributeValue.S("Jon"), "age" to AttributeValue.N("48")),
            ),
        )
    }

    /** Empty containers are legal DynamoDB values and must not be elided. */
    @Test
    fun emptyMapsAndListsSerializeAsThemselves() {
        assertEquals("""{"M":{}}""", Json.encodeToString(AttributeValueSerializer, AttributeValue.M(emptyMap())))
        assertEquals("""{"L":[]}""", Json.encodeToString(AttributeValueSerializer, AttributeValue.L(emptyList())))
    }
}
