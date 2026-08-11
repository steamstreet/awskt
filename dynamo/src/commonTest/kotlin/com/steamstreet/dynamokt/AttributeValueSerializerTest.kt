package com.steamstreet.dynamokt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The codec that owns two wire formats nobody may break: **pagination tokens** and **DynamoDB
 * stream records**.
 *
 * `dynamo` had no test sources at all before this, so its multiplatform port would otherwise have
 * been compile-verified only — which for a serializer means untested.
 *
 * The headline requirement is that a pagination token minted by 2.2.x still decodes after the
 * migration. Tokens are opaque strings handed to clients and handed back later, so a format change
 * is not caught by any test that mints and reads within one version — it shows up as a customer's
 * saved cursor failing. Hence the captured fixture below.
 */
class AttributeValueSerializerTest {

    /**
     * Captured by running the **pre-migration** serializer (the SDK-typed
     * `AttributeValueSerializer` on 2.2.x) before it was deleted, not hand-written to match the new
     * one. That direction matters: a hand-written fixture would assert what the new code does, which
     * is exactly the thing under test.
     */
    private val legacyToken = """
        {"pk":{"S":"customer#1"},"empty":{"S":""},
         "sk":{"N":"99999999999999999999999999999999999999"},"flag":{"BOOL":true},
         "blob":{"B":"AAEC/3+A"},"pad1":{"B":"AQIDBA=="},"pad2":{"B":"AQIDBAU="},
         "tags":{"SS":["a","b"]},"nums":{"NS":["1","-2.5"]},
         "list":{"L":[{"S":"x"},{"N":"2"}]},"map":{"M":{"inner":{"S":"y"}}}}
    """.trimIndent().replace("\n", "").replace(" ", "")

    @Test
    fun aPaginationTokenMintedBeforeTheMigrationStillDecodes() {
        val item = legacyToken.fromJsonToItem()

        assertEquals(AttributeValue.S("customer#1"), item["pk"])
        assertEquals(AttributeValue.S(""), item["empty"])
        assertEquals(
            AttributeValue.N("99999999999999999999999999999999999999"),
            item["sk"],
            "38 significant digits must survive; a Double would round this",
        )
        assertEquals(AttributeValue.Bool(true), item["flag"])
        assertEquals(AttributeValue.B(byteArrayOf(0, 1, 2, -1, 127, -128)), item["blob"])
        assertEquals(AttributeValue.Ss(listOf("a", "b")), item["tags"])
        assertEquals(AttributeValue.Ns(listOf("1", "-2.5")), item["nums"])
        assertEquals(AttributeValue.L(listOf(AttributeValue.S("x"), AttributeValue.N("2"))), item["list"])
        assertEquals(AttributeValue.M(mapOf("inner" to AttributeValue.S("y"))), item["map"])
    }

    /**
     * Base64 has to agree byte for byte with `java.util.Base64.getEncoder()`, which minted the
     * fixture. `pad1` and `pad2` are 4- and 5-byte payloads, the two lengths that produce `==` and
     * `=` padding — the cases where a non-padding alphabet would diverge and a 3-byte-aligned test
     * would not notice.
     */
    @Test
    fun base64PaddingMatchesTheJvmEncoderThatMintedTheFixture() {
        val item = legacyToken.fromJsonToItem()

        assertEquals(AttributeValue.B(byteArrayOf(1, 2, 3, 4)), item["pad1"])
        assertEquals(AttributeValue.B(byteArrayOf(1, 2, 3, 4, 5)), item["pad2"])
        // And re-encoding reproduces the padded forms rather than a bare alphabet.
        val reencoded = mapOf("pad1" to AttributeValue.B(byteArrayOf(1, 2, 3, 4))).toJsonItemString()
        assertEquals("""{"pad1":{"B":"AQIDBA=="}}""", reencoded)
    }

    /** A token that survives a decode must survive the re-encode a paging caller does next. */
    @Test
    fun aLegacyTokenRoundTripsBackToTheSameJson() {
        val item = legacyToken.fromJsonToItem()
        val reencoded = item.toJsonItemString()

        assertEquals(
            Json.parseToJsonElement(legacyToken),
            Json.parseToJsonElement(reencoded),
            "re-encoding a decoded token must reproduce it",
        )
    }

    /**
     * The two variants the **old** serializer could not emit, which is what makes adding them safe.
     *
     * Verified against the pre-migration code rather than assumed: `Bs` threw
     * `ArrayIndexOutOfBoundsException: Index 8 out of bounds for length 8` (it wrote at index 8 of
     * an 8-element descriptor), and `Null` produced the literal `{"x":{null}}` — not valid JSON at
     * all. No token in existence can contain either, so supporting them is strictly widening and
     * cannot invalidate a stored cursor.
     */
    @Test
    fun binarySetsAndNullsNowEncodeCorrectly() {
        assertEquals(
            """{"x":{"BS":["AQI=","Aw=="]}}""",
            mapOf("x" to AttributeValue.Bs(listOf(byteArrayOf(1, 2), byteArrayOf(3)))).toJsonItemString(),
        )
        assertEquals(
            """{"x":{"NULL":true}}""",
            mapOf("x" to AttributeValue.Null()).toJsonItemString(),
        )

        // And both survive a round trip, which the old serializer could not do at all.
        for (value in listOf(AttributeValue.Bs(listOf(byteArrayOf(9))), AttributeValue.Null())) {
            val item = mapOf("x" to value)
            assertEquals(item, item.toJsonItemString().fromJsonToItem())
        }
    }

    /**
     * An unrecognised variant must not throw.
     *
     * This codec runs inside DynamoDB stream handlers, where an exception poisons the shard and the
     * record is retried until it expires. Carrying the value through as [AttributeValue.SdkUnknown]
     * degrades one attribute instead of failing the whole record — and losslessly, so a pagination
     * token containing one is not silently corrupted by a decode/re-encode cycle.
     */
    @Test
    fun anUnknownVariantIsCarriedThroughRatherThanThrowing() {
        val json = """{"pk":{"S":"a"},"weird":{"XX":{"nested":1}}}"""

        val item = json.fromJsonToItem()

        assertEquals(AttributeValue.S("a"), item["pk"])
        val unknown = assertIs<AttributeValue.SdkUnknown>(item["weird"])
        assertEquals("XX", unknown.discriminator)
        assertEquals(
            Json.parseToJsonElement(json),
            Json.parseToJsonElement(item.toJsonItemString()),
            "an unknown variant must re-encode losslessly",
        )
    }

    /** Malformed input still fails loudly — tolerance is for unknown variants, not for garbage. */
    @Test
    fun structurallyInvalidAttributesStillFail() {
        assertFailsWith<Exception> { """{"x":{}}""".fromJsonToItem() }
        assertFailsWith<Exception> { """{"x":"not-an-object"}""".fromJsonToItem() }
    }

    @Test
    fun deeplyNestedStructuresRoundTrip() {
        val item = mapOf(
            "root" to AttributeValue.M(
                mapOf(
                    "l" to AttributeValue.L(
                        listOf(
                            AttributeValue.M(mapOf("inner" to AttributeValue.L(listOf(AttributeValue.Null())))),
                            AttributeValue.B(byteArrayOf(9)),
                            AttributeValue.Bs(listOf(byteArrayOf(1), byteArrayOf(2))),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(item, item.toJsonItemString().fromJsonToItem())
    }

    /** `fromJsonToAttributeValue` decodes a single attribute, not an item. */
    @Test
    fun singleAttributeDecoding() {
        assertEquals(AttributeValue.N("42"), """{"N":"42"}""".fromJsonToAttributeValue())
    }

    /**
     * The pagination-token guard at the JSON-config level: `encodeDefaults = false` and
     * `ignoreUnknownKeys = true` are what let a token gain fields later without old readers
     * breaking. Asserted so a future "tidy-up" of the config fails here rather than in production.
     */
    @Test
    fun theTokenJsonConfigStaysLenient() {
        assertTrue(attributeValueJson.configuration.ignoreUnknownKeys)
        assertTrue(!attributeValueJson.configuration.encodeDefaults)
    }
}
