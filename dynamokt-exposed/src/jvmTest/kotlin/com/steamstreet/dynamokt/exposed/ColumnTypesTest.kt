package com.steamstreet.dynamokt.exposed

import com.steamstreet.dynamokt.AttributeValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Conversion tests for the column types.
 *
 * Deliberately **not** an [ExposedTestBase] subclass: everything under test is pure
 * `Kotlin value <-> AttributeValue` conversion, which needs no DynamoDB, no LocalStack and no
 * Docker. Keeping these as plain unit tests means they actually run in environments where the
 * container suite cannot.
 */
@OptIn(ExperimentalTime::class)
class ColumnTypesTest {

    private object Fixtures : Table("column-types-fixtures") {
        val payload = map("payload")
        val occurredAt = timestamp("occurredAt")
        val ratio = double("ratio")
        val blob = binary("blob")
        val labels = stringSet("labels")
        val counters = numberSet("counters")
    }

    // -- MapColumn ---------------------------------------------------------------------------

    @Test
    fun `map column round-trips a deeply nested structure`() {
        val original = mapOf<String, Any?>(
            "name" to "widget",
            "count" to 3,
            "price" to 19.99,
            "active" to true,
            "absent" to null,
            "tags" to listOf("a", "b"),
            "nested" to mapOf(
                "inner" to listOf(
                    // A map inside a list: the case the old encoder stored as `S(toString())`.
                    mapOf(
                        "deep" to 1.5,
                        "deeper" to mapOf("x" to listOf(1, 2, 3)),
                    ),
                    "plain",
                    null,
                    listOf(true, false),
                ),
            ),
        )

        val decoded = Fixtures.payload.fromAttributeValue(Fixtures.payload.toAttributeValue(original))

        val expected = mapOf<String, Any?>(
            "name" to "widget",
            "count" to 3L,
            "price" to 19.99,
            "active" to true,
            "absent" to null,
            "tags" to listOf("a", "b"),
            "nested" to mapOf(
                "inner" to listOf(
                    mapOf(
                        "deep" to 1.5,
                        "deeper" to mapOf("x" to listOf(1L, 2L, 3L)),
                    ),
                    "plain",
                    null,
                    listOf(true, false),
                ),
            ),
        )
        assertEquals(expected, decoded)
    }

    @Test
    fun `map inside a list encodes as M, not as a stringified object`() {
        val encoded = Fixtures.payload.toAttributeValue(mapOf("items" to listOf(mapOf("k" to "v"))))

        val list = encoded.asM().getValue("items").asL()
        assertEquals(AttributeValue.M(mapOf("k" to AttributeValue.S("v"))), list.single())
    }

    @Test
    fun `doubles are accepted at every depth`() {
        // The old converter threw on a top-level Double but accepted one nested in a list.
        val encoded = Fixtures.payload.toAttributeValue(
            mapOf("top" to 0.25, "nested" to listOf(0.5)),
        )
        assertEquals(AttributeValue.N("0.25"), encoded.asM().getValue("top"))
        assertEquals(AttributeValue.N("0.5"), encoded.asM().getValue("nested").asL().single())
    }

    @Test
    fun `every Number subtype encodes as N`() {
        val encoded = Fixtures.payload.toAttributeValue(
            mapOf(
                "byte" to 1.toByte(),
                "short" to 2.toShort(),
                "int" to 3,
                "long" to 4L,
                "float" to 5.5f,
                "double" to 6.5,
            ),
        ).asM()

        assertEquals(AttributeValue.N("1"), encoded.getValue("byte"))
        assertEquals(AttributeValue.N("2"), encoded.getValue("short"))
        assertEquals(AttributeValue.N("3"), encoded.getValue("int"))
        assertEquals(AttributeValue.N("4"), encoded.getValue("long"))
        assertEquals(AttributeValue.N("5.5"), encoded.getValue("float"))
        assertEquals(AttributeValue.N("6.5"), encoded.getValue("double"))
    }

    @Test
    fun `map column rejects non-String keys`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            Fixtures.payload.toAttributeValue(mapOf("nested" to mapOf(42 to "value")))
        }
        assertTrue(failure.message!!.contains("42"), failure.message)
        assertTrue(failure.message!!.contains("nested"), failure.message)
    }

    @Test
    fun `map column rejects unsupported value types instead of stringifying them`() {
        class Unsupported

        val failure = assertFailsWith<IllegalArgumentException> {
            Fixtures.payload.toAttributeValue(mapOf("items" to listOf(Unsupported())))
        }
        assertTrue(failure.message!!.contains("Unsupported"), failure.message)
        assertTrue(failure.message!!.contains("items[0]"), failure.message)
    }

    @Test
    fun `map column rejects attribute types it cannot represent instead of decoding to null`() {
        val stored = AttributeValue.M(mapOf("labels" to AttributeValue.Ss(listOf("a"))))

        val failure = assertFailsWith<IllegalArgumentException> {
            Fixtures.payload.fromAttributeValue(stored)
        }
        assertTrue(failure.message!!.contains("string set"), failure.message)
    }

    @Test
    fun `map column rejects a non-map attribute`() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.payload.fromAttributeValue(AttributeValue.S("not a map"))
        }
    }

    // -- TimestampColumn ---------------------------------------------------------------------

    @Test
    fun `timestamp encoding is fixed width`() {
        val instants = listOf(
            Instant.fromEpochSeconds(0),
            Instant.fromEpochSeconds(1_700_000_000),
            Instant.fromEpochSeconds(1_700_000_000, 500_000_000),
            Instant.fromEpochSeconds(1_700_000_000, 999_999_999),
            Instant.fromEpochSeconds(1_700_000_000, 1),
            Instant.fromEpochSeconds(-1, 500_000_000),
        )

        instants.forEach { instant ->
            val encoded = Fixtures.occurredAt.toAttributeValue(instant).asS()
            assertEquals(
                "YYYY-MM-DDTHH:MM:SS.nnnnnnnnnZ".length,
                encoded.length,
                "wrong width for $instant: $encoded",
            )
            assertTrue(encoded.endsWith("Z"), encoded)
            // 9 fractional digits plus the 'Z' follow the decimal point.
            assertEquals('.', encoded[encoded.length - 11], encoded)
        }

        assertEquals("1970-01-01T00:00:00.000000000Z", Fixtures.occurredAt.toAttributeValue(Instant.fromEpochSeconds(0)).asS())
        assertEquals(
            "2023-11-14T22:13:20.500000000Z",
            Fixtures.occurredAt.toAttributeValue(Instant.fromEpochSeconds(1_700_000_000, 500_000_000)).asS(),
        )
    }

    /**
     * The regression test for the sort-key bug: DynamoDB compares sort keys byte-wise, so the
     * encoded strings must sort into chronological order all by themselves.
     */
    @Test
    fun `encoded timestamps sort lexicographically in chronological order`() {
        val chronological = listOf(
            Instant.fromEpochSeconds(1_699_999_999, 999_999_999),
            Instant.fromEpochSeconds(1_700_000_000),
            Instant.fromEpochSeconds(1_700_000_000, 1),
            Instant.fromEpochSeconds(1_700_000_000, 500_000_000),
            Instant.fromEpochSeconds(1_700_000_000, 999_999_999),
            Instant.fromEpochSeconds(1_700_000_001),
            Instant.fromEpochSeconds(1_700_000_060, 250_000_000),
        )

        val encoded = chronological.shuffled().map { Fixtures.occurredAt.toAttributeValue(it).asS() }
        val decoded = encoded.sorted().map { Fixtures.occurredAt.fromAttributeValue(AttributeValue.S(it)) }

        assertEquals(chronological, decoded)

        // And the shape of the bug this replaced: the raw `Instant.toString()` form does NOT sort
        // chronologically, because '.' (0x2E) sorts before 'Z' (0x5A).
        val legacySorted = chronological.map { it.toString() }.sorted()
        assertTrue(
            legacySorted != chronological.map { it.toString() },
            "expected the variable-width form to mis-sort; got $legacySorted",
        )
    }

    @Test
    fun `timestamp round-trips nanosecond precision`() {
        val instant = Instant.fromEpochSeconds(1_700_000_000, 123_456_789)
        val roundTripped = Fixtures.occurredAt.fromAttributeValue(Fixtures.occurredAt.toAttributeValue(instant))
        assertEquals(instant, roundTripped)
        assertEquals(123_456_789, roundTripped.nanosecondsOfSecond)
    }

    @Test
    fun `timestamp decoding stays lenient for legacy variable-width values`() {
        val legacy = mapOf(
            "2023-11-14T22:13:20Z" to Instant.fromEpochSeconds(1_700_000_000),
            "2023-11-14T22:13:20.5Z" to Instant.fromEpochSeconds(1_700_000_000, 500_000_000),
            "2023-11-14T22:13:20.123Z" to Instant.fromEpochSeconds(1_700_000_000, 123_000_000),
            "2023-11-14T22:13:20.123456789Z" to Instant.fromEpochSeconds(1_700_000_000, 123_456_789),
            "2023-11-14T23:13:20+01:00" to Instant.fromEpochSeconds(1_700_000_000),
        )

        legacy.forEach { (stored, expected) ->
            assertEquals(expected, Fixtures.occurredAt.fromAttributeValue(AttributeValue.S(stored)), stored)
        }
    }

    // -- DoubleColumn ------------------------------------------------------------------------

    @Test
    fun `double column round-trips`() {
        listOf(0.0, -0.5, 19.99, 1.0E-7, 1.7976931348623157E308).forEach { value ->
            assertEquals(value, Fixtures.ratio.fromAttributeValue(Fixtures.ratio.toAttributeValue(value)))
        }
        assertEquals(AttributeValue.N("19.99"), Fixtures.ratio.toAttributeValue(19.99))
    }

    // -- BinaryColumn ------------------------------------------------------------------------

    @Test
    fun `binary column round-trips`() {
        val bytes = byteArrayOf(0, 1, 2, -1, 127, -128)
        assertEquals(AttributeValue.B(bytes), Fixtures.blob.toAttributeValue(bytes))
        assertContentEquals(bytes, Fixtures.blob.fromAttributeValue(Fixtures.blob.toAttributeValue(bytes)))
        assertContentEquals(
            byteArrayOf(),
            Fixtures.blob.fromAttributeValue(Fixtures.blob.toAttributeValue(byteArrayOf())),
        )
    }

    // -- StringSetColumn / NumberSetColumn ---------------------------------------------------

    @Test
    fun `string set column round-trips`() {
        val labels = setOf("alpha", "beta", "gamma")
        assertEquals(AttributeValue.Ss(labels.toList()), Fixtures.labels.toAttributeValue(labels))
        assertEquals(labels, Fixtures.labels.fromAttributeValue(Fixtures.labels.toAttributeValue(labels)))

        // Members come back in whatever order the service felt like.
        assertEquals(labels, Fixtures.labels.fromAttributeValue(AttributeValue.Ss(listOf("gamma", "alpha", "beta"))))
    }

    @Test
    fun `number set column round-trips`() {
        val counters = setOf(-1L, 0L, 42L, Long.MAX_VALUE)
        assertEquals(
            AttributeValue.Ns(counters.map { it.toString() }),
            Fixtures.counters.toAttributeValue(counters),
        )
        assertEquals(counters, Fixtures.counters.fromAttributeValue(Fixtures.counters.toAttributeValue(counters)))
    }

    @Test
    fun `set columns reject empty sets`() {
        val stringFailure = assertFailsWith<IllegalArgumentException> {
            Fixtures.labels.toAttributeValue(emptySet())
        }
        assertTrue(stringFailure.message!!.contains("labels"), stringFailure.message)
        assertTrue(stringFailure.message!!.contains("Remove the attribute"), stringFailure.message)

        val numberFailure = assertFailsWith<IllegalArgumentException> {
            Fixtures.counters.toAttributeValue(emptySet())
        }
        assertTrue(numberFailure.message!!.contains("counters"), numberFailure.message)
        assertTrue(numberFailure.message!!.contains("Remove the attribute"), numberFailure.message)
    }

    // -- nullable() interplay ------------------------------------------------------------------

    @Test
    fun `new column types can be made nullable`() {
        val ratio = Fixtures.ratio.nullable()
        assertEquals(AttributeValue.Null(true), ratio.toAttributeValue(null))
        assertNull(ratio.fromAttributeValue(AttributeValue.Null(true)))
        assertEquals(2.5, ratio.fromAttributeValue(ratio.toAttributeValue(2.5)))

        val blob = Fixtures.blob.nullable()
        assertEquals(AttributeValue.Null(true), blob.toAttributeValue(null))
        assertNull(blob.fromAttributeValue(AttributeValue.Null(true)))
        assertContentEquals(byteArrayOf(7, 8), blob.fromAttributeValue(blob.toAttributeValue(byteArrayOf(7, 8))))

        val labels = Fixtures.labels.nullable()
        assertEquals(AttributeValue.Null(true), labels.toAttributeValue(null))
        assertNull(labels.fromAttributeValue(AttributeValue.Null(true)))
        assertEquals(setOf("a"), labels.fromAttributeValue(labels.toAttributeValue(setOf("a"))))
        // A nullable set still cannot be empty — null is how "no members" is expressed.
        assertFailsWith<IllegalArgumentException> { labels.toAttributeValue(emptySet()) }

        val counters = Fixtures.counters.nullable()
        assertEquals(AttributeValue.Null(true), counters.toAttributeValue(null))
        assertNull(counters.fromAttributeValue(AttributeValue.Null(true)))
        assertEquals(setOf(9L), counters.fromAttributeValue(counters.toAttributeValue(setOf(9L))))

        val payload = Fixtures.payload.nullable()
        assertEquals(AttributeValue.Null(true), payload.toAttributeValue(null))
        assertNull(payload.fromAttributeValue(AttributeValue.Null(true)))
        assertEquals(
            mapOf<String, Any?>("k" to 1L),
            payload.fromAttributeValue(payload.toAttributeValue(mapOf("k" to 1))),
        )

        val occurredAt = Fixtures.occurredAt.nullable()
        assertEquals(AttributeValue.Null(true), occurredAt.toAttributeValue(null))
        assertNull(occurredAt.fromAttributeValue(AttributeValue.Null(true)))
        val instant = Instant.fromEpochSeconds(1_700_000_000, 5)
        assertEquals(instant, occurredAt.fromAttributeValue(occurredAt.toAttributeValue(instant)))
    }
}
