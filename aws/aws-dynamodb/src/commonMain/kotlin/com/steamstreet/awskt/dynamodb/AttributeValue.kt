package com.steamstreet.awskt.dynamodb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A DynamoDB attribute value.
 *
 * Variant names and the `asX()` / `asXOrNull()` accessor families deliberately match
 * `aws.sdk.kotlin`'s, because roughly 200 call sites across `dynamokt` and `dynamokt-exposed`
 * depend on them and matching costs nothing. The type *identity* differs — that is the API break
 * recorded as Q1 item (a).
 *
 * ### On numbers
 *
 * [N] holds a **String**, not a Double or a Long. DynamoDB numbers carry up to 38 significant
 * digits; routing them through a floating-point type silently corrupts large identifiers and money
 * amounts. This is the single most important representational decision in the type.
 *
 * ### On sets, and a deliberate divergence from the AWS SDK
 *
 * [Ss], [Ns] and [Bs] compare as **sets**: `Ss(["a","b"]) == Ss(["b","a"])`. The AWS SDK's
 * equivalents carry a `List` with generated, order-sensitive equality, so this is a divergence, and
 * it is intentional (Jon, 2026-08-10).
 *
 * The reason is that DynamoDB's set types are genuinely unordered — the service returns members in
 * whatever order it likes, and a LocalStack round trip demonstrated it immediately: `NS: ["1","-2.5"]`
 * came back as `["-2.5","1"]`. Order-sensitive equality therefore makes a set-valued attribute
 * **unequal to itself across a write and a read**, which is not a defensible contract. It breaks
 * anything that diffs items structurally — `dynamokt`'s `findDifferences`/`diff()` in `attributes.kt`
 * most concretely, which would report a spurious change for every set attribute the service
 * reordered. This is the same class of bug that already forced hand-written equality on [B] and
 * [Bs] for `ByteArray`, applied to the property that actually matters for sets.
 *
 * Two consequences worth stating rather than discovering:
 *
 * - **Duplicates collapse.** `Ss(["a","a"]) == Ss(["a"])`. That agrees with the service, which
 *   rejects duplicate members outright — a set carrying them was never going to round-trip anyway.
 * - **The `List` is kept in the constructor**, so element order still survives serialization and is
 *   still visible to a caller that reads `value`. Only *equality* ignores it.
 */
@Serializable(with = AttributeValueSerializer::class)
public sealed class AttributeValue {

    /** A string. May legitimately be empty — DynamoDB has allowed `{"S":""}` since 2020. */
    public data class S(public val value: String) : AttributeValue()

    /** A number, carried as its exact decimal string. See the class KDoc. */
    public data class N(public val value: String) : AttributeValue()

    /** Binary. Base64 on the wire. */
    public class B(public val value: ByteArray) : AttributeValue() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is B && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
        override fun toString(): String = "B(${value.size} bytes)"
    }

    public data class Bool(public val value: Boolean) : AttributeValue()

    /** An explicit null. DynamoDB always encodes it as `{"NULL":true}`. */
    public data class Null(public val value: Boolean = true) : AttributeValue()

    public data class M(public val value: Map<String, AttributeValue>) : AttributeValue()

    public data class L(public val value: List<AttributeValue>) : AttributeValue()

    /**
     * A string set. Equality is **set equality** — see the note on the three set types below.
     *
     * Still a `data class`, so `copy()` and destructuring survive; declaring `equals`/`hashCode`
     * explicitly simply suppresses the generated pair.
     */
    public data class Ss(public val value: List<String>) : AttributeValue() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Ss && value.toSet() == other.value.toSet())

        override fun hashCode(): Int = value.toSet().hashCode()
    }

    /**
     * A number set, each element an exact decimal string. Equality is **set equality**.
     *
     * Compared as strings, not as numbers: `N` deliberately carries an exact decimal string
     * precisely so nothing has to decide what `"1"` and `"1.0"` mean. That question belongs to
     * whoever wrote the values, not to `equals`.
     */
    public data class Ns(public val value: List<String>) : AttributeValue() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Ns && value.toSet() == other.value.toSet())

        override fun hashCode(): Int = value.toSet().hashCode()
    }

    /**
     * A binary set. Equality is **set equality over contents**.
     *
     * `ByteArray` has reference equality, so the elements are mapped to `List<Byte>` before the set
     * comparison — that gets both content semantics and order independence in one step.
     */
    public class Bs(public val value: List<ByteArray>) : AttributeValue() {
        private fun contents(): Set<List<Byte>> = value.mapTo(mutableSetOf()) { it.toList() }

        override fun equals(other: Any?): Boolean =
            this === other || (other is Bs && contents() == other.contents())

        override fun hashCode(): Int = contents().hashCode()
        override fun toString(): String = "Bs(${value.size} blobs)"
    }

    // -- Accessors, matching the AWS SDK's shape ---------------------------------------------

    public fun asS(): String = (this as S).value
    public fun asSOrNull(): String? = (this as? S)?.value

    public fun asN(): String = (this as N).value
    public fun asNOrNull(): String? = (this as? N)?.value

    public fun asB(): ByteArray = (this as B).value
    public fun asBOrNull(): ByteArray? = (this as? B)?.value

    public fun asBool(): Boolean = (this as Bool).value
    public fun asBoolOrNull(): Boolean? = (this as? Bool)?.value

    public fun asNull(): Boolean = (this as Null).value
    public fun asNullOrNull(): Boolean? = (this as? Null)?.value

    public fun asM(): Map<String, AttributeValue> = (this as M).value
    public fun asMOrNull(): Map<String, AttributeValue>? = (this as? M)?.value

    public fun asL(): List<AttributeValue> = (this as L).value
    public fun asLOrNull(): List<AttributeValue>? = (this as? L)?.value

    public fun asSs(): List<String> = (this as Ss).value
    public fun asSsOrNull(): List<String>? = (this as? Ss)?.value

    public fun asNs(): List<String> = (this as Ns).value
    public fun asNsOrNull(): List<String>? = (this as? Ns)?.value

    public fun asBs(): List<ByteArray> = (this as Bs).value
    public fun asBsOrNull(): List<ByteArray>? = (this as? Bs)?.value
}

/**
 * The DynamoDB JSON codec for [AttributeValue] — a single-key tagged union.
 *
 * This *is* the wire format, which is why there is no separate serialization layer: the DTOs carry
 * `AttributeValue` directly and kotlinx-serialization emits DynamoDB JSON with no adapter step.
 */
public object AttributeValueSerializer : KSerializer<AttributeValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AttributeValue")

    override fun serialize(encoder: Encoder, value: AttributeValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("AttributeValue can only be written as JSON")
        jsonEncoder.encodeJsonElement(toJson(value))
    }

    override fun deserialize(decoder: Decoder): AttributeValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("AttributeValue can only be read from JSON")
        return fromJson(jsonDecoder.decodeJsonElement())
    }

    @OptIn(ExperimentalEncodingApi::class)
    public fun toJson(value: AttributeValue): JsonObject = buildJsonObject {
        when (value) {
            is AttributeValue.S -> put("S", JsonPrimitive(value.value))
            is AttributeValue.N -> put("N", JsonPrimitive(value.value))
            is AttributeValue.B -> put("B", JsonPrimitive(Base64.encode(value.value)))
            is AttributeValue.Bool -> put("BOOL", JsonPrimitive(value.value))
            is AttributeValue.Null -> put("NULL", JsonPrimitive(value.value))
            is AttributeValue.Ss -> put("SS", buildJsonArray { value.value.forEach { add(JsonPrimitive(it)) } })
            is AttributeValue.Ns -> put("NS", buildJsonArray { value.value.forEach { add(JsonPrimitive(it)) } })
            is AttributeValue.Bs ->
                put("BS", buildJsonArray { value.value.forEach { add(JsonPrimitive(Base64.encode(it))) } })

            is AttributeValue.L -> put("L", buildJsonArray { value.value.forEach { add(toJson(it)) } })
            is AttributeValue.M ->
                put("M", buildJsonObject { value.value.forEach { (k, v) -> put(k, toJson(v)) } })
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    public fun fromJson(element: JsonElement): AttributeValue {
        val obj = element as? JsonObject
            ?: throw SerializationException("an AttributeValue must be a JSON object, got $element")
        val entry = obj.entries.firstOrNull()
            ?: throw SerializationException("an AttributeValue must carry exactly one key")

        return when (entry.key) {
            "S" -> AttributeValue.S(entry.value.jsonPrimitive.content)
            "N" -> AttributeValue.N(entry.value.jsonPrimitive.content)
            "B" -> AttributeValue.B(Base64.decode(entry.value.jsonPrimitive.content))
            "BOOL" -> AttributeValue.Bool(entry.value.jsonPrimitive.boolean)
            "NULL" -> AttributeValue.Null(entry.value.jsonPrimitive.boolean)
            "SS" -> AttributeValue.Ss(entry.value.jsonArray.map { it.jsonPrimitive.content })
            "NS" -> AttributeValue.Ns(entry.value.jsonArray.map { it.jsonPrimitive.content })
            "BS" -> AttributeValue.Bs(entry.value.jsonArray.map { Base64.decode(it.jsonPrimitive.content) })
            "L" -> AttributeValue.L(entry.value.jsonArray.map { fromJson(it) })
            "M" -> AttributeValue.M(entry.value.jsonObject.mapValues { fromJson(it.value) })
            else -> throw SerializationException("unknown AttributeValue discriminator '${entry.key}'")
        }
    }
}

/** An item: the shape every DynamoDB read and write is expressed in. */
public typealias Item = Map<String, AttributeValue>

// -- Construction conveniences ----------------------------------------------------------------

public fun String.attributeValue(): AttributeValue = AttributeValue.S(this)
public fun Int.attributeValue(): AttributeValue = AttributeValue.N(this.toString())
public fun Long.attributeValue(): AttributeValue = AttributeValue.N(this.toString())
public fun Boolean.attributeValue(): AttributeValue = AttributeValue.Bool(this)
public fun ByteArray.attributeValue(): AttributeValue = AttributeValue.B(this)
