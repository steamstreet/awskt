package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import kotlinx.datetime.Instant

/**
 * Builds filter expressions.
 */
public class ExpressionBuilder {
    private var index: Int = 1
    internal val nameMap = mutableMapOf<String, String>()
    internal val nameReverseMap = mutableMapOf<String, String>()
    internal val valueMap = mutableMapOf<String, AttributeValue>()
    internal val expressions = arrayListOf<String>()

    internal fun addExpression(
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
            val existing = nameReverseMap.get(name)
            if (existing != null) return existing

            val key = "#n${index++}"
            nameMap[key] = name
            nameReverseMap[name] = key
            key
        }.joinToString(".")
    }

    private fun valueKey(value: String): String {
        val key = ":v${index++}"
        val attributeValue = value.attributeValue()
        valueMap[key] = attributeValue
        return key
    }

    public fun valueIn(attribute: String, values: List<String>) {
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

    public fun startsWith(attribute: String, prefix: String) {
        expressions += "begins_with(${getAttrName(attribute)}, ${valueKey(prefix)})"
    }

    public fun equalTo(attribute: String, value: String) {
        expressions += "${getAttrName(attribute)} = ${valueKey(value)}"
    }

    public inner class FilterAttribute(public val name: String) {
        public infix fun isOneOf(values: List<String>) {
            valueIn(name, values)
        }

        public fun exists() {
            attributeExists(name)
        }

        public fun equalTo(value: String) {
            equalTo(name, value)
        }
    }

    public fun attribute(attribute: String): FilterAttribute = FilterAttribute(attribute)
}

public fun ExpressionBuilder.apply(scan: ScanRequest.Builder) {
    if (expressions.isNotEmpty()) {
        scan.filterExpression = expressions.joinToString(" AND ")
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

    if (expressionBuilder.expressions.isEmpty()) {
        return
    }

    val fullFilter = expressionBuilder.expressions.joinToString(" AND ")

    filter(fullFilter, expressionBuilder.nameMap, expressionBuilder.valueMap)
}