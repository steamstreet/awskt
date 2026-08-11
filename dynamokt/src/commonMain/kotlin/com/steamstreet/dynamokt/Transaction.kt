package com.steamstreet.dynamokt

import com.steamstreet.awskt.dynamodb.ConditionCheck
import com.steamstreet.awskt.dynamodb.TransactDelete
import com.steamstreet.awskt.dynamodb.TransactPut
import com.steamstreet.awskt.dynamodb.TransactUpdate
import com.steamstreet.awskt.dynamodb.TransactWriteItem
import com.steamstreet.awskt.dynamodb.TransactWriteItemsRequest
import com.steamstreet.awskt.dynamodb.orNullIfEmpty
import kotlinx.coroutines.runBlocking

/**
 * An item updater that batches changes into a transaction. This implementation does not
 * attempt to control the size of the transaction, so too many updates could result in
 * exceptions from DynamoDB, which limits the number of updates in a transaction.
 */
public class Transaction internal constructor(private val mapper: DynamoKtSession) : ItemUpdater, AutoCloseable {
    private val items = ArrayList<TransactWriteItem>()

    /**
     * Commit the transaction.
     */
    override suspend fun commit() {
        if (items.isNotEmpty()) {
            mapper.dynamo.transactWriteItems(TransactWriteItemsRequest(items))
        }
    }

    override suspend fun put(pk: String, sk: String?, attributes: Map<String, AttributeValue>): Item {
        items.add(
            TransactWriteItem(
                put = TransactPut(
                    tableName = mapper.table,
                    item = attributes + mapper.keyMap(pk, sk),
                ),
            ),
        )
        return Item(mapper, attributes)
    }

    /**
     * Add a condition check to the transaction.
     */
    public fun condition(
        pk: String, sk: String?, expression: String, expressionNames: Map<String, String>,
        expressionValues: Map<String, AttributeValue>
    ) {
        items.add(
            TransactWriteItem(
                conditionCheck = ConditionCheck(
                    tableName = mapper.table,
                    key = mapper.keyMap(pk.attributeValue(), sk?.attributeValue()),
                    conditionExpression = expression,
                    // Empty maps are normalized to null, never sent as `{}` — DynamoDB rejects that.
                    expressionAttributeNames = expressionNames.orNullIfEmpty(),
                    expressionAttributeValues = expressionValues.orNullIfEmpty(),
                ),
            ),
        )
    }

    private fun buildDelete(pk: String, sk: String?): TransactWriteItem {
        return TransactWriteItem(
            delete = TransactDelete(tableName = mapper.table, key = mapper.keyMap(pk, sk)),
        )
    }

    private fun buildUpdate(entity: MutableItem): TransactWriteItem {
        return TransactWriteItem(
            update = TransactUpdate(
                tableName = mapper.table,
                key = mapper.keyMap(
                    entity.attributes[mapper.pkName]!!,
                    mapper.skName?.let { entity.attributes[it] },
                ),
                updateExpression = entity.buildUpdateExpression(),
                conditionExpression = entity.conditionExpression,
                expressionAttributeNames = entity.attributeNames.orNullIfEmpty(),
                expressionAttributeValues = entity.attributeValues.orNullIfEmpty(),
            ),
        )
    }

    private fun buildPut(entity: MutableItem): TransactWriteItem {
        return TransactWriteItem(
            put = TransactPut(
                tableName = mapper.table,
                item = (
                    entity.updates.filter { it.value.action == AttributeAction.Put }
                        .mapValues { it.value.value } +
                        mapper.keyMap(
                            entity.attributes[mapper.pkName]!!,
                            mapper.skName?.let { entity.attributes[it] },
                        )
                    ).filterNullValues(),
                conditionExpression = "attribute_not_exists(#pk)".takeIf { entity.doNotOverwrite },
                expressionAttributeNames =
                    mapOf("#pk" to mapper.pkName).takeIf { entity.doNotOverwrite },
            ),
        )
    }

    override suspend fun put(pk: AttributeValue, sk: AttributeValue?, block: suspend MutableItem.() -> Unit): Item {
        val key = mapper.keyMap(pk, sk)
        return MutableItem(mapper, key).let {
            it.doNotOverwrite = true
            it.block()
            items.add(buildPut(it))
            // it's not feasible to return the item, so we'll return an unloaded version of the item
            Item(mapper, key, false)
        }
    }

    override suspend fun update(pk: String, sk: String?, block: suspend MutableItem.() -> Unit): Item {
        val key = mapper.keyMap(pk, sk)
        return MutableItem(mapper, key).let {
            it.block()

            items.add(
                if (it.replace || it.doNotOverwrite) {
                    buildPut(it)
                } else {
                    buildUpdate(it)
                }
            )

            // it's not feasible to return the item, so we'll return an unloaded version of the item
            Item(mapper, key, false)
        }
    }

    override suspend fun delete(pk: String, sk: String?, block: suspend MutableItem.() -> Unit) {
        items.add(buildDelete(pk, sk))
    }

    override fun close() {
        runBlocking {
            commit()
        }
    }
}