package com.steamstreet.dynamokt

import software.amazon.awssdk.services.dynamodb.model.*
import java.io.Closeable

/**
 * An item updater that batches changes into a transaction. This implementation does not
 * attempt to control the size of the transaction, so too many updates could result in
 * exceptions from DynamoDB, which limits the number of updates in a transaction.
 */
public class Transaction internal constructor(private val mapper: DynamoKtSession) : ItemUpdater, Closeable {
    private val items = ArrayList<TransactWriteItem>()

    /**
     * Commit the transaction.
     */
    override fun commit() {
        if (items.isNotEmpty()) {
            mapper.dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build())
        }
    }

    override fun put(pk: String, sk: String?, attributes: Map<String, AttributeValue>): Item {
        items.add(TransactWriteItem.builder().put {
            it.tableName(mapper.table)
            it.item(attributes + mapper.keyMap(pk, sk))
        }.build())
        return Item(mapper, attributes)
    }

    /**
     * Add a condition check to the transaction.
     */
    public fun condition(
        pk: String, sk: String, expression: String, expressionNames: Map<String, String>,
        expressionValues: Map<String, AttributeValue>
    ) {
        val check = ConditionCheck.builder().apply {
            tableName(mapper.table)
            key(
                mapOf(
                    mapper.pkName to pk.attributeValue(),
                    mapper.skName to sk.attributeValue()
                )
            )

            conditionExpression(expression)
            if (expressionNames.isNotEmpty()) {
                expressionAttributeNames(expressionNames)
            }
            if (expressionValues.isNotEmpty()) {
                expressionAttributeValues(expressionValues)
            }
        }.build()
        items.add(TransactWriteItem.builder().conditionCheck(check).build())
    }

    private fun buildDelete(pk: String, sk: String?): TransactWriteItem {
        return TransactWriteItem.builder().apply {
            delete(Delete.builder().apply {
                tableName(mapper.table)
                key(mapper.keyMap(pk, sk))
            }.build())
        }.build()
    }

    private fun buildUpdate(entity: MutableItem): TransactWriteItem {
        return TransactWriteItem.builder().apply {
            update(Update.builder().apply {
                this.tableName(mapper.table)

                key(mapper.keyMap(entity.attributes[mapper.pkName]!!, mapper.skName?.let {
                    entity.attributes[it]
                }))

                this.updateExpression(entity.buildUpdateExpression())
                entity.conditionExpression?.let {
                    this.conditionExpression(it)
                }
                if (entity.attributeNames.isNotEmpty()) {
                    this.expressionAttributeNames(entity.attributeNames)
                }
                if (entity.attributeValues.isNotEmpty()) {
                    this.expressionAttributeValues(entity.attributeValues)
                }
            }.build())
        }.build()
    }

    private fun buildPut(entity: MutableItem): TransactWriteItem {
        return TransactWriteItem.builder().apply {
            put(Put.builder().apply {
                tableName(mapper.table)
                item((entity.updates.filter {
                    it.value.action() == AttributeAction.PUT
                }.mapValues {
                    it.value.value()
                } + mapOf(
                    mapper.pkName to entity.attributes[mapper.pkName],
                    mapper.skName to entity.attributes[mapper.skName]
                )).filterNullValues())

                if (entity.doNotOverwrite) {
                    conditionExpression("attribute_not_exists(#pk)")
                    expressionAttributeNames(mapOf("#pk" to mapper.pkName))
                }
            }.build())
        }.build()
    }

    override fun put(pk: AttributeValue, sk: AttributeValue?, block: MutableItem.() -> Unit): Item {
        val key = mapper.keyMap(pk, sk)
        return MutableItem(mapper, key).let {
            it.doNotOverwrite = true
            it.block()
            items.add(buildPut(it))
            // it's not feasible to return the item, so we'll return an unloaded version of the item
            Item(mapper, key, false)
        }
    }

    override fun update(pk: String, sk: String?, block: MutableItem.() -> Unit): Item {
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

    override fun delete(pk: String, sk: String?, block: MutableItem.() -> Unit) {
        items.add(buildDelete(pk, sk))
    }

    override fun close() {
        commit()
    }
}