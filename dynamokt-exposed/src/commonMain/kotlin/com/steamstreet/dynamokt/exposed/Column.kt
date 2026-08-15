package com.steamstreet.dynamokt.exposed

import com.steamstreet.dynamokt.AttributeValue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Base interface for all column types in DynamoDB tables.
 * Provides type-safe conversion between Kotlin types and DynamoDB AttributeValue.
 */
public interface Column<T> {
    public val name: String
    public val table: Table

    /**
     * Convert a Kotlin value to a DynamoDB AttributeValue
     */
    public fun toAttributeValue(value: T): AttributeValue

    /**
     * Convert a DynamoDB AttributeValue to a Kotlin value
     */
    public fun fromAttributeValue(value: AttributeValue): T

    /**
     * Make this column nullable
     */
    @Suppress("UNCHECKED_CAST")
    public fun nullable(): Column<T?> = NullableColumn(this as Column<Any>) as Column<T?>
}

/**
 * Column storing String values as DynamoDB String (S) type.
 * Equivalent to Exposed's varchar() or text()
 */
public class VarCharColumn(
    override val table: Table,
    override val name: String
) : Column<String> {
    override fun toAttributeValue(value: String): AttributeValue = AttributeValue.S(value)
    override fun fromAttributeValue(value: AttributeValue): String = value.asS()
}

/**
 * Alias for VarCharColumn. In DynamoDB, there's no difference between varchar and text.
 */
public class TextColumn(
    override val table: Table,
    override val name: String
) : Column<String> {
    override fun toAttributeValue(value: String): AttributeValue = AttributeValue.S(value)
    override fun fromAttributeValue(value: AttributeValue): String = value.asS()
}

/**
 * Column storing Int values as DynamoDB Number (N) type
 */
public class IntegerColumn(
    override val table: Table,
    override val name: String
) : Column<Int> {
    override fun toAttributeValue(value: Int): AttributeValue = AttributeValue.N(value.toString())
    override fun fromAttributeValue(value: AttributeValue): Int = value.asN().toInt()
}

/**
 * Column storing Long values as DynamoDB Number (N) type
 */
public class LongColumn(
    override val table: Table,
    override val name: String
) : Column<Long> {
    override fun toAttributeValue(value: Long): AttributeValue = AttributeValue.N(value.toString())
    override fun fromAttributeValue(value: AttributeValue): Long = value.asN().toLong()
}

/**
 * Column storing Boolean values as DynamoDB Boolean (BOOL) type
 */
public class BoolColumn(
    override val table: Table,
    override val name: String
) : Column<Boolean> {
    override fun toAttributeValue(value: Boolean): AttributeValue = AttributeValue.Bool(value)
    override fun fromAttributeValue(value: AttributeValue): Boolean = value.asBool()
}

/**
 * Column storing Double values as DynamoDB Number (N) type.
 *
 * DynamoDB's `N` carries an exact decimal string with up to 38 significant digits, which is *wider*
 * than a `Double` can represent. Reading a value that was written by something else with more
 * precision than a `Double` holds will therefore round; use [LongColumn] (or read the raw
 * `AttributeValue`) when exactness matters. `NaN` and the infinities have no DynamoDB
 * representation at all — writing one produces a string the service rejects.
 */
public class DoubleColumn(
    override val table: Table,
    override val name: String
) : Column<Double> {
    override fun toAttributeValue(value: Double): AttributeValue = AttributeValue.N(value.toString())
    override fun fromAttributeValue(value: AttributeValue): Double = value.asN().toDouble()
}

/**
 * Column storing raw bytes as DynamoDB Binary (B) type.
 *
 * The bytes are handed to the codec as-is; base64 encoding happens at the wire layer, not here.
 */
public class BinaryColumn(
    override val table: Table,
    override val name: String
) : Column<ByteArray> {
    override fun toAttributeValue(value: ByteArray): AttributeValue = AttributeValue.B(value)
    override fun fromAttributeValue(value: AttributeValue): ByteArray = value.asB()
}

/**
 * Column storing a `Set<String>` as DynamoDB String Set (SS) type.
 *
 * A DynamoDB set is genuinely unordered and the service returns members in whatever order it likes,
 * so this maps to a Kotlin `Set` rather than a `List` — see `AttributeValue.Ss`, whose equality is
 * set equality for the same reason.
 *
 * **DynamoDB forbids empty sets.** There is no `{"SS":[]}`; the way to express "no members" is for
 * the attribute not to exist. Encoding an empty set therefore throws [IllegalArgumentException]
 * rather than writing something the service will reject — remove the attribute instead.
 */
public class StringSetColumn(
    override val table: Table,
    override val name: String
) : Column<Set<String>> {
    override fun toAttributeValue(value: Set<String>): AttributeValue {
        require(value.isNotEmpty()) { emptySetMessage(name, "string set (SS)") }
        return AttributeValue.Ss(value.toList())
    }

    override fun fromAttributeValue(value: AttributeValue): Set<String> = value.asSs().toSet()
}

/**
 * Column storing a `Set<Long>` as DynamoDB Number Set (NS) type.
 *
 * `Long` rather than a wider or a floating-point element type: DynamoDB's `NS` holds exact decimal
 * strings, and integral identifiers, timestamps and counters are overwhelmingly what number sets get
 * used for. `Long` round-trips those exactly. A set of fractional numbers would need `Double`, which
 * cannot represent every value DynamoDB can store, so it is left to a hand-written column rather
 * than made the default.
 *
 * The same non-empty rule as [StringSetColumn] applies: DynamoDB has no empty set, so encoding one
 * throws [IllegalArgumentException] — remove the attribute instead.
 */
public class NumberSetColumn(
    override val table: Table,
    override val name: String
) : Column<Set<Long>> {
    override fun toAttributeValue(value: Set<Long>): AttributeValue {
        require(value.isNotEmpty()) { emptySetMessage(name, "number set (NS)") }
        return AttributeValue.Ns(value.map { it.toString() })
    }

    override fun fromAttributeValue(value: AttributeValue): Set<Long> = value.asNs().mapTo(mutableSetOf()) { it.toLong() }
}

private fun emptySetMessage(name: String, kind: String): String =
    "Cannot write an empty $kind to attribute '$name': DynamoDB has no empty set type. " +
        "Remove the attribute instead of writing an empty set."

/**
 * Column storing Instant values as a DynamoDB String (S) in **fixed-width** ISO-8601 UTC:
 * `YYYY-MM-DDTHH:MM:SS.nnnnnnnnnZ` — always exactly nine fractional digits, always a `Z` suffix,
 * always 30 characters.
 *
 * ### Why fixed width
 *
 * DynamoDB compares sort keys **byte-wise as strings**, so a range condition on a timestamp sort key
 * is only correct if lexicographic order matches chronological order. `Instant.toString()` emits
 * variable-width fractional seconds (it omits them entirely when zero, and otherwise prints 3, 6 or
 * 9 digits), which breaks that: `"…T00:00:00.5Z"` sorts *before* `"…T00:00:00Z"` because `'.'`
 * (0x2E) is less than `'Z'` (0x5A), even though it is half a second later. Padding every value to
 * the same shape removes the ambiguity — `between`, `gt`, `lt` and friends on a timestamp sort key
 * then mean what they read as.
 *
 * ### Reading is lenient
 *
 * Decoding goes through `Instant.parse`, which accepts any ISO-8601 form, so values written by an
 * older version of this column (or by another writer) still read back correctly. Only the *write*
 * side is normalized; a table with mixed-width history will read fine but will not order correctly
 * until the old rows are rewritten.
 *
 * ### Caveat
 *
 * The ordering guarantee assumes a four-digit year in `0000..9999`. Instants outside that range
 * render with a `+`/`-` sign and a different number of year digits, which breaks the byte-wise
 * ordering — as does any `Instant.DISTANT_PAST`/`DISTANT_FUTURE` sentinel used as a real key.
 */
@OptIn(ExperimentalTime::class)
public class TimestampColumn(
    override val table: Table,
    override val name: String
) : Column<Instant> {
    override fun toAttributeValue(value: Instant): AttributeValue = AttributeValue.S(format(value))

    override fun fromAttributeValue(value: AttributeValue): Instant = Instant.parse(value.asS())

    private companion object {
        /**
         * Renders [instant] with exactly nine fractional digits.
         *
         * The seconds-truncated instant supplies the calendar part — deriving it here rather than
         * relying on the default `toString()`'s fractional formatting is the whole point — and the
         * nanoseconds are appended zero-padded. `nanosecondsOfSecond` is always in `0..999_999_999`
         * (the epoch second floors toward negative infinity), so the padding is never lossy.
         */
        fun format(instant: Instant): String {
            val whole = Instant.fromEpochSeconds(instant.epochSeconds).toString()
            // `toString()` on a whole-second instant should already be `…:SSZ`, but normalize
            // defensively: strip any fraction and any zone suffix, then re-attach our own.
            val body = whole.removeSuffix("Z").substringBefore('.')
            val seconds = if (body.substringAfter('T').count { it == ':' } < 2) "$body:00" else body
            return "$seconds.${instant.nanosecondsOfSecond.toString().padStart(9, '0')}Z"
        }
    }
}

/**
 * Column storing Enum values as DynamoDB Number (N) type using the enum's ordinal.
 * This matches Exposed's enumeration() API.
 *
 * Takes the enum constants as a list rather than a `KClass`. `KClass.java.enumConstants` is a JVM
 * reflection call with no multiplatform equivalent; the reified [Table.enumeration] factory supplies
 * `enumEntries<T>()` instead, so call sites are unchanged while the class itself becomes portable.
 */
public class EnumerationColumn<T : Enum<T>>(
    override val table: Table,
    override val name: String,
    public val entries: List<T>
) : Column<T> {
    override fun toAttributeValue(value: T): AttributeValue = AttributeValue.N(value.ordinal.toString())

    override fun fromAttributeValue(value: AttributeValue): T {
        val ordinal = value.asN().toInt()
        return entries[ordinal]
    }
}

/**
 * Column storing Enum values as DynamoDB String (S) type using the enum's name.
 * This matches Exposed's enumerationByName() API.
 *
 * See [EnumerationColumn] for why this takes the constants as a list rather than a `KClass`.
 */
public class EnumerationByNameColumn<T : Enum<T>>(
    override val table: Table,
    override val name: String,
    public val entries: List<T>
) : Column<T> {
    override fun toAttributeValue(value: T): AttributeValue = AttributeValue.S(value.name)

    override fun fromAttributeValue(value: AttributeValue): T {
        val enumName = value.asS()
        return entries.first { it.name == enumName }
    }
}

/**
 * Column storing Enum values with custom serialization/deserialization.
 * This matches Exposed's customEnumeration() API.
 *
 * @param fromDb converts the stored string value to the enum type
 * @param toDb converts the enum value to a string for storage
 */
public class CustomEnumerationColumn<T : Enum<T>>(
    override val table: Table,
    override val name: String,
    private val fromDb: (String) -> T,
    private val toDb: (T) -> String
) : Column<T> {
    override fun toAttributeValue(value: T): AttributeValue = AttributeValue.S(toDb(value))

    override fun fromAttributeValue(value: AttributeValue): T = fromDb(value.asS())
}

/**
 * Column storing List values as DynamoDB List (L) type
 */
public class ListColumn<T>(
    override val table: Table,
    override val name: String,
    public val elementColumn: Column<T>
) : Column<List<T>> {
    override fun toAttributeValue(value: List<T>): AttributeValue {
        val listValues = value.map { elementColumn.toAttributeValue(it) }
        return AttributeValue.L(listValues)
    }

    override fun fromAttributeValue(value: AttributeValue): List<T> {
        return value.asL().map { elementColumn.fromAttributeValue(it) }
    }
}

/**
 * Column storing a schemaless `Map<String, Any?>` as a DynamoDB Map (M).
 *
 * Encoding and decoding are **fully recursive and symmetric**: the same converter runs at every
 * depth, so a map inside a list inside a map is handled exactly like one at the top level. (An
 * earlier version had two divergent converters — one for the top level, a lossy one for anything
 * nested — which stored nested maps as `AttributeValue.S(value.toString())` and decoded nested
 * lists and maps back as `null`.)
 *
 * ### Supported values
 *
 * | Kotlin | DynamoDB |
 * |---|---|
 * | `null` | `NULL` |
 * | `String` | `S` |
 * | `Boolean` | `BOOL` |
 * | any `Number` (`Int`, `Long`, `Short`, `Byte`, `Float`, `Double`, …) | `N` |
 * | `List<*>` | `L` (recursed) |
 * | `Map<*, *>` with `String` keys | `M` (recursed) |
 *
 * Anything else throws [IllegalArgumentException] naming the offending path and type. There is no
 * `toString()` fallback: silently storing an arbitrary object's debug rendering is data loss that
 * only shows up on read.
 *
 * ### Round-trip fidelity
 *
 * Round-tripping an arbitrarily nested structure is lossless **except for numeric widening**, which
 * is inherent to DynamoDB: `N` is a single decimal-string type carrying no Kotlin type tag, so
 * every integral value (`Int`, `Short`, `Byte`, `Long`) reads back as `Long` and every fractional
 * one (`Float`, `Double`) reads back as `Double`. Compare decoded numbers accordingly — `42` written
 * as an `Int` comes back equal to `42L`, not to `42`.
 *
 * If a value's Kotlin type must survive exactly, use a typed column instead of this one.
 */
public class MapColumn(
    override val table: Table,
    override val name: String
) : Column<Map<String, Any?>> {
    override fun toAttributeValue(value: Map<String, Any?>): AttributeValue = encodeDynamic(value, "")

    override fun fromAttributeValue(value: AttributeValue): Map<String, Any?> {
        val map = value.asMOrNull()
            ?: throw IllegalArgumentException(
                "Attribute '$name' is not a DynamoDB map (M) but ${describe(value)}"
            )
        return map.mapValues { (key, v) -> decodeDynamic(v, key) }
    }
}

/**
 * Recursively converts a plain Kotlin value into an [AttributeValue].
 *
 * [path] is carried purely so a failure deep inside a nested structure can say *where* it happened;
 * without it the message names a type with no way to find the offending value.
 */
private fun encodeDynamic(value: Any?, path: String): AttributeValue = when (value) {
    null -> AttributeValue.Null(true)
    is String -> AttributeValue.S(value)
    is Boolean -> AttributeValue.Bool(value)
    // `Number` before the container branches, and covering Int/Long/Short/Byte/Float/Double in one
    // arm: the previous split between a top-level whitelist and a nested `is Number` was why a
    // `Double` was rejected at depth 0 and accepted at depth 1.
    is Number -> AttributeValue.N(value.toString())
    is List<*> -> AttributeValue.L(value.mapIndexed { index, element -> encodeDynamic(element, "$path[$index]") })
    is Map<*, *> -> AttributeValue.M(
        value.entries.associate { (key, element) ->
            if (key !is String) {
                throw IllegalArgumentException(
                    "DynamoDB map keys must be Strings, but ${at(path)} has key $key of type ${key?.let { it::class }}"
                )
            }
            key to encodeDynamic(element, if (path.isEmpty()) key else "$path.$key")
        }
    )

    else -> throw IllegalArgumentException(
        "Unsupported value type ${value::class} ${at(path)}. Supported: null, String, Boolean, " +
            "Number, List, and Map with String keys."
    )
}

/** Recursively converts an [AttributeValue] back into a plain Kotlin value. See [MapColumn]. */
private fun decodeDynamic(value: AttributeValue, path: String): Any? = when (value) {
    is AttributeValue.Null -> null
    is AttributeValue.S -> value.value
    // No type tag survives in `N`, so integral values come back as Long and the rest as Double.
    is AttributeValue.N -> value.value.toLongOrNull() ?: value.value.toDouble()
    is AttributeValue.Bool -> value.value
    is AttributeValue.L -> value.value.mapIndexed { index, element -> decodeDynamic(element, "$path[$index]") }
    is AttributeValue.M -> value.value.mapValues { (key, element) ->
        decodeDynamic(element, if (path.isEmpty()) key else "$path.$key")
    }
    // Returning null here (as this used to) turns an unexpected attribute into a silently missing
    // value; the caller cannot distinguish it from a stored NULL.
    else -> throw IllegalArgumentException(
        "Cannot decode ${describe(value)} ${at(path)} into a plain Kotlin value. " +
            "Binary and set attributes need a typed column (binary/stringSet/numberSet)."
    )
}

private fun at(path: String): String = if (path.isEmpty()) "at the map root" else "at '$path'"

private fun describe(value: AttributeValue): String = when (value) {
    is AttributeValue.S -> "a string (S)"
    is AttributeValue.N -> "a number (N)"
    is AttributeValue.B -> "a binary value (B)"
    is AttributeValue.Bool -> "a boolean (BOOL)"
    is AttributeValue.Null -> "a null (NULL)"
    is AttributeValue.M -> "a map (M)"
    is AttributeValue.L -> "a list (L)"
    is AttributeValue.Ss -> "a string set (SS)"
    is AttributeValue.Ns -> "a number set (NS)"
    is AttributeValue.Bs -> "a binary set (BS)"
    is AttributeValue.SdkUnknown -> "an unrecognised attribute type (${value.discriminator})"
}

/**
 * Wrapper for nullable columns
 */
public class NullableColumn<T : Any>(
    public val wrapped: Column<T>
) : Column<T?> {
    override val name: String get() = wrapped.name
    override val table: Table get() = wrapped.table

    override fun toAttributeValue(value: T?): AttributeValue {
        return value?.let { wrapped.toAttributeValue(it) } ?: AttributeValue.Null(true)
    }

    override fun fromAttributeValue(value: AttributeValue): T? {
        return if (value.asNullOrNull() == true) null else wrapped.fromAttributeValue(value)
    }
}
