package com.steamstreet.dynamokt

import com.steamstreet.exceptions.NotFoundException
import com.steamstreet.mutableLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder
import software.amazon.awssdk.services.dynamodb.model.*

public var dynamoKtClientBuilder: DynamoDbClientBuilder by mutableLazy {
    DynamoDbClient.builder()
}

/**
 * The global object that stores the Dynamo configuration.
 */
public class DynamoKt(
    public val table: String,
    public val pkName: String = "pk",
    public val skName: String = "sk",
    public val builder: DynamoDbClientBuilder = dynamoKtClientBuilder,
    public val defaultCredentials: AwsCredentialsProvider? = null
) {
    internal val indexes = hashMapOf<String, DynamoKtIndex>()

    private val defaultClient: DynamoDbClient by lazy {
        if (defaultCredentials != null) {
            builder.credentialsProvider(defaultCredentials)
        }
        builder.build()
    }

    /**
     * Create an AWS session, which is just operations linked with specific credentials.
     */
    public fun session(awsCredentialsProvider: AwsCredentialsProvider? = null): DynamoKtSession {
        val client = if (awsCredentialsProvider == null) {
            defaultClient
        } else {
            builder.credentialsProvider(awsCredentialsProvider).build()
        }
        return DynamoKtSession(
            this,
            client,
            table, pkName, skName
        )
    }

    public fun registerIndex(name: String, pk: String, sk: String?): DynamoKtIndex {
        return DynamoKtIndex(name, pk, sk).apply {
            indexes[name] = this
        }
    }
}

public class DynamoKtIndex(
    public val name: String,
    public val pk: String,
    public val sk: String?
)

public class DynamoKtSession(
    public val dynamoKt: DynamoKt,
    public val dynamo: DynamoDbClient,
    public val table: String,
    public val pkName: String,
    public val skName: String,
    private val cache: MutableMap<String, Item>? = null
) : ItemUpdater {

    public fun getOrNull(pk: String, sk: String, attributes: List<String>? = null): Item? {
        return dynamo.getItem {
            it.tableName(table)
            it.key(mapOf(pkName to pk.attributeValue(), skName to sk.attributeValue()))
            attributes?.apply {
                it.attributesToGet(this)
            }
        }.let {
            if (!it.hasItem()) {
                null
            } else {
                val combined = it.item() + mapOf(pkName to pk.attributeValue(), skName to sk.attributeValue())
                Item(this, combined).also(::cacheItem)
            }
        }
    }

    public fun get(pk: String, sk: String, attributes: List<String>? = null): Item {
        return getOrNull(pk, sk, attributes) ?: throw NotFoundException("Unknown item $pk $sk")
    }

    private fun cacheItem(item: Item) {
        val cacheKey = "${item.pk}_|_${item.sk}"
        cache?.put(cacheKey, item)
    }

    public fun getAll(items: List<Pair<String, String>>, attributes: Collection<String> = emptyList()): List<Item> {
        return items.chunked(80).flatMap { chunkedItems ->
            val request = BatchGetItemRequest.builder().apply {
                requestItems(
                    mapOf(
                        table to
                                KeysAndAttributes.builder().apply {
                                    keys(chunkedItems.map {
                                        mapOf(pkName to it.first.attributeValue(), skName to it.second.attributeValue())
                                    })

                                    if (attributes.isNotEmpty()) {
                                        var index = 0
                                        val names = attributes.associateBy { "#attr${index++}" }
                                        expressionAttributeNames(names)
                                        projectionExpression(names.keys.joinToString(","))
                                    }
                                }.build()
                    )
                )
            }.build()
            dynamo.batchGetItem(request).responses().get(table)?.map {
                Item(this, it).also { item ->
                    if (attributes.isEmpty()) {
                        cacheItem(item)
                    }
                }
            } ?: emptyList()
        }
    }

    override fun put(pk: AttributeValue, sk: AttributeValue, block: MutableItem.() -> Unit): Item {
        return MutableItem(
            this, mapOf(
                pkName to pk,
                skName to sk
            )
        ).let {
            it.doNotOverwrite = true
            it.block()
            it.save()
        }
    }

    override fun put(pk: String, sk: String, attributes: Map<String, AttributeValue>): Item {
        dynamo.putItem {
            it.tableName(table)
            it.item(attributes + (pkName to pk.attributeValue()) + (skName to sk.attributeValue()))
        }.let {
            return Item(
                this, attributes +
                        (pkName to pk.attributeValue()) +
                        (skName to sk.attributeValue())
            )
        }
    }

    override fun update(pk: String, sk: String, block: MutableItem.() -> Unit): Item {
        return MutableItem(
            this, mapOf(
                pkName to pk.attributeValue(),
                skName to sk.attributeValue()
            )
        ).let {
            it.block()
            it.save()
        }
    }

    public fun transaction(): Transaction {
        return Transaction(this)
    }

    public fun query(pk: String, block: Query.() -> Unit = {}): QueryResult {
        return Query(this, pk).apply(block).execute()
    }

    public fun queryIndex(name: String, pk: String, block: Query.() -> Unit = {}): QueryResult {
        val index = dynamoKt.indexes[name] ?: throw IllegalArgumentException("Unknown index")
        return Query(this, pk, index.name, index.pk, index.sk).apply(block).execute()
    }

    /**
     * Execute the query and delete all of the items in the result
     */
    public fun queryDelete(pk: String, block: Query.() -> Unit = {}): QueryResult {
        val result = Query(this, pk).apply(block).execute()

        result.items.map { item ->
            DeleteRequest.builder().key(
                mapOf(
                    pkName to item.pk.attributeValue(),
                skName to item.sk!!.attributeValue()
            )).build()
        }.map {
            WriteRequest.builder().deleteRequest(it).build()
        }.let { items ->
            if (items.isNotEmpty()) {
                dynamo.batchWriteItem {
                    it.requestItems(mapOf(table to items))
                }
            }
        }
        return result
    }

    public fun scan(block: Query.() -> Unit): QueryResult {
        return Query(this, "SCAN").apply(block).executeScan()
    }

    /**
     * Execute a scan in parallel using the provided number of segments. Returns a flow
     * that a client can use to asynchronously receive events.
     */
    public fun parallelScan(segments: Int, block: Query.() -> Unit): Flow<Item> {
        return channelFlow {
            repeat(segments) { index ->
                launch(Dispatchers.IO) {
                    scan {
                        block()
                        this.segments = segments
                        this.segment = index
                        loadAll = true
                    }.items.forEach {
                        send(it)
                    }
                }
            }
        }
    }

    override fun delete(pk: String, sk: String, block: MutableItem.() -> Unit) {
        val item = MutableItem(this, mapOf(pkName to pk.attributeValue(), skName to sk.attributeValue()))
        item.block()

        dynamo.deleteItem {
            it.tableName(table)
            it.key(
                mapOf(
                    pkName to pk.attributeValue(),
                    skName to sk.attributeValue()
                )
            )

            if (item.conditionExpression != null) {
                it.conditionExpression(item.conditionExpression)

                if (item.attributeNames.isNotEmpty()) {
                    it.expressionAttributeNames(item.attributeNames)
                }
                if (item.attributeValues.isNotEmpty()) {
                    it.expressionAttributeValues(item.attributeValues)
                }
            }
        }
    }

    override fun commit() {
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
            attributes +
                    mapOf(
                        pkName to pk.attributeValue(),
                        skName to sk.attributeValue()
                    )
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

    public fun unloaded(pk: String, sk: String, attributes: Map<String, AttributeValue>, failOnLoading: Boolean): Item {
        return unloaded(
            mapOf(
                pkName to pk.attributeValue(),
                skName to sk.attributeValue()
            ) + attributes, failOnLoading
        )
    }
}

public fun Map<String, AttributeValue>?.facade(session: DynamoKtSession): Item {
    return session.facade(this ?: emptyMap())
}