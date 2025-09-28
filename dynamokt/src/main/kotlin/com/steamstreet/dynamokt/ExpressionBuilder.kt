@file:OptIn(ExperimentalTime::class)

package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * DynamoDB attribute types for type checking.
 */
public enum class AttributeType(public val dynamoType: String) {
    STRING("S"),
    NUMBER("N"),
    BINARY("B"),
    STRING_SET("SS"),
    NUMBER_SET("NS"),
    BINARY_SET("BS"),
    MAP("M"),
    LIST("L"),
    NULL("NULL"),
    BOOLEAN("BOOL")
}

/**
 * Shared state for ExpressionBuilder instances to ensure unique naming and value mapping.
 */
internal class ExpressionBuilderState {
    var index: Int = 1
    val nameMap = mutableMapOf<String, String>()
    val nameReverseMap = mutableMapOf<String, String>()
    val valueMap = mutableMapOf<String, AttributeValue>()
}

/**
 * Builds filter expressions.
 */
public class ExpressionBuilder internal constructor(private val state: ExpressionBuilderState = ExpressionBuilderState()) {
    internal val expressions = arrayListOf<String>()

    // Expose state for compatibility with existing code
    internal val nameMap: MutableMap<String, String> get() = state.nameMap
    internal val valueMap: MutableMap<String, AttributeValue> get() = state.valueMap

    // Public constructor
    public constructor() : this(ExpressionBuilderState())

    public fun addExpression(
        exp: String,
        names: Map<String, String> = emptyMap(),
        values: Map<String, AttributeValue> = emptyMap()
    ) {
        expressions += exp
        nameMap.putAll(names)
        valueMap.putAll(values)
    }

    public fun attributeExists(name: String) {
        addExpression("attribute_exists(${getAttrName(name)})")
    }

    public fun attributeNotExists(name: String) {
        addExpression("attribute_not_exists(${getAttrName(name)})")
    }

    private fun getAttrName(attrName: String): String {
        val names = attrName.split(".")
        return names.map { name ->
            val existing = state.nameReverseMap[name]
            if (existing != null) return existing

            val key = "#n${state.index++}"
            state.nameMap[key] = name
            state.nameReverseMap[name] = key
            key
        }.joinToString(".")
    }

    private fun valueKey(value: String): String {
        val key = ":v${state.index++}"
        val attributeValue = value.attributeValue()
        state.valueMap[key] = attributeValue
        return key
    }

    private fun valueKey(value: Number): String {
        val key = ":v${state.index++}"
        val attributeValue = value.attributeValue()
        state.valueMap[key] = attributeValue
        return key
    }

    public fun valueIn(attribute: String, values: List<String>) {
        expressions += "${getAttrName(attribute)} IN (${values.map { valueKey(it) }.joinToString(",")})"
    }

    public fun valueInNumbers(attribute: String, values: List<Number>) {
        expressions += "${getAttrName(attribute)} IN (${values.map { valueKey(it) }.joinToString(",")})"
    }

    public fun greaterThan(attribute: String, value: String) {
        expressions += "${getAttrName(attribute)} > ${valueKey(value)}"
    }

    public fun greaterThan(attribute: String, value: Instant) {
        greaterThan(attribute, value.toString())
    }

    public fun lessThan(attribute: String, value: String) {
        expressions += "${getAttrName(attribute)} < ${valueKey(value)}"
    }

    public fun lessThan(attribute: String, value: Instant) {
        lessThan(attribute, value.toString())
    }

    public fun greaterThan(attribute: String, value: Number) {
        expressions += "${getAttrName(attribute)} > ${valueKey(value)}"
    }

    public fun lessThan(attribute: String, value: Number) {
        expressions += "${getAttrName(attribute)} < ${valueKey(value)}"
    }

    public fun greaterThanOrEqualTo(attribute: String, value: Number) {
        expressions += "${getAttrName(attribute)} >= ${valueKey(value)}"
    }

    public fun lessThanOrEqualTo(attribute: String, value: Number) {
        expressions += "${getAttrName(attribute)} <= ${valueKey(value)}"
    }

    public fun startsWith(attribute: String, prefix: String) {
        expressions += "begins_with(${getAttrName(attribute)}, ${valueKey(prefix)})"
    }

    public fun equalTo(attribute: String, value: String) {
        expressions += "${getAttrName(attribute)} = ${valueKey(value)}"
    }

    public fun equalTo(attribute: String, value: Number) {
        expressions += "${getAttrName(attribute)} = ${valueKey(value)}"
    }

    public fun notEqualTo(attribute: String, value: String) {
        expressions += "${getAttrName(attribute)} <> ${valueKey(value)}"
    }

    public fun notEqualTo(attribute: String, value: Number) {
        expressions += "${getAttrName(attribute)} <> ${valueKey(value)}"
    }

    public fun between(attribute: String, low: Number, high: Number) {
        expressions += "${getAttrName(attribute)} BETWEEN ${valueKey(low)} AND ${valueKey(high)}"
    }

    public fun between(attribute: String, low: String, high: String) {
        expressions += "${getAttrName(attribute)} BETWEEN ${valueKey(low)} AND ${valueKey(high)}"
    }

    public fun contains(attribute: String, value: String) {
        expressions += "contains(${getAttrName(attribute)}, ${valueKey(value)})"
    }

    public fun contains(attribute: String, value: Number) {
        expressions += "contains(${getAttrName(attribute)}, ${valueKey(value)})"
    }

    public fun size(attribute: String): SizeAttribute = SizeAttribute(attribute)

    public fun attributeType(attribute: String, type: AttributeType) {
        expressions += "attribute_type(${getAttrName(attribute)}, ${valueKey(type.dynamoType)})"
    }

    public fun isNull(attribute: String) {
        expressions += "attribute_type(${getAttrName(attribute)}, ${valueKey("NULL")})"
    }

    public fun isNotNull(attribute: String) {
        expressions += "attribute_type(${getAttrName(attribute)}) <> ${valueKey("NULL")}"
    }

    public fun isEmpty(attribute: String) {
        expressions += "size(${getAttrName(attribute)}) = ${valueKey(0)}"
    }

    public fun isNotEmpty(attribute: String) {
        expressions += "size(${getAttrName(attribute)}) > ${valueKey(0)}"
    }

    public fun or(block: ExpressionBuilder.() -> Unit) {
        val orBuilder = ExpressionBuilder(state)
        orBuilder.block()

        if (orBuilder.expressions.isNotEmpty()) {
            val orExpression = if (orBuilder.expressions.size == 1) {
                orBuilder.expressions.first()
            } else {
                "(${orBuilder.expressions.joinToString(" AND ")})"
            }
            expressions += orExpression
        }
    }

    public fun expression(): String? {
        return if (expressions.isEmpty()) {
            null
        } else {
            expressions.joinToString(" AND ")
        }
    }

    public inner class SizeAttribute(private val attribute: String) {
        public fun greaterThan(value: Number) {
            expressions += "size(${getAttrName(attribute)}) > ${valueKey(value)}"
        }

        public fun lessThan(value: Number) {
            expressions += "size(${getAttrName(attribute)}) < ${valueKey(value)}"
        }

        public fun greaterThanOrEqualTo(value: Number) {
            expressions += "size(${getAttrName(attribute)}) >= ${valueKey(value)}"
        }

        public fun lessThanOrEqualTo(value: Number) {
            expressions += "size(${getAttrName(attribute)}) <= ${valueKey(value)}"
        }

        public fun equalTo(value: Number) {
            expressions += "size(${getAttrName(attribute)}) = ${valueKey(value)}"
        }
    }

    public inner class FilterAttribute(public val name: String) {
        public infix fun isOneOf(values: List<String>) {
            valueIn(name, values)
        }

        public fun isOneOfNumbers(values: List<Number>) {
            valueInNumbers(name, values)
        }

        public fun exists() {
            attributeExists(name)
        }

        public fun equalTo(value: String) {
            equalTo(name, value)
        }

        public fun equalTo(value: Number) {
            equalTo(name, value)
        }

        public fun greaterThan(value: Number) {
            greaterThan(name, value)
        }

        public fun lessThan(value: Number) {
            lessThan(name, value)
        }

        public fun greaterThanOrEqualTo(value: Number) {
            greaterThanOrEqualTo(name, value)
        }

        public fun lessThanOrEqualTo(value: Number) {
            lessThanOrEqualTo(name, value)
        }

        public fun notEqualTo(value: String) {
            notEqualTo(name, value)
        }

        public fun notEqualTo(value: Number) {
            notEqualTo(name, value)
        }

        public fun between(low: Number, high: Number) {
            between(name, low, high)
        }

        public fun between(low: String, high: String) {
            between(name, low, high)
        }

        public fun contains(value: String) {
            contains(name, value)
        }

        public fun contains(value: Number) {
            contains(name, value)
        }

        public fun size(): SizeAttribute = SizeAttribute(name)

        public fun hasType(type: AttributeType) {
            attributeType(name, type)
        }

        public fun isNull() {
            isNull(name)
        }

        public fun isNotNull() {
            isNotNull(name)
        }

        public fun isEmpty() {
            isEmpty(name)
        }

        public fun isNotEmpty() {
            isNotEmpty(name)
        }
    }

    public fun attribute(attribute: String): FilterAttribute = FilterAttribute(attribute)
}

public fun ExpressionBuilder.apply(scan: ScanRequest.Builder) {
    val expr = expression()
    if (expr != null) {
        scan.filterExpression = expr
        scan.expressionAttributeNames = nameMap
        scan.expressionAttributeValues = valueMap
    }
}

/**
 * Build a filter from a query.
 */
public fun Query.buildFilter(builder: ExpressionBuilder.() -> Unit) {
    val expressionBuilder = ExpressionBuilder()

    expressionBuilder.builder()

    val fullFilter = expressionBuilder.expression()
    if (fullFilter != null) {
        filter(fullFilter, expressionBuilder.nameMap, expressionBuilder.valueMap)
    }
}