package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.*
import aws.sdk.kotlin.services.dynamodb.putItem
import aws.sdk.kotlin.services.dynamodb.updateItem
import com.steamstreet.exceptions.DuplicateItemException
import com.steamstreet.exceptions.NotFoundException
import kotlinx.datetime.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KProperty1

public class DuplicateDynamoItemException(pk: String, sk: String?, item: Item? = null) : DuplicateItemException(
    "${pk}${if (sk != null) ":$sk" else ""}"
)

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
     * The values that are returned when the update is called. Defaults to all new
     * values, but might be useful to return only updated values for example.
     */
    public var returnValues: ReturnValue = ReturnValue.AllNew

    /**
     * Defines whether to return the old item when a conditional check fails.
     */
    private var conditionalCheckFailReturn: ReturnValuesOnConditionCheckFailure =
        ReturnValuesOnConditionCheckFailure.None

    /**
     * Get the attribute with the given name. Attempts to use the updated value if it
     * is available.
     */
    override suspend fun get(name: String): AttributeValue? {
        val value = attributes[name]
        val update = updates[name]
        return when {
            update == null -> {
                value
            }
            update.action == AttributeAction.Delete -> {
                null
            }

            else -> {
                update.value
            }
        }
    }

    public fun update(): MutableItem {
        return this
    }

    /**
     * Adds a condition to prevent an existing item from being overwritten. Optionally
     * set a flag to return the old item if this condition fails.
     */
    public fun doNoOverwrite(returnValuesInResponse: Boolean = false) {
        this.doNotOverwrite = true
        if (returnValuesInResponse) {
            this.conditionalCheckFailReturn = ReturnValuesOnConditionCheckFailure.AllOld
        }
    }

    /**
     * Get all updates currently pending for this item.
     */
    public val allUpdates: Map<String, AttributeValueUpdate>
        get() {
            return updates
        }

    public operator fun set(key: String, value: String?) {
        set(key, value?.let { AttributeValue(value) })
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
        set(key, value?.let { AttributeValue.N(value.toString()) })
    }

    public fun setLong(key: String, value: Long?) {
        set(key, value?.let { AttributeValue.N(value.toString()) })
    }

    public fun setBoolean(key: String, value: Boolean?) {
        set(key, value?.let { AttributeValue.Bool(value) })
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
        updates[key] = AttributeValueUpdate(value,
            if (value != null) AttributeAction.Put else AttributeAction.Delete)
    }

    /**
     * Increment an attribute value
     */
    public fun increment(key: String, amount: Int = 1) {
        if (amount != 0) {
            val attr = "attr${attributeIndex.getAndIncrement()}"
            attributeNames["#$attr"] = key
            attributeValues[":$attr"] = AttributeValue.N(amount.toString())
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
        var list = existingList ?: AttributeValue.L(emptyList())

        list = AttributeValue.L(list.asL() + value)

        attributeNames["#$attr"] = key
        attributeValues[":$attr"] = list
        attributeValues[":empty_list"] = AttributeValue.L(emptyList())

        if (existingList == null) {
            updateExpressions.add(Update("SET", "#$attr = list_append(if_not_exists(#$attr, :empty_list), :$attr)"))
        }
    }

    /**
     * Add the given string to a string set attribute.
     */
    public fun addToSet(key: String, value: String) {
        val attr = "attr${attributeIndex.getAndIncrement()}"
        attributeNames["#$attr"] = key
        attributeValues[":$attr"] = value.attributeValue()
        updateExpressions.add(Update("ADD", "#$attr :$attr"))

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
        attributeValues: Map<String, AttributeValue> = emptyMap(),
        returnValuesOnFail: ReturnValuesOnConditionCheckFailure = ReturnValuesOnConditionCheckFailure.None
    ) {
        conditionExpression = expression
        this.attributeNames.putAll(attributeNames)
        this.attributeValues.putAll(attributeValues)
        this.conditionalCheckFailReturn = returnValuesOnFail
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

    public suspend fun save(): Item {
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
     * Clear the ttl.
     */
    public fun clearTTL() {
        dynamo.dynamoKt.ttlAttribute?.let {
            delete(it)
        }
    }

    /**
     * Set the TTL to the current time plus the provided offset.
     */
    public fun setTTL(duration: kotlin.time.Duration) {
        setTTL(kotlinx.datetime.Clock.System.now().plus(duration))
    }

    private suspend fun putItem(): Item {
        val attributes = (updates.filter {
            it.value.action == AttributeAction.Put
        }.mapValues {
            it.value.value
        } + buildMap {
            put(dynamo.pkName, attributes[dynamo.pkName])
            if (dynamo.skName != null) {
                put(dynamo.skName, attributes[dynamo.skName])
            }
        }).filterNullValues()

        val result = try {
            dynamo.dynamo.putItem {
                tableName = dynamo.table
                item = attributes

                // ALL_NEW is the default
                if (this@MutableItem.returnValues != ReturnValue.AllNew) {
                    returnValues = this@MutableItem.returnValues
                }

                if (doNotOverwrite) {
                    conditionExpression = "attribute_not_exists(#pk)"
                    expressionAttributeNames = mapOf("#pk" to dynamo.pkName)
                    this.returnValuesOnConditionCheckFailure = this@MutableItem.conditionalCheckFailReturn
                }
            }
        } catch (cce: ConditionalCheckFailedException) {
            throw DuplicateDynamoItemException(attributes[dynamo.pkName]?.asS().orEmpty(),
                dynamo.skName?.let { attributes[it]?.asS() })
        }

        return if (returnValues == ReturnValue.AllNew) {
            Item(dynamo, attributes)
        } else {
            Item(dynamo, result.attributes.orEmpty())
        }
    }

    private suspend fun updateItem(): Item {
        if (updates.isEmpty() && updateExpressions.isEmpty()) {
            // if there are no updates, just get a new copy of the item. This isn't super
            // efficient, but maintains the ability to use the result of this call.
            return try {
                dynamo.get(attributes[dynamo.pkName]!!.asS(), dynamo.skName?.let {attributes[it]?.asS()})
            } catch (e: NotFoundException) {
                this
            }
        }
        return Item(dynamo, dynamo.dynamo.updateItem {
            tableName = dynamo.table
            key = buildMap {
                put(dynamo.pkName, attributes[dynamo.pkName]!!)
                if (dynamo.skName != null) {
                    put(dynamo.skName, attributes[dynamo.skName]!!)

                }
            }

            updateExpression = buildUpdateExpression()
            returnValues = this@MutableItem.returnValues

            this@MutableItem.conditionExpression?.let { expr ->
                conditionExpression = expr
                this.returnValuesOnConditionCheckFailure = this@MutableItem.conditionalCheckFailReturn
            }
            if (attributeNames.isNotEmpty()) {
                expressionAttributeNames = attributeNames
            }
            if (attributeValues.isNotEmpty()) {
                expressionAttributeValues = attributeValues
            }
        }.attributes!!)
    }
}

internal fun <K, V : Any> Map<K, V?>.filterNullValues(): Map<K, V> =
    mapNotNull { it.value?.let { value -> it.key to value } }
        .toMap()