package com.steamstreet.dynamokt

import com.steamstreet.awskt.dynamodb.DeleteItemRequest
import com.steamstreet.awskt.dynamodb.DeleteRequest
import com.steamstreet.awskt.dynamodb.DescribeTableRequest
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.GetItemRequest
import com.steamstreet.awskt.dynamodb.PutItemRequest
import com.steamstreet.awskt.dynamodb.PutRequest
import com.steamstreet.awskt.dynamodb.TableDescription
import com.steamstreet.awskt.dynamodb.WriteRequest
import com.steamstreet.awskt.dynamodb.batchGetAll
import com.steamstreet.awskt.dynamodb.batchWriteAll
import com.steamstreet.awskt.dynamodb.orNullIfEmpty
import com.steamstreet.exceptions.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

public class DynamoKtSession(
    public val dynamoKt: DynamoKt,
    public val dynamo: DynamoDb,
    public val table: String = dynamoKt.table,
    public val pkName: String = dynamoKt.pkName,
    public val skName: String? = dynamoKt.skName,
    private val cache: MutableMap<String, Item>? = null
) : ItemUpdater {

    /**
     * Describe the table
     */
    public suspend fun describeTable(): TableDescription {
        return dynamo.describeTable(DescribeTableRequest(table)).table ?: throw IllegalStateException()
    }

    /**
     * Get the given item, or return null if not available.
     */
    public suspend fun getOrNull(
        pk: String, sk: String?, attributes: List<String>? = null,
        consistent: Boolean = false
    ): Item? {
        // `attributesToGet` is DynamoDB's deprecated legacy parameter and our client does not carry
        // it. A projection expression is the supported replacement, and it needs the placeholder
        // indirection because an attribute name may collide with a reserved word.
        val projection = attributes?.takeIf { it.isNotEmpty() }?.let { requested ->
            requested.withIndex().associate { (index, name) -> "#p$index" to name }
        }
        return dynamo.getItem(
            GetItemRequest(
                tableName = table,
                key = keyMap(pk, sk),
                consistentRead = consistent,
                projectionExpression = projection?.keys?.joinToString(", "),
                expressionAttributeNames = projection.orNullIfEmpty(),
            ),
        ).let {
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
        // `batchGetAll` chunks to DynamoDB's documented limit of 100 and, crucially, loops
        // `UnprocessedKeys`. The code this replaces chunked to 80 and read only `Responses`, so a
        // throttled batch or a 16 MB cap silently returned *fewer items than were asked for* with no
        // error — a live data-loss bug, not a style issue.
        val names = attributes.takeIf { it.isNotEmpty() }
            ?.withIndex()?.associate { (index, name) -> "#attr$index" to name }

        return dynamo.batchGetAll(
            tableName = table,
            keys = items.map {
                buildMap {
                    put(pkName, it.first.attributeValue())
                    if (skName != null) put(skName, it.second!!.attributeValue())
                }
            },
            consistentRead = consistent,
            projectionExpression = names?.keys?.joinToString(","),
            expressionAttributeNames = names.orNullIfEmpty(),
        ).map {
            Item(this, it).also { item ->
                if (attributes.isEmpty()) cacheItem(item)
            }
        }
    }

    override suspend fun put(pk: AttributeValue, sk: AttributeValue?, block: suspend MutableItem.() -> Unit): Item {
        return MutableItem(
            this, keyMap(pk, sk)
        ).let {
            it.doNotOverwrite = true
            it.block()
            it.save()
        }
    }

    override suspend fun put(pk: String, sk: String?, attributes: Map<String, AttributeValue>): Item {
        dynamo.putItem(
            PutItemRequest(tableName = table, item = attributes + keyMap(pk, sk)),
        ).let {
            return Item(
                this, attributes + keyMap(pk, sk)
            )
        }
    }

    override suspend fun update(pk: String, sk: String?, block: suspend MutableItem.() -> Unit): Item {
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
            DeleteRequest(
                key = buildMap {
                    put(pkName, item.pk.attributeValue())
                    if (skName != null) put(skName, item.sk!!.attributeValue())
                },
            )
        }.map {
            WriteRequest(deleteRequest = it)
        }.let { items ->
            val toDelete = items.toList()
            if (toDelete.isNotEmpty()) {
                // `batchWriteAll` chunks to 25 and loops `UnprocessedItems`. The unchunked call this
                // replaces threw ValidationException above 25 items and silently under-deleted
                // whenever a batch was throttled, because it discarded the response.
                dynamo.batchWriteAll(table, toDelete)
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

    override suspend fun delete(pk: String, sk: String?, block: suspend MutableItem.() -> Unit) {
        val item = MutableItem(this, keyMap(pk, sk))
        item.block()

        dynamo.deleteItem(
            DeleteItemRequest(
                tableName = table,
                key = keyMap(pk, sk),
                conditionExpression = item.conditionExpression,
                expressionAttributeNames =
                    item.attributeNames.orNullIfEmpty().takeIf { item.conditionExpression != null },
                expressionAttributeValues =
                    item.attributeValues.orNullIfEmpty().takeIf { item.conditionExpression != null },
            ),
        )
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