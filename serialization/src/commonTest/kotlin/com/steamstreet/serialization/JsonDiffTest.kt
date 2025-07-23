package com.steamstreet.serialization

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonDiffTest {

    @Test
    fun testDeepEquals() {
        val obj1 = buildJsonObject {
            put("name", "John")
            put("age", 30)
            put("address", buildJsonObject {
                put("city", "New York")
                put("zip", "10001")
            })
        }

        val obj2 = buildJsonObject {
            put("name", "John")
            put("age", 30)
            put("address", buildJsonObject {
                put("city", "New York")
                put("zip", "10001")
            })
        }

        val obj3 = buildJsonObject {
            put("name", "John")
            put("age", 31) // Different age
            put("address", buildJsonObject {
                put("city", "New York")
                put("zip", "10001")
            })
        }

        assertTrue(obj1.deepEquals(obj2))
        assertFalse(obj1.deepEquals(obj3))
    }

    @Test
    fun testBasicDiff() {
        val original = buildJsonObject {
            put("name", "John")
            put("age", 30)
        }

        val updated = buildJsonObject {
            put("name", "John")
            put("age", 31) // Changed
            put("email", "john@example.com") // Added
        }

        val diff = original.diff(updated)

        val expected = buildJsonObject {
            put("age", 31)
            put("email", "john@example.com")
        }

        assertTrue(diff.deepEquals(expected))
    }

    @Test
    fun testNestedDiff() {
        val original = buildJsonObject {
            put("user", buildJsonObject {
                put("name", "John")
                put("age", 30)
                put("address", buildJsonObject {
                    put("city", "New York")
                    put("zip", "10001")
                })
            })
        }

        val updated = buildJsonObject {
            put("user", buildJsonObject {
                put("name", "John")
                put("age", 31) // Changed
                put("address", buildJsonObject {
                    put("city", "Boston") // Changed
                    put("zip", "10001")
                    put("country", "USA") // Added
                })
            })
        }

        val diff = original.diff(updated)

        val expected = buildJsonObject {
            put("user", buildJsonObject {
                put("age", 31)
                put("address", buildJsonObject {
                    put("city", "Boston")
                    put("country", "USA")
                })
            })
        }

        assertTrue(diff.deepEquals(expected))
    }

    @Test
    fun testComprehensiveDiff() {
        val original = buildJsonObject {
            put("name", "John")
            put("age", 30)
            put("city", "New York")
        }

        val updated = buildJsonObject {
            put("name", "Jane") // Modified
            put("age", 30) // Same
            put("email", "jane@example.com") // Added
            // city removed
        }

        val diff = original.comprehensiveDiff(updated)

        // Check that we have the expected structure
        assertTrue(diff.containsKey("added"))
        assertTrue(diff.containsKey("modified"))
        assertTrue(diff.containsKey("removed"))

        val added = diff["added"]!! as kotlinx.serialization.json.JsonObject
        val modified = diff["modified"]!! as kotlinx.serialization.json.JsonObject
        val removed = diff["removed"]!! as kotlinx.serialization.json.JsonObject

        assertEquals("jane@example.com", added["email"]!!.toString().removeSurrounding("\""))
        assertEquals("Jane", modified["name"]!!.toString().removeSurrounding("\""))
        assertEquals("New York", removed["city"]!!.toString().removeSurrounding("\""))
    }

    @Test
    fun testEmptyDiff() {
        val obj1 = buildJsonObject {
            put("name", "John")
            put("age", 30)
        }

        val obj2 = buildJsonObject {
            put("name", "John")
            put("age", 30)
        }

        val diff = obj1.diff(obj2)
        assertTrue(diff.isEmpty())

        val comprehensiveDiff = obj1.comprehensiveDiff(obj2)
        assertTrue(comprehensiveDiff.isEmpty())
    }

    @Test
    fun testDeepEqualsWithExtraElements() {
        val obj1 = buildJsonObject {
            put("name", "John")
            put("age", 30)
        }

        val obj2 = buildJsonObject {
            put("name", "John")
            put("age", 30)
            put("email", "john@example.com") // Extra element
        }

        val obj3 = buildJsonObject {
            put("name", "John")
            put("age", 30)
            put("address", buildJsonObject {
                put("city", "New York")
                put("zip", "10001")
            })
        }

        val obj4 = buildJsonObject {
            put("name", "John")
            put("age", 30)
            put("address", buildJsonObject {
                put("city", "New York")
                put("zip", "10001")
                put("country", "USA") // Extra nested element
            })
        }

        // These should all return false since the objects have different sizes
        assertFalse(obj1.deepEquals(obj2))
        assertFalse(obj2.deepEquals(obj1))
        assertFalse(obj3.deepEquals(obj4))
        assertFalse(obj4.deepEquals(obj3))
    }
}