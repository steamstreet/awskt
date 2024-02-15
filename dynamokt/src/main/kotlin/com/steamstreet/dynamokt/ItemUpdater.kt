package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue


/**
 * Interface for an instance that can make changes to entities. This provides a base class for both regular updates
 * and transactional updates
 */
public interface ItemUpdater {
    /**
     * Create a new entity where the keys are both strings. When calling put, the default
     * implementation will not allow for updating an existing item and will throw an exception
     * if the item exists. To overwrite the item, set `doNotOverwrite` to `false` and `replace` to `true`.
     */
    public suspend fun put(pk: String, sk: String?, block: MutableItem.() -> Unit = {}): Item =
        put(pk.attributeValue(), sk?.attributeValue(), block)

    /**
     * Pu an update using attribute values for the key.
     */
    public suspend fun put(pk: AttributeValue, sk: AttributeValue?, block: MutableItem.() -> Unit = {}): Item

    /**
     * Put an item with the given attributes. Always overwrites the previous item.
     */
    public suspend fun put(pk: String, sk: String?, attributes: Map<String, AttributeValue>): Item

    /**
     * Update an item. This will not replace an item, but update individual attributes based on
     * the callback.
     */
    public suspend fun update(pk: String, sk: String?, block: suspend MutableItem.() -> Unit = {}): Item

    /**
     * Update an item
     */
    public suspend fun update(entity: Item, block: MutableItem.() -> Unit = {}): Item =
        update(entity.pk, entity.sk, block)

    /**
     * Delete an item. The provided block allows the caller to set conditional expressions on the delete
     * operation for implementations that support it.
     */
    public suspend fun delete(pk: String, sk: String?, block: MutableItem.() -> Unit = {})

    /**
     * Commit changes. Not all implementations will use this, and instead will perform updates as they are submitted.
     * However, implementations that do not will implement this as a no-op.
     */
    public suspend fun commit()
}

