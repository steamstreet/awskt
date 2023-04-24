package com.steamstreet.dynamokt

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Attribute mappings for Java date and time
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

public val AttributeValue.localDate: java.time.LocalDate? get() = s()?.let { java.time.LocalDate.parse(it) }
public val AttributeValue.localTime: LocalTime? get() = s()?.let { LocalTime.parse(it) }
public val AttributeValue.localDateTime: LocalDateTime? get() = s()?.let { LocalDateTime.parse(it) }
