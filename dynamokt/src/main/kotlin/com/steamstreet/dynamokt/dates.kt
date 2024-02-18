package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Attribute mappings for date and times
 */

public fun <R : ItemContainer> R.instantAttribute(attributeName: String? = null): ItemAttributeDelegate<Instant?, R> =
    stringBacked(attributeName) {
        from {
            it?.let { Instant.parse(it) }
        }
        to {
            it.toString()
        }
    }

public fun <R : ItemContainer> R.instantAttribute(
    default: Instant,
    attributeName: String? = null
): ItemAttributeDelegate<Instant, R> = stringBacked(attributeName) {
    from {
        it?.let { Instant.parse(it) } ?: default
    }
    to {
        it.toString()
    }
}

public fun <R : ItemContainer> R.localDateAttribute(attributeName: String? = null): ItemAttributeDelegate<LocalDate?, R> =
    stringBacked(attributeName) {
        from {
            it?.let { LocalDate.parse(it) }
        }
        to {
            it.toString()
        }
    }

public fun <R : ItemContainer> R.localDateAttribute(
    default: LocalDate,
    attributeName: String? = null
): ItemAttributeDelegate<LocalDate, R> = stringBacked(attributeName) {
    from {
        it?.let { LocalDate.parse(it) } ?: default
    }
    to {
        it.toString()
    }
}

/**
 * Get an instant from the given key.
 */
public suspend fun Item.getInstant(key: String): Instant? = get(key)?.instant

public val AttributeValue.localDate: java.time.LocalDate? get() = asSOrNull()?.let { java.time.LocalDate.parse(it) }
public val AttributeValue.localTime: LocalTime? get() = asSOrNull()?.let { LocalTime.parse(it) }
public val AttributeValue.localDateTime: LocalDateTime? get() = asSOrNull()?.let { LocalDateTime.parse(it) }
public val AttributeValue.instant: Instant? get() = asSOrNull()?.let { Instant.parse(it) }


/**
 * Attribute value from an Instant
 */
public fun Instant.attributeValue(): AttributeValue = AttributeValue.S(this.toString())

