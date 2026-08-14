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
 * Column storing Instant values as DynamoDB String (S) type in ISO-8601 format
 */
@OptIn(ExperimentalTime::class)
public class TimestampColumn(
    override val table: Table,
    override val name: String
) : Column<Instant> {
    override fun toAttributeValue(value: Instant): AttributeValue = AttributeValue.S(value.toString())
    override fun fromAttributeValue(value: AttributeValue): Instant = Instant.parse(value.asS())
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
 * Column storing Map values as DynamoDB Map (M) type
 * Values are stored as AttributeValue with limited type support
 */
public class MapColumn(
    override val table: Table,
    override val name: String
) : Column<Map<String, Any?>> {
    override fun toAttributeValue(value: Map<String, Any?>): AttributeValue {
        val mapValues = value.mapValues { (_, v) ->
            when (v) {
                null -> AttributeValue.Null(true)
                is String -> AttributeValue.S(v)
                is Int -> AttributeValue.N(v.toString())
                is Long -> AttributeValue.N(v.toString())
                is Boolean -> AttributeValue.Bool(v)
                is List<*> -> AttributeValue.L(v.map { convertToAttributeValue(it) })
                is Map<*, *> -> toAttributeValue(v as Map<String, Any?>)
                else -> throw IllegalArgumentException("Unsupported map value type: ${v::class}")
            }
        }
        return AttributeValue.M(mapValues)
    }

    override fun fromAttributeValue(value: AttributeValue): Map<String, Any?> {
        return value.asM().mapValues { (_, v) ->
            when {
                v.asNullOrNull() == true -> null
                v.asSOrNull() != null -> v.asS()
                v.asNOrNull() != null -> v.asN().toLongOrNull() ?: v.asN().toDouble()
                v.asBoolOrNull() != null -> v.asBool()
                v.asLOrNull() != null -> v.asL().map { convertFromAttributeValue(it) }
                v.asMOrNull() != null -> fromAttributeValue(v)
                else -> null
            }
        }
    }

    private fun convertToAttributeValue(value: Any?): AttributeValue = when (value) {
        null -> AttributeValue.Null(true)
        is String -> AttributeValue.S(value)
        is Number -> AttributeValue.N(value.toString())
        is Boolean -> AttributeValue.Bool(value)
        else -> AttributeValue.S(value.toString())
    }

    private fun convertFromAttributeValue(value: AttributeValue): Any? = when {
        value.asSOrNull() != null -> value.asS()
        value.asNOrNull() != null -> value.asN().toLongOrNull() ?: value.asN().toDouble()
        value.asBoolOrNull() != null -> value.asBool()
        value.asNullOrNull() == true -> null
        else -> null
    }
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
