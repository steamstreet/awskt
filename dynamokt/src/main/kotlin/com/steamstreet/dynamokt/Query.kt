package com.steamstreet.dynamokt

import com.steamstreet.awskt.dynamodb.QueryRequest
import com.steamstreet.awskt.dynamodb.QueryResponse
import com.steamstreet.awskt.dynamodb.ScanRequest
import com.steamstreet.awskt.dynamodb.items
import com.steamstreet.awskt.dynamodb.orNullIfEmpty
import com.steamstreet.awskt.dynamodb.queryPaged
import com.steamstreet.awskt.dynamodb.scanItems
import com.steamstreet.awskt.dynamodb.scanPaged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map


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

    private var attributeIndex = 1

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
            "#attr${attributeIndex++}".also {
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

    /**
     * Builds the request.
     *
     * Returns a value rather than mutating a builder, which is not a stylistic change: the AWS SDK's
     * mutable `QueryRequest.Builder` is gone, and a `data class` request is what makes the paginator
     * safe — `page.copy(exclusiveStartKey = …)` cannot silently drop a field the way the old
     * field-by-field reconstruction could.
     *
     * Every optional collection goes through `orNullIfEmpty()`: an assigned `emptyMap()` serializes
     * as `"ExpressionAttributeValues":{}`, which DynamoDB rejects outright.
     */
    private fun buildQuery(): QueryRequest = QueryRequest(
        tableName = dynamo.table,
        indexName = indexName,
        keyConditionExpression = "#pk = :pk".let {
            if (sk.expression != null) "$it and ${sk.expression}" else it
        },
        filterExpression = filter,
        projectionExpression = projection?.takeIf { it.isNotEmpty() }?.joinToString(", "),
        expressionAttributeNames = buildExpressionNames().orNullIfEmpty(),
        expressionAttributeValues = buildExpressionValues().orNullIfEmpty(),
        exclusiveStartKey = token?.fromJsonToItem(),
        limit = limit,
        scanIndexForward = false.takeIf { !forward },
        consistentRead = true.takeIf { consistent },
    )

    internal suspend fun execute(): QueryResult {
        val request = buildQuery()

        return if (loadAll) {
            val pages = dynamo.dynamo.queryPaged(request)
            object : QueryResult {
                override val items: Flow<Item> = pages.items().map { Item(dynamo, it) }
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
        val request = ScanRequest(
            tableName = dynamo.table,
            filterExpression = filter,
            projectionExpression = projection?.joinToString(", "),
            expressionAttributeNames = buildExpressionNames().orNullIfEmpty(),
            expressionAttributeValues = buildExpressionValues().orNullIfEmpty(),
            segment = segment,
            totalSegments = segments,
        )
        val items = dynamo.dynamo.scanPaged(request).scanItems().map { Item(dynamo, it) }

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