package com.steamstreet.dynamokt

import com.steamstreet.exceptions.DuplicateItemException
import com.steamstreet.exceptions.NotFoundException
import kotlinx.datetime.Instant
import software.amazon.awssdk.services.dynamodb.model.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KProperty1

/**
 * An item that can be updated. Never created directly, this is used when calling update on an item
 * or via the session.
 */
public class MutableItem internal constructor(dynamo: DynamoKtSession, attributes: Map<String, AttributeValue>) :
    Item(dynamo, attributes) {
    public class Update(public val type: String, public val expression: String)

    internal val updates = HashMap<String, AttributeValueUpdate>()
    internal val attributeNames = HashMap<String, String>()
    internal val attributeValues = HashMap<String, AttributeValue>()
    internal var updateExpressions = ArrayList<Update>()

    private val attributeIndex = AtomicInteger(1)

    /**
     * A condition expression that is evaluated when updating. If the condition fails,
     * the update will not occur.
     */
    internal var conditionExpression: String? = null

    /**
     * Set to true to prevent this from overwriting any attributes from an existing item.
     */
    public var doNotOverwrite: Boolean = false

    /**
     * Set to true to completely replace an item if it already exists (or adds a new one if it doesn't)
     */
    public var replace: Boolean = false

    /**
     * The values that are returned when the update is called. Defaults to all of the new
     * values, but might be useful to return only updated values for example.
     */
    public var returnValues: ReturnValue = ReturnValue.ALL_NEW

    /**
     * Get the attribute with the given name. Attempts to use the updated value if it
     * is available.
     */
    override fun get(name: String): AttributeValue? {
        val value = attributes[name]
        val update = updates[name]
        return when {
            update == null -> {
                value
            }
            update.action() == AttributeAction.DELETE -> {
                null
            }

            else -> {
                update.value()
            }
        }
    }

    public fun update(): MutableItem {
        return this
    }

    /**
     * Get all updates currently pending for this item.
     */
    public val allUpdates: Map<String, AttributeValueUpdate>
        get() {
            return updates
        }

    public operator fun set(key: String, value: String?) {
        set(key, value?.let { AttributeValue.builder().s(value)?.build() })
    }

    public operator fun set(key: String, value: Instant?) {
        set(key, value?.toString())
    }

    public fun <V : ItemContainer, T> set(property: KProperty1<V, T>, value: T) {
        if (value is String?) {
            set(property.name, value)
        }
    }

    public operator fun set(key: String, value: Int?) {
        set(key, value?.let { AttributeValue.builder().n(value.toString()).build() })
    }

    public fun setLong(key: String, value: Long?) {
        set(key, value?.let { AttributeValue.builder().n(value.toString()).build() })
    }

    public fun setBoolean(key: String, value: Boolean?) {
        set(key, value?.let { AttributeValue.builder().bool(value)?.build() })
    }

    /**
     * Set the value to an enumerated value, which uses the 'name' field as its string value.
     */
    public operator fun set(key: String, value: Enum<*>?) {
        set(key, value?.name)
    }

    public operator fun set(key: String, value: AttributeValue?) {
        val newKey = key.split(".").map { keyElement ->
            "#attr${attributeIndex.getAndIncrement()}".also {
                attributeNames[it] = keyElement
            }
        }.joinToString(".")

        if (value != null) {
            val attrValue = "attr${attributeIndex.getAndIncrement()}"
            updateExpressions.add(Update("SET", "$newKey = :$attrValue"))
            attributeValues[":$attrValue"] = value
        } else {
            updateExpressions.add(Update("REMOVE", newKey))
        }
        updates[key] = AttributeValueUpdate.builder().value(value)
            .action(if (value != null) AttributeAction.PUT else AttributeAction.DELETE).build()
    }

    /**
     * Increment an attribute value
     */
    public fun increment(key: String, amount: Int = 1) {
        if (amount != 0) {
            val attr = "attr${attributeIndex.getAndIncrement()}"
            attributeNames["#$attr"] = key
            attributeValues[":$attr"] = AttributeValue.builder().n(amount.toString()).build()
            updateExpressions.add(Update("ADD", "#$attr :$attr"))
        }
    }

    /**
     * Add an item to the list, properly handling the case of the list doesn't exist.
     */
    public fun addToList(key: String, value: AttributeValue) {
        // look for a key with the same value. If the list already exists, we need to add it to that one
        // instead of adding a new expression.
        val existingAttr = attributeNames.entries.find { it.value == key }?.key?.drop(1)
        val attr = existingAttr ?: "attr${attributeIndex.getAndIncrement()}"
        val existingList = attributeValues[":$attr"]
        val list = existingList ?: AttributeValue.builder().l(emptyList()).build()

        list.l().add(value)

        attributeNames["#$attr"] = key
        attributeValues[":$attr"] = list
        attributeValues[":empty_list"] = AttributeValue.builder().l(emptyList()).build()

        if (existingList == null) {
            updateExpressions.add(Update("SET", "#$attr = list_append(if_not_exists(#$attr, :empty_list), :$attr)"))
        }
    }

    /**
     * Remove an item from a list
     */
    public fun removeFromList(key: String, index: Int) {
        val attr = "attr${attributeIndex.getAndIncrement()}"
        attributeNames["#$attr"] = key
        updateExpressions.add(Update("REMOVE", """#$attr[$index]"""))
    }

    /**
     * Attach a condition to this update.
     */
    public fun condition(
        expression: String, attributeNames: Map<String, String> = emptyMap(),
        attributeValues: Map<String, AttributeValue> = emptyMap()
    ) {
        conditionExpression = expression
        this.attributeNames.putAll(attributeNames)
        this.attributeValues.putAll(attributeValues)
    }

    /**
     * Add a condition that checks that an attribute has the given value.
     */
    public fun conditionAttributeEquals(name: String, value: AttributeValue) {
        val attr = "attr${attributeIndex.getAndIncrement()}"
        condition(
            "#$attr = :$attr",
            mapOf("#$attr" to name),
            mapOf(":$attr" to value)
        )
    }

    /**
     * Require that an attribute exists as a condition of the update.
     */
    public fun requireAttributeExists(name: String) {
        val attr = "attr${attributeIndex.getAndIncrement()}"
        condition(
            "attribute_exists(#$attr)",
            mapOf("#$attr" to name)
        )
    }

    /**
     * Require that an attribute exists as a condition of the update.
     */
    public fun requireAttributeNotExists(name: String) {
        val attr = "attr${attributeIndex.getAndIncrement()}"
        condition(
            "attribute_not_exists(#$attr)",
            mapOf("#$attr" to name)
        )
    }

    /**
     * Set the values for the global secondary index on this item. As a convention, GSI attributes start with
     * _gsi, followed by the index, and pk or sk. For example _gsi1pk, _gsi2pk. Values in GSI keys
     * should not generally be used for other purposes.
     */
    public fun setGSI(index: Int, pk: String, sk: String) {
        set("_gsi${index}pk", pk)
        set("_gsi${index}sk", sk)
    }

    public fun delete(key: String) {
        val attr = "attr${attributeIndex.getAndIncrement()}"
        attributeNames["#$attr"] = key
        updateExpressions.add(Update("REMOVE", "#$attr"))
    }

    /**
     * Build the update expression from the collection of Update instances.
     */
    internal fun buildUpdateExpression(): String {
        val byType = updateExpressions.groupBy { it.type }

        return byType.entries.joinToString(" ") {
            "${it.key} ${it.value.joinToString(", ") { it.expression }}"
        }
    }

    /**
     * Adds a condition to ensure that the item already exists.
     */
    public fun mustExist() {
        this.condition("attribute_exists(#pk)", mapOf("#pk" to dynamo.pkName))
    }

    public fun save(): Item {
        return if (replace || doNotOverwrite) {
            putItem()
        } else {
            updateItem()
        }
    }

    /**
     * Set the ttl of the item using the attribute provided in the main DynamoKt class.
     */
    public fun setTTL(instant: Instant) {
        dynamo.dynamoKt.ttlAttribute?.let {
            setLong(it, instant.epochSeconds)
        }
    }

    /**
     * Set the TTL to the current time plus the provided offset.
     */
    public fun setTTL(duration: kotlin.time.Duration) {
        setTTL(kotlinx.datetime.Clock.System.now().plus(duration))
    }

    private fun putItem(): Item {
        val attributes = (updates.filter {
            it.value.action() == AttributeAction.PUT
        }.mapValues {
            it.value.value()
        } + buildMap {
            put(dynamo.pkName, attributes[dynamo.pkName])
            if (dynamo.skName != null) {
                put(dynamo.skName, attributes[dynamo.skName])
            }
        }).filterNullValues()

        val result = try {
            dynamo.dynamo.putItem {
                it.tableName(dynamo.table)
                it.item(attributes)

                // ALL_NEW is the default
                if (returnValues != ReturnValue.ALL_NEW) {
                    it.returnValues(returnValues)
                }

                if (doNotOverwrite) {
                    it.conditionExpression("attribute_not_exists(#pk)")
                    it.expressionAttributeNames(mapOf("#pk" to dynamo.pkName))
                }
            }
        } catch (cce: ConditionalCheckFailedException) {
            throw DuplicateItemException("${attributes[dynamo.pkName]?.s()}:${attributes[dynamo.skName]?.s()}")
        }

        return if (returnValues == ReturnValue.ALL_NEW) {
            Item(dynamo, attributes)
        } else {
            Item(dynamo, result.attributes())
        }
    }

    private fun updateItem(): Item {
        if (updates.isEmpty() && updateExpressions.isEmpty()) {
            // if there are no updates, just get a new copy of the item. This isn't super
            // efficient, but maintains the ability to use the result of this call.
            return try {
                dynamo.get(attributes[dynamo.pkName]!!.s(), attributes[dynamo.skName]?.s())
            } catch (e: NotFoundException) {
                this
            }
        }
        return Item(dynamo, dynamo.dynamo.updateItem {
            it.tableName(dynamo.table)
            it.key(
                mapOf(
                    dynamo.pkName to attributes[dynamo.pkName],
                    dynamo.skName to attributes[dynamo.skName]
                )
            )

            it.updateExpression(buildUpdateExpression())
            it.returnValues(returnValues)

            conditionExpression?.let { expr ->
                it.conditionExpression(expr)
            }
            if (attributeNames.isNotEmpty()) {
                it.expressionAttributeNames(attributeNames)
            }
            if (attributeValues.isNotEmpty()) {
                it.expressionAttributeValues(attributeValues)
            }
        }.attributes())
    }
}

internal fun <K, V : Any> Map<K, V?>.filterNullValues(): Map<K, V> =
    mapNotNull { it.value?.let { value -> it.key to value } }
        .toMap()
