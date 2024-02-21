package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeAction
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem
import aws.sdk.kotlin.services.dynamodb.model.TransactWriteItemsRequest
import kotlinx.coroutines.runBlocking
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
    override suspend fun commit() {
        if (items.isNotEmpty()) {
            mapper.dynamo.transactWriteItems(TransactWriteItemsRequest { this.transactItems = items })
        }
    }

    override suspend fun put(pk: String, sk: String?, attributes: Map<String, AttributeValue>): Item {
        items.add(TransactWriteItem {
            put {
                tableName = mapper.table
                item = attributes + mapper.keyMap(pk, sk)
            }
        })
        return Item(mapper, attributes)
    }

    /**
     * Add a condition check to the transaction.
     */
    public fun condition(
        pk: String, sk: String?, expression: String, expressionNames: Map<String, String>,
        expressionValues: Map<String, AttributeValue>
    ) {
        items.add(TransactWriteItem {
            conditionCheck {
                tableName = mapper.table
                key = mapper.keyMap(pk.attributeValue(), sk?.attributeValue())

                conditionExpression = expression
                if (expressionNames.isNotEmpty()) {
                    expressionAttributeNames = expressionNames
                }
                if (expressionValues.isNotEmpty()) {
                    expressionAttributeValues = expressionValues
                }
            }
        })
    }

    private fun buildDelete(pk: String, sk: String?): TransactWriteItem {
        return TransactWriteItem {
            delete  {
                tableName = mapper.table
                key = mapper.keyMap(pk, sk)
            }
        }
    }

    private fun buildUpdate(entity: MutableItem): TransactWriteItem {
        return TransactWriteItem {
            update {
                tableName = mapper.table

                this.key = mapper.keyMap(entity.attributes[mapper.pkName]!!, mapper.skName?.let {
                    entity.attributes[it]
                })

                updateExpression = entity.buildUpdateExpression()
                entity.conditionExpression?.let {
                    this.conditionExpression = it
                }
                if (entity.attributeNames.isNotEmpty()) {
                    this.expressionAttributeNames = entity.attributeNames
                }
                if (entity.attributeValues.isNotEmpty()) {
                    this.expressionAttributeValues = entity.attributeValues
                }
            }
        }
    }

    private fun buildPut(entity: MutableItem): TransactWriteItem {
        return TransactWriteItem {
            put {
                tableName = mapper.table
                item = (entity.updates.filter {
                    it.value.action == AttributeAction.Put
                }.mapValues {
                    it.value.value
                } + mapper.keyMap(entity.attributes[mapper.pkName]!!, mapper.skName?.let {
                    entity.attributes[it]
                })).filterNullValues()

                if (entity.doNotOverwrite) {
                    conditionExpression = "attribute_not_exists(#pk)"
                    expressionAttributeNames = mapOf("#pk" to mapper.pkName)
                }
            }
        }
    }

    override suspend fun put(pk: AttributeValue, sk: AttributeValue?, block: MutableItem.() -> Unit): Item {
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

    override suspend fun delete(pk: String, sk: String?, block: MutableItem.() -> Unit) {
        items.add(buildDelete(pk, sk))
    }

    override fun close() {
        runBlocking {
            commit()
        }
    }
}