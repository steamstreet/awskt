package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.DeleteRequest
import com.steamstreet.awskt.dynamodb.Item
import com.steamstreet.awskt.dynamodb.PutRequest
import com.steamstreet.awskt.dynamodb.WriteRequest
import com.steamstreet.awskt.dynamodb.batchWriteAll

/**
 * Render a list of `pk to sk` pairs into the key items a batch request needs.
 *
 * The pairs are validated against the table's own key schema *before* anything is sent, because a
 * partial or over-specified key is rejected by DynamoDB for the **whole request** with a
 * `ValidationException` that names neither the offending pair nor the attribute. Catching it here
 * means the caller learns which key is wrong and why.
 *
 * Duplicates are collapsed: DynamoDB rejects a request that asks for the same key twice, and
 * `listOf(a, a)` is a reasonable thing for a caller to build out of a list of ids.
 *
 * @param operation the caller's name, used in error messages ("keys()", "batchDelete", ...)
 * @throws IllegalArgumentException if a pair omits the sort key of a composite-key table, or
 *   supplies one for a table that has no sort key
 */
internal fun Table.batchKeyItems(
    keys: List<Pair<Any, Any?>>,
    operation: String
): List<Item> {
    val pkColumn = partitionKey ?: error("Table $tableName has no partition key defined")
    val skColumn = sortKey

    return keys.map { (pk, sk) ->
        if (skColumn == null) {
            require(sk == null) {
                "$operation on table '$tableName' was given the sort key value '$sk' for partition " +
                    "key '$pk', but the table has no sort key. Pass '$pk to null' instead."
            }
        } else {
            requireNotNull(sk) {
                "$operation on table '$tableName' was given a null sort key for partition key " +
                    "'$pk'. Sort key '${skColumn.name}' is part of the primary key, so every pair " +
                    "must supply it - a partial key identifies no item, and DynamoDB rejects the " +
                    "entire batch when one is sent."
            }
        }

        buildMap {
            @Suppress("UNCHECKED_CAST")
            put(pkColumn.name, (pkColumn as Column<Any?>).toAttributeValue(pk))

            if (skColumn != null) {
                @Suppress("UNCHECKED_CAST")
                put(skColumn.name, (skColumn as Column<Any?>).toAttributeValue(sk))
            }
        }
    }.distinct()
}

/**
 * Write many items in one go, building each one with the same block the single-item [insert] uses.
 *
 * ### These are puts, not SQL INSERTs
 *
 * Like [insert], every element renders to a `PutItem`, which **replaces any existing item with the
 * same primary key wholesale** - attributes on the old item that this statement does not set are
 * gone, and no error is raised.
 *
 * ### Conditions are not available here
 *
 * DynamoDB's `BatchWriteItem` carries no condition expressions at all, so
 * [InsertStatement.condition] and [InsertStatement.ifNotExists] cannot be honoured. Calling either
 * inside [block] fails fast with [IllegalArgumentException] rather than silently writing
 * unconditionally; use [insert] per item, or a transaction, when the write must be conditional.
 *
 * ### Reliability
 *
 * The writes are chunked to DynamoDB's limit of 25 per request and any `UnprocessedItems` are
 * resubmitted with backoff. If they still do not land, the call throws
 * `BatchWriteIncompleteException`, which **names every write that did not land** so the remainder
 * can be resubmitted; writes not listed there did land, since a batch is not a transaction.
 *
 * Duplicate primary keys are *not* collapsed here - unlike a key list, two elements writing the
 * same key may carry different attributes, and dropping one would be a silent choice. DynamoDB
 * rejects a batch whose 25-item chunk contains the same key twice, so de-duplicate upstream when
 * the input may repeat a key.
 *
 * Example:
 * ```
 * Users.batchInsert(database, newUsers) { statement, user ->
 *     statement[id] = user.id
 *     statement[name] = user.name
 * }
 * ```
 *
 * @param items the elements to write; an empty sequence is a no-op and issues no request
 * @param concurrency how many 25-item chunks may be in flight at once. The default of 1 keeps them
 *   strictly sequential; raising it consumes write capacity proportionally faster.
 * @return the items as written, in input order - an echo of what was sent, the same as [insert]
 *   returns for one item. DynamoDB reports nothing per item in a batch write, so there is nothing
 *   here that was read back from the table.
 * @throws IllegalArgumentException if an element omits the partition key, or sets a condition
 */
public suspend fun <T : Table, E> T.batchInsert(
    database: Database,
    items: Iterable<E>,
    concurrency: Int = 1,
    block: T.(InsertStatement, E) -> Unit
): List<ResultRow> {
    require(concurrency >= 1) { "concurrency must be at least 1, was $concurrency" }

    // build() is the single-insert validation: it rejects a missing partition key with the same
    // message insert() produces, so the two paths cannot drift apart.
    val built = items.map { element ->
        val statement = InsertStatement(this, database)
        block(statement, element)
        val put = statement.build()
        require(put.conditionExpression == null) {
            "batchInsert on table '$tableName' cannot apply a condition: DynamoDB's BatchWriteItem " +
                "supports neither condition expressions nor ifNotExists(), so the write would " +
                "silently happen unconditionally. Use insert() for each item, or a transaction, " +
                "when the condition has to hold."
        }
        put.item
    }
    if (built.isEmpty()) return emptyList()

    database.client.batchWriteAll(
        tableName = database.resolveTableName(this),
        writes = built.map { WriteRequest(putRequest = PutRequest(it)) },
        concurrency = concurrency,
    )

    return built.map { ResultRow(this, it) }
}

/**
 * Delete many items by their full primary keys.
 *
 * The keys are validated and de-duplicated exactly as for a `keys(...)` read: a pair must supply
 * the sort key of a composite-key table and must not supply one for a table without a sort key,
 * and a key repeated in the input is sent once.
 *
 * Deletes are chunked to DynamoDB's limit of 25 per request and any `UnprocessedItems` are
 * resubmitted with backoff; if they still do not land the call throws
 * `BatchWriteIncompleteException` naming every delete that did not land.
 *
 * Nothing is returned: `BatchWriteItem` reports no per-item outcome, so - unlike [delete] - there
 * is no way to tell which of these keys actually held an item. Deleting a key that holds nothing is
 * a success, as it is for a single delete. Conditions are not supported here for the same reason as
 * in [batchInsert].
 *
 * @param keys partition key / sort key pairs; the sort key is `null` for a table without one
 * @param concurrency how many 25-item chunks may be in flight at once; see [batchInsert]
 * @throws IllegalArgumentException if a pair does not match the table's key schema
 */
public suspend fun Table.batchDelete(
    database: Database,
    keys: List<Pair<Any, Any?>>,
    concurrency: Int = 1
) {
    require(concurrency >= 1) { "concurrency must be at least 1, was $concurrency" }

    val keyItems = batchKeyItems(keys, "batchDelete")
    if (keyItems.isEmpty()) return

    database.client.batchWriteAll(
        tableName = database.resolveTableName(this),
        writes = keyItems.map { WriteRequest(deleteRequest = DeleteRequest(it)) },
        concurrency = concurrency,
    )
}
