package com.steamstreet.dynamokt

/**
 * A pair of old and new items from a DynamoDB stream event.
 */
public data class Items(
    val old: Item? = null,
    val new: Item?
)

/**
 * Get the old and new items from a stream event.
 */
public fun DynamoStreamEvent.oldAndNew(session: DynamoKtSession): Items = items(session)

/**
 * Get the old and new items from a stream event.
 */
public fun DynamoStreamEvent.items(session: DynamoKtSession): Items {
    return Items(oldItem(session), newItem(session))
}

/**
 * Get the list of attribute names that differ between old and new images.
 */
public fun DynamoStreamEvent.diffs(): List<String> {
    return findDifferences(this.dynamodb.old, this.dynamodb.new)
}

/**
 * Get the new item from a stream event as a DynamoKt Item.
 */
public fun DynamoStreamEvent.newItem(session: DynamoKtSession): Item? {
    return dynamodb.new?.let {
        session.facade(dynamodb.keys + it)
    }
}

/**
 * Get the old item from a stream event as a DynamoKt Item.
 */
public fun DynamoStreamEvent.oldItem(session: DynamoKtSession): Item? {
    return dynamodb.old?.let {
        session.facade(dynamodb.keys + it)
    }
}
