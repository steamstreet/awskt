package com.steamstreet.dynamokt


/**
 * An object that can contain dynamo item data. Model classes will implement this
 * interface to indicate that they are backed by a DynamoKt item.
 */
public interface ItemContainer {
    /**
     * The dynamo item
     */
    public val entity: Item
}

/**
 * An item container that can be updated.
 */
public interface MutableItemContainer<T : ItemContainer> : ItemContainer {
    public suspend fun update(updates: T.(MutableItem) -> Unit): Item {
        return entity.update {
            val mutable = mutable(this)
            mutable.updates(this)
            decorate(this)
        }
    }

    /**
     * Override to decorate the record based on updates.
     */
    public fun decorate(item: MutableItem) {}

    /**
     * Create a copy of this item for updates
     */
    public fun mutable(entity: MutableItem): T
}