package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryResponse
import aws.sdk.kotlin.services.dynamodb.paginators.items
import aws.sdk.kotlin.services.dynamodb.paginators.queryPaginated
import aws.sdk.kotlin.services.dynamodb.paginators.scanPaginated
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger


public class Query internal constructor(
    public val dynamo: DynamoKtSession,
    public val pk: String,
    public var indexName: String? = null,
    public var pkName: String = dynamo.pkName,
    public var skName: String? = dynamo.skName
) {
    public var forward: Boolean = true
    public var limit: Int? = null
    public var token: String? = null
    public var loadAll: Boolean = false
    public var consistent: Boolean = false

    private var projection: MutableList<String>? = null

    private var filter: String? = null
    private var attributeNames: MutableMap<String, String>? = null
    private var attributeValues: MutableMap<String, AttributeValue>? = null

    private var attributeIndex = AtomicInteger(1)

    // Segment information used for scans only.
    internal var segments: Int? = null
    internal var segment: Int? = null

    /**
     * Wraps the sort key operations, for a cleaner API.
     */
    public val sk: SortKey = SortKey()

    public fun reversed() {
        forward = false
    }

    private fun processAttributeName(name: String): String {
        return name.split(".").map { keyElement ->
            "#attr${attributeIndex.getAndIncrement()}".also {
                if (attributeNames == null) {
                    attributeNames = HashMap()
                }
                attributeNames?.put(it, keyElement)
            }
        }.joinToString(".")

    }

    /**
     * Define the attributes that should be returned.
     */
    public fun attributes(vararg attributeNames: String) {
        if (projection == null && attributeNames.isNotEmpty()) {
            projection = ArrayList()
        }
        val processedNames = attributeNames.map {
            processAttributeName(it)
        }
        projection?.addAll(processedNames.toList())
    }

    /**
     * Configures this query to use the GSI index.
     */
    public fun index(indexName: String, pkName: String, skName: String) {
        this.indexName = indexName
        this.pkName = pkName
        this.skName = skName
    }

    public class SortKey {
        public var expression: String? = null
        public val values: HashMap<String, AttributeValue> = HashMap()
        public fun startsWith(str: String) {
            expression = "begins_with(#sk, :sk)"
            values[":sk"] = str.attributeValue()
        }

        override fun equals(other: Any?): Boolean {
            if (other is String) {
                expression = "#sk = :sk"
                values[":sk"] = other.attributeValue()
            }
            return false
        }

        public fun isEqual(other: String) {
            expression = "#sk = :sk"
            values[":sk"] = other.attributeValue()
        }

        public fun between(range: ClosedRange<String>) {
            expression = "#sk BETWEEN :sk1 AND :sk2"
            values[":sk1"] = range.start.attributeValue()
            values[":sk2"] = range.endInclusive.attributeValue()
        }

        override fun hashCode(): Int {
            return 0
        }

        public infix fun lessThan(str: String) {
            expression = "#sk < :sk"
            values[":sk"] = str.attributeValue()
        }

        public infix fun lessThanOrEqualTo(str: String) {
            expression = "#sk <= :sk"
            values[":sk"] = str.attributeValue()
        }

        public infix fun greaterThan(str: String) {
            expression = "#sk > :sk"
            values[":sk"] = str.attributeValue()
        }

        public infix fun greaterThanOrEqualTo(str: String) {
            expression = "#sk >= :sk"
            values[":sk"] = str.attributeValue()
        }
    }

    public fun filter(
        expression: String,
        names: Map<String, String> = emptyMap(),
        values: Map<String, AttributeValue> = emptyMap()
    ) {
        this.filter = expression
        if (attributeNames == null) {
            attributeNames = HashMap()
        }
        attributeNames?.putAll(names)

        if (attributeValues == null) {
            attributeValues = HashMap()
        }

        attributeValues?.putAll(values)
    }

    private fun buildExpressionNames(): HashMap<String, String> {
        return HashMap<String, String>().also {
            if (pk != "SCAN") {
                it["#pk"] = pkName
            }
            if (sk.expression != null) {
                it["#sk"] = skName!!
            }

            if (!attributeNames.isNullOrEmpty()) {
                it.putAll(attributeNames!!)
            }
        }
    }

    private fun buildExpressionValues(): Map<String, AttributeValue> {
        return buildMap {
            if (pk != "SCAN") {
                this[":pk"] = pk.attributeValue()
            }
            if (sk.expression != null) {
                this.putAll(sk.values)
            }

            if (!attributeValues.isNullOrEmpty()) {
                this.putAll(attributeValues!!)
            }
        }
    }

    private fun QueryRequest.Builder.buildQuery() {
        this.tableName = dynamo.table

        if (!forward) {
            scanIndexForward = false
        }

        token?.let {
            exclusiveStartKey = it.fromJsonToAttributeValue().asM()
        }

        if (this@Query.indexName != null) {
            indexName = this@Query.indexName
        }

        if (consistent) {
            this.consistentRead = (true)
        }

        if (!projection.isNullOrEmpty()) {
            this.projectionExpression = (projection?.joinToString(", "))
        }

        expressionAttributeNames = (buildExpressionNames())
        expressionAttributeValues = (buildExpressionValues())
        this.filterExpression = (filter)

        keyConditionExpression = ("#pk = :pk".let {
            if (sk.expression != null) {
                "$it and ${sk.expression}"
            } else {
                it
            }
        })

        if (this@Query.limit != null) {
            this.limit = this@Query.limit
        }
    }

    @OptIn(FlowPreview::class)
    internal suspend fun execute(): QueryResult {
        val request = QueryRequest {
            buildQuery()
        }

        return if (loadAll) {
            val result = dynamo.dynamo.queryPaginated(request)


            object : QueryResult {
                override val items: Flow<Item> = result.items().map { Item(dynamo, it) }
                override val paginationToken: String? = null
            }
        } else {
            SingleTableQueryResult(dynamo.dynamo.query(request))
        }
    }

    /**
     * Define the segment to scan for.
     */
    public fun segment(index: Int, total: Int) {
        segment = index
        segments = total
    }

    internal fun executeScan(): QueryResult {
        val items = dynamo.dynamo.scanPaginated {
            tableName = (dynamo.table)
            buildExpressionNames().takeIf { it.isNotEmpty() }?.let {
                expressionAttributeNames = it
            }
            buildExpressionValues().takeIf { it.isNotEmpty() }?.let {
                expressionAttributeValues = it
            }
            projection?.let {
                projectionExpression = it.joinToString(", ")
            }

            segment?.let {
                segment = it
            }
            segments?.let {
                totalSegments = it
            }

            filterExpression = filter
        }.items().map {
            Item(dynamo, it)
        }

        return object : QueryResult {
            override val items: Flow<Item> = items
            override val paginationToken: String? = null
        }
    }

    private inner class SingleTableQueryResult(val result: QueryResponse) : QueryResult {
        override val items by lazy { result.items?.map { Item(dynamo, it) }.orEmpty().asFlow() }
        override val paginationToken: String? by lazy {
            result.lastEvaluatedKey?.toJsonItemString()
        }
    }
}


public interface QueryResult {
    /**
     * The list of items
     */
    public val items: Flow<Item>

    /**
     * A pagination token used to subsequent calls to get more items.
     */
    public val paginationToken: String?
}