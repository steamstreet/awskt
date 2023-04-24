package com.steamstreet.dynamokt

import com.steamstreet.exceptions.NotFoundException
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

/**
 * Encapsulates an item in the database.
 */
public open class Item internal constructor(
    public val dynamo: DynamoKtSession,
    attrs: Map<String, AttributeValue>,
    public var loaded: Boolean = true,
    public val failOnLoading: Boolean = true
) {
    internal var attributes: Map<String, AttributeValue> = attrs

    /**
     * Get all the attributes of the item. This will automatically load attributes if this is a facade.
     */
    public val allAttributes: Map<String, AttributeValue>
        get() {
            fetch()
            return attributes
        }

    /**
     * The partition key of the item.
     */
    public val pk: String
        get() {
            return attributes[dynamo.pkName]?.s() ?: error("PK should never be blank")
        }

    /**
     * The sort key of the item.
     */
    public val sk: String?
        get() {
            return attributes[dynamo.skName]?.s()
        }

    /**
     * Fetches data if it's not already loaded.
     */
    private fun fetch() {
        if (!loaded) {
            synchronized(this) { // prevent double loading
                if (!loaded) {
                    val pk = attributes[dynamo.pkName]?.s()
                    val sk = attributes[dynamo.skName]?.s()
                    if (pk != null && sk != null) {
                        try {
                            attributes = dynamo.get(pk, sk).attributes
                        } catch (e: NotFoundException) {
                            if (failOnLoading) throw e
                        }
                    }
                    loaded = true
                }
            }
        }
    }

    /**
     * Get an attribute value with the given key. If this is an unloaded item, will attempt to
     * load if the attribute is not present.
     */
    public open operator fun get(name: String): AttributeValue? {
        val attribute = attributes[name]
        if (attribute == null && !loaded) {
            fetch()
        }
        return attributes[name]
    }

    /**
     * Get the string value of an attribute (null if it's missing or not a string)
     */
    public fun getString(key: String): String? {
        return get(key)?.s()
    }

    /**
     * Get the boolean value of an attribute (null if it's missing or not a string)
     */
    public fun getBoolean(key: String): Boolean? {
        return get(key)?.bool()
    }

    /**
     * Get an integer value of an attribute
     */
    public fun getInt(key: String): Int? = get(key)?.n()?.toIntOrNull()

    /**
     * Get the long value of an attribute.
     */
    public fun getLong(key: String): Long? {
        return get(key)?.n()?.toLongOrNull()
    }

    /**
     * Update the item. Provide a function that makes calls on `MutableItem` to make the actual
     * changes.
     */
    public fun update(updater: ItemUpdater = dynamo, block: MutableItem.() -> Unit): Item {
        return updater.update(this, block)
    }

}