package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.*
import aws.sdk.kotlin.services.dynamodb.model.*
import com.steamstreet.exceptions.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

public class DynamoKtSession(
    public val dynamoKt: DynamoKt,
    public val dynamo: DynamoDbClient,
    public val table: String = dynamoKt.table,
    public val pkName: String = dynamoKt.pkName,
    public val skName: String? = dynamoKt.skName,
    private val cache: MutableMap<String, Item>? = null
) : ItemUpdater {
    /**
     * Get the given item, or return null if not available.
     */
    public suspend fun getOrNull(
        pk: String, sk: String?, attributes: List<String>? = null,
        consistent: Boolean = false
    ): Item? {
        return dynamo.getItem {
            tableName = table
            key = keyMap(pk, sk)
            attributes?.apply {
                attributesToGet = this
            }
            consistentRead = consistent
        }.let {
            if (it.item == null) {
                null
            } else {
                val combined = it.item!! + keyMap(pk, sk)
                Item(this, combined).also(::cacheItem)
            }
        }
    }

    /**
     * Get the item with the given partition and sort key.
     * @throws NotFoundException if the item isn't found.
     */
    public suspend fun get(
        pk: String,
        sk: String?,
        attributes: List<String>? = null,
        consistent: Boolean = false
    ): Item {
        return getOrNull(pk, sk, attributes, consistent) ?: throw NotFoundException("Unknown item $pk $sk")
    }

    public suspend fun <T> get(
        pk: String, sk: String?,
        factory: (Item) -> T
    ): T {
        return factory(get(pk, sk))
    }

    private fun cacheItem(item: Item) {
        val cacheKey = "${item.pk}_|_${item.sk}"
        cache?.put(cacheKey, item)
    }

    public suspend fun getAll(
        items: List<Pair<String, String?>>, attributes: Collection<String> = emptyList(),
        consistent: Boolean = false
    ): List<Item> {
        return items.chunked(80).flatMap { chunkedItems ->
            val request = BatchGetItemRequest {
                requestItems =
                    mapOf(
                        table to
                                KeysAndAttributes {
                                    keys = chunkedItems.map {
                                        buildMap {
                                            put(pkName, it.first.attributeValue())
                                            if (skName != null) {
                                                put(skName, it.second!!.attributeValue())
                                            }
                                        }
                                    }

                                    if (attributes.isNotEmpty()) {
                                        var index = 0
                                        val names = attributes.associateBy { "#attr${index++}" }
                                        expressionAttributeNames = names
                                        projectionExpression = names.keys.joinToString(",")
                                    }
                                    consistentRead = consistent
                                }
                    )
            }
            dynamo.batchGetItem(request).responses?.get(table)?.map {
                Item(this, it).also { item ->
                    if (attributes.isEmpty()) {
                        cacheItem(item)
                    }
                }
            } ?: emptyList()
        }
    }

    override suspend fun put(pk: AttributeValue, sk: AttributeValue?, block: MutableItem.() -> Unit): Item {
        return MutableItem(
            this, keyMap(pk, sk)
        ).let {
            it.doNotOverwrite = true
            it.block()
            it.save()
        }
    }

    override suspend fun put(pk: String, sk: String?, attributes: Map<String, AttributeValue>): Item {
        dynamo.putItem {
            tableName = table
            item = attributes + keyMap(pk, sk)
        }.let {
            return Item(
                this, attributes + keyMap(pk, sk)
            )
        }
    }

    override suspend fun update(pk: String, sk: String?, block: MutableItem.() -> Unit): Item {
        return MutableItem(this, keyMap(pk, sk)).let {
            it.block()
            it.save()
        }
    }

    public fun transaction(): Transaction {
        return Transaction(this)
    }

    public suspend fun query(pk: String, block: Query.() -> Unit = {}): QueryResult {
        return Query(this, pk).apply(block).execute()
    }

    public suspend fun queryIndex(name: String, pk: String, block: Query.() -> Unit = {}): QueryResult {
        val index = dynamoKt.indexes[name] ?: throw IllegalArgumentException("Unknown index")
        return Query(this, pk, index.name, index.pk, index.sk).apply(block).execute()
    }

    /**
     * Execute the query and delete all of the items in the result
     */
    public suspend fun queryDelete(pk: String, block: Query.() -> Unit = {}): QueryResult {
        val result = Query(this, pk).apply(block).execute()

        result.items.map { item ->
            DeleteRequest {
                key = buildMap {
                    put(pkName, item.pk.attributeValue())
                    if (skName != null) {
                        put(skName, item.sk!!.attributeValue())
                    }
                }
            }
        }.map {
            WriteRequest {
                this.deleteRequest = it
            }
        }.let { items ->
            val toDelete = items.toList()
            if (toDelete.isNotEmpty()) {
                dynamo.batchWriteItem {
                    requestItems = (mapOf(table to toDelete))
                }
            }
        }
        return result
    }

    public suspend fun scan(block: Query.() -> Unit): QueryResult {
        return Query(this, "SCAN").apply(block).executeScan()
    }

    /**
     * Execute a scan in parallel using the provided number of segments. Returns a flow
     * that a client can use to asynchronously receive events.
     */
    public fun parallelScan(segments: Int, segmentNumbers: List<Int>? = null, block: Query.() -> Unit): Flow<Item> {
        val actualSegmentNumbers = if (segmentNumbers.isNullOrEmpty()) {
            (0 until segments).toList()
        } else {
            segmentNumbers
        }
        return channelFlow {
            actualSegmentNumbers.forEach { index ->
                launch(Dispatchers.IO) {
                    scan {
                        block()
                        this.segments = segments
                        this.segment = index
                        loadAll = true
                    }.items.collect {
                        send(it)
                    }
                }
            }
        }
    }

    internal fun keyMap(pk: String, sk: String?): Map<String, AttributeValue> {
        return keyMap(pk.attributeValue(), sk?.attributeValue())
    }

    internal fun keyMap(pk: AttributeValue, sk: AttributeValue?): Map<String, AttributeValue> {
        return buildMap {
            put(pkName, pk)
            if (skName != null) {
                put(skName, sk!!)
            }
        }
    }

    override suspend fun delete(pk: String, sk: String?, block: MutableItem.() -> Unit) {
        val item = MutableItem(this, keyMap(pk, sk))
        item.block()

        dynamo.deleteItem {
            tableName = (table)
            key = (keyMap(pk, sk))

            if (item.conditionExpression != null) {
                conditionExpression = item.conditionExpression

                if (item.attributeNames.isNotEmpty()) {
                    expressionAttributeNames = item.attributeNames
                }
                if (item.attributeValues.isNotEmpty()) {
                    expressionAttributeValues = item.attributeValues
                }
            }
        }
    }

    override suspend fun commit() {
        // do nothing
    }

    /**
     * An item that can be treated like a regular entity, but isn't backed by any
     * database items. No attempts to load will occur when looking up attributes.
     */
    public fun facade(attributes: Map<String, AttributeValue>): Item {
        return Item(this, attributes)
    }

    public fun facade(
        pk: String, sk: String,
        attributes: Map<String, AttributeValue> = emptyMap()
    ): Item {
        return facade(
            attributes + keyMap(pk, sk)
        )
    }

    /**
     * An unloaded item is like a facade, but if an attribute is requested that isn't present,
     * the item will attempt to load the item from the database. This is useful for lazy items where
     * you may have useful information in the keys but don't yet want to make a trip to the database.
     *
     * The attributes must include a partition value and sort value for this to work properly.
     *
     * If you just want to wrap a set of attributes in an Item, use `facade` instead.
     */
    public fun unloaded(attributes: Map<String, AttributeValue>, failOnLoading: Boolean): Item {
        return Item(this, attributes, false, failOnLoading)
    }

    public fun unloaded(
        pk: String,
        sk: String?,
        attributes: Map<String, AttributeValue>,
        failOnLoading: Boolean
    ): Item {
        return unloaded(
            keyMap(pk, sk) + attributes, failOnLoading
        )
    }
}

public fun Map<String, AttributeValue>?.facade(session: DynamoKtSession): Item {
    return session.facade(this ?: emptyMap())
}