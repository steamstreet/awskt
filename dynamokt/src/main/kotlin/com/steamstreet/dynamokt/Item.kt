package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import com.steamstreet.exceptions.NotFoundException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val mutex = Mutex()

    /**
     * Get all the attributes of the item. This will automatically load attributes if this is a facade.
     */
    public val allAttributes: Map<String, AttributeValue>
        get() {
            return runBlocking {
                allAttributes()
            }
        }

    public suspend fun allAttributes(): Map<String, AttributeValue> {
        fetch()
        return attributes
    }

    /**
     * The partition key of the item.
     */
    public val pk: String
        get() {
            return attributes[dynamo.pkName]?.asS() ?: error("PK should never be blank")
        }

    /**
     * The sort key of the item.
     */
    public val sk: String?
        get() {
            return attributes[dynamo.skName]?.asS()
        }

    /**
     * Fetches data if it's not already loaded.
     */
    private suspend fun fetch() {
        if (!loaded) {
            mutex.withLock {
                if (!loaded) {
                    val pk = attributes[dynamo.pkName]?.asS()
                    val sk = attributes[dynamo.skName]?.asS()
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
    public open suspend fun get(name: String): AttributeValue? {
        val attribute = attributes[name]
        if (attribute == null && !loaded) {
            fetch()
        }
        return attributes[name]
    }

    /**
     * Get the string value of an attribute (null if it's missing or not a string)
     */
    public suspend fun getString(key: String): String? {
        return get(key)?.asS()
    }

    /**
     * Get the boolean value of an attribute (null if it's missing or not a string)
     */
    public suspend fun getBoolean(key: String): Boolean? {
        return get(key)?.asBool()
    }

    /**
     * Get an integer value of an attribute
     */
    public suspend fun getInt(key: String): Int? = get(key)?.asN()?.toIntOrNull()

    /**
     * Get the long value of an attribute.
     */
    public suspend fun getLong(key: String): Long? {
        return get(key)?.asN()?.toLongOrNull()
    }

    /**
     * Update the item. Provide a function that makes calls on `MutableItem` to make the actual
     * changes.
     */
    public suspend fun update(updater: ItemUpdater = dynamo, block: MutableItem.() -> Unit): Item {
        return updater.update(this, block)
    }

}