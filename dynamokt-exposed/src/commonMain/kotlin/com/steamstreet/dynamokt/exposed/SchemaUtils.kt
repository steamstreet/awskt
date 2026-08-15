package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.AttributeDefinition
import com.steamstreet.awskt.dynamodb.BillingMode
import com.steamstreet.awskt.dynamodb.CreateTableRequest
import com.steamstreet.awskt.dynamodb.DeleteTableRequest
import com.steamstreet.awskt.dynamodb.DescribeTableRequest
import com.steamstreet.awskt.dynamodb.KeySchemaElement
import com.steamstreet.awskt.dynamodb.KeyType
import com.steamstreet.awskt.dynamodb.Projection
import com.steamstreet.awskt.dynamodb.ProvisionedThroughput
import com.steamstreet.awskt.dynamodb.ResourceNotFoundException
import com.steamstreet.awskt.dynamodb.ScalarAttributeType
import com.steamstreet.awskt.dynamodb.StreamSpecification
import com.steamstreet.awskt.dynamodb.GlobalSecondaryIndex as ClientGlobalSecondaryIndex
import com.steamstreet.awskt.dynamodb.LocalSecondaryIndex as ClientLocalSecondaryIndex
import com.steamstreet.awskt.dynamodb.ProjectionType as ClientProjectionType

/**
 * Creates and drops DynamoDB tables from the [Table] definitions themselves, the way Exposed's
 * `SchemaUtils.create(table)` does for SQL.
 *
 * A [Table] already carries everything `CreateTable` needs - the partition and sort keys, every
 * `gsi { }` and `lsi { }` with its projection - so restating that in a second place, by hand, only
 * creates a way for the two to disagree.
 *
 * ### Intended for tests, local development and bootstrap
 *
 * In production the table is normally owned by infrastructure-as-code (CloudFormation, CDK,
 * Terraform), which also owns capacity, tags, TTL, encryption, backups, deletion protection and
 * everything else this object deliberately does not model. Use these functions for integration
 * tests against DynamoDB Local or LocalStack, for a local development database, and for one-off
 * bootstrapping - not as a substitute for the deployment pipeline.
 *
 * ### Creation is asynchronous
 *
 * `CreateTable` returns as soon as DynamoDB accepts the request; on real AWS the table (and each of
 * its global secondary indices) then spends time in `CREATING` and rejects data-plane calls until
 * it reaches `ACTIVE`. Nothing here waits for that. DynamoDB Local and LocalStack create tables
 * synchronously, which is why the test suites can write immediately; against AWS, poll
 * `describeTable` yourself.
 */
public object SchemaUtils {

    /**
     * Create the DynamoDB table described by [table].
     *
     * The table is created under [Database.resolveTableName], so a database configured with a
     * `tableNameMapper` creates the same prefixed table its reads and writes will use.
     *
     * Attribute definitions are derived from the key columns of the table and of every index, each
     * declared exactly once, with the scalar type inferred from the column type: `varchar`, `text`,
     * `timestamp`, `enumerationByName` and `customEnumeration` are `S`; `integer`, `long`, `double`
     * and `enumeration` (which stores the ordinal) are `N`; `binary` is `B`. A column of any other
     * type used as a key is rejected - DynamoDB allows only those three as key attributes.
     *
     * ### Capacity
     *
     * With the default [BillingMode.PayPerRequest] there is no throughput to configure and
     * [provisionedThroughput] must be null. With [BillingMode.Provisioned] it is required, and
     * **every global secondary index inherits the same value**: per-index capacity is a tuning
     * decision that belongs with whatever owns the production table, and this object is not that.
     * Local secondary indices share the table's capacity by definition and have none of their own.
     *
     * @param billingMode how the table is charged; pay-per-request by default, which is what a test
     *   or a local database wants
     * @param provisionedThroughput required for [BillingMode.Provisioned], rejected otherwise
     * @param streamSpecification optionally enable a DynamoDB stream on the new table
     * @throws IllegalArgumentException if the table has no partition key, a key column has a type
     *   that cannot be a DynamoDB key, an `INCLUDE` projection names no attributes, or the billing
     *   mode and throughput disagree
     */
    public suspend fun createTable(
        database: Database,
        table: Table,
        billingMode: BillingMode = BillingMode.PayPerRequest,
        provisionedThroughput: ProvisionedThroughput? = null,
        streamSpecification: StreamSpecification? = null
    ) {
        database.client.createTable(
            buildCreateTableRequest(database, table, billingMode, provisionedThroughput, streamSpecification)
        )
    }

    /**
     * Delete the DynamoDB table described by [table], with everything in it.
     *
     * Like [createTable] this resolves the name through [Database.resolveTableName], and like
     * `CreateTable` the deletion is asynchronous on AWS: the call returns while the table is still
     * `DELETING`.
     *
     * @throws com.steamstreet.awskt.dynamodb.ResourceNotFoundException if the table does not exist
     */
    public suspend fun dropTable(database: Database, table: Table) {
        database.client.deleteTable(DeleteTableRequest(tableName = database.resolveTableName(table)))
    }

    /**
     * Create each of [tables] that does not already exist, leaving the others untouched.
     *
     * Existence is decided by `DescribeTable`: a table whose description comes back is kept as it
     * is - **including when its schema no longer matches the [Table] definition**. Nothing here
     * migrates or reconciles an existing table, so a key or index change needs the table dropped and
     * recreated (or a real migration).
     *
     * Only DynamoDB's `ResourceNotFoundException` is treated as "missing"; a throttle, a credentials
     * failure or any other error propagates rather than being mistaken for absence and answered with
     * a `CreateTable` that then fails for an unrelated reason.
     *
     * Tables are created with the [createTable] defaults (pay-per-request, no stream). Call
     * [createTable] directly when a table needs provisioned capacity or a stream.
     */
    public suspend fun createMissingTables(database: Database, vararg tables: Table) {
        tables.forEach { table ->
            if (!exists(database, table)) {
                createTable(database, table)
            }
        }
    }

    /**
     * Whether `DescribeTable` finds the table this [table] resolves to.
     */
    private suspend fun exists(database: Database, table: Table): Boolean = try {
        database.client.describeTable(DescribeTableRequest(tableName = database.resolveTableName(table)))
        true
    } catch (notFound: ResourceNotFoundException) {
        false
    }
}

/**
 * Render a [Table] into the `CreateTable` request that describes it.
 *
 * Separate from the call so the mapping can be reasoned about - and asserted on - without a
 * DynamoDB endpoint.
 */
internal fun buildCreateTableRequest(
    database: Database,
    table: Table,
    billingMode: BillingMode,
    provisionedThroughput: ProvisionedThroughput?,
    streamSpecification: StreamSpecification?
): CreateTableRequest {
    when (billingMode) {
        BillingMode.Provisioned -> requireNotNull(provisionedThroughput) {
            "Creating table '${table.tableName}' with BillingMode.Provisioned requires a " +
                "provisionedThroughput; DynamoDB has no default read and write capacity."
        }

        BillingMode.PayPerRequest -> require(provisionedThroughput == null) {
            "Creating table '${table.tableName}' with BillingMode.PayPerRequest cannot take a " +
                "provisionedThroughput: an on-demand table has no provisioned capacity, and " +
                "DynamoDB rejects the request. Pass BillingMode.Provisioned to set capacity."
        }
    }

    val pkColumn = table.partitionKey ?: throw IllegalArgumentException(
        "Table '${table.tableName}' has no partition key, so it cannot be created. Mark a column " +
            "with partitionKey()."
    )
    val skColumn = table.sortKey

    // Every key attribute of the table and of every index, in declaration order, each declared once.
    // DynamoDB rejects both a missing definition and a duplicate one.
    val attributeTypes = linkedMapOf<String, ScalarAttributeType>()
    fun declare(column: Column<*>) {
        val type = keyScalarType(table, column)
        val existing = attributeTypes.put(column.name, type)
        require(existing == null || existing == type) {
            "Attribute '${column.name}' on table '${table.tableName}' is used as a key with two " +
                "different scalar types ($existing and $type). A DynamoDB attribute has one type."
        }
    }

    declare(pkColumn)
    skColumn?.let { declare(it) }

    val globalIndices = mutableListOf<ClientGlobalSecondaryIndex>()
    val localIndices = mutableListOf<ClientLocalSecondaryIndex>()

    table.indices.forEach { index ->
        when (index) {
            is GlobalSecondaryIndex -> {
                declare(index.partitionKey)
                index.sortKey?.let { declare(it) }
                globalIndices.add(
                    ClientGlobalSecondaryIndex(
                        indexName = index.name,
                        keySchema = keySchema(index.partitionKey, index.sortKey),
                        projection = projection(table, index.name, index.projectionType, index.nonKeyAttributes),
                        // Inherits the table's capacity; see SchemaUtils.createTable.
                        provisionedThroughput = provisionedThroughput,
                    )
                )
            }

            is LocalSecondaryIndex -> {
                // An LSI's partition key is the table's, already declared above.
                declare(index.sortKey)
                localIndices.add(
                    ClientLocalSecondaryIndex(
                        indexName = index.name,
                        keySchema = keySchema(pkColumn, index.sortKey),
                        projection = projection(table, index.name, index.projectionType, index.nonKeyAttributes),
                    )
                )
            }
        }
    }

    return CreateTableRequest(
        tableName = database.resolveTableName(table),
        attributeDefinitions = attributeTypes.map { (name, type) -> AttributeDefinition(name, type) },
        keySchema = keySchema(pkColumn, skColumn),
        billingMode = billingMode,
        provisionedThroughput = provisionedThroughput,
        globalSecondaryIndexes = globalIndices.ifEmpty { null },
        localSecondaryIndexes = localIndices.ifEmpty { null },
        streamSpecification = streamSpecification,
    )
}

/**
 * The HASH (and optional RANGE) key schema for a table or an index.
 */
private fun keySchema(pkColumn: Column<*>, skColumn: Column<*>?): List<KeySchemaElement> = buildList {
    add(KeySchemaElement(pkColumn.name, KeyType.Hash))
    skColumn?.let { add(KeySchemaElement(it.name, KeyType.Range)) }
}

/**
 * Map the module's [ProjectionType] onto the client's, validating the one combination DynamoDB
 * rejects at the service and the one it silently accepts as useless.
 */
private fun projection(
    table: Table,
    indexName: String,
    projectionType: ProjectionType,
    nonKeyAttributes: List<String>?
): Projection = when (projectionType) {
    ProjectionType.ALL -> {
        require(nonKeyAttributes.isNullOrEmpty()) {
            "Index '$indexName' on table '${table.tableName}' projects ALL but also names " +
                "nonKeyAttributes. NonKeyAttributes only apply to an INCLUDE projection; ALL " +
                "already projects every attribute."
        }
        Projection(projectionType = ClientProjectionType.All)
    }

    ProjectionType.KEYS_ONLY -> {
        require(nonKeyAttributes.isNullOrEmpty()) {
            "Index '$indexName' on table '${table.tableName}' projects KEYS_ONLY but also names " +
                "nonKeyAttributes. Set projectionType = ProjectionType.INCLUDE to project them."
        }
        Projection(projectionType = ClientProjectionType.KeysOnly)
    }

    ProjectionType.INCLUDE -> {
        require(!nonKeyAttributes.isNullOrEmpty()) {
            "Index '$indexName' on table '${table.tableName}' projects INCLUDE but names no " +
                "nonKeyAttributes. An INCLUDE projection with an empty list is KEYS_ONLY written " +
                "the long way, and DynamoDB rejects it - set nonKeyAttributes, or use " +
                "ProjectionType.KEYS_ONLY."
        }
        Projection(projectionType = ClientProjectionType.Include, nonKeyAttributes = nonKeyAttributes)
    }
}

/**
 * The DynamoDB scalar type of a column used as a key attribute.
 *
 * Only `S`, `N` and `B` may be key types, so a column of any other type is a definition error
 * rather than something to encode and let the service refuse.
 */
private fun keyScalarType(table: Table, column: Column<*>): ScalarAttributeType {
    // Nullability says nothing about the stored type, and a key attribute has to be present on
    // every item anyway, so infer from what the wrapper wraps.
    val underlying = (column as? NullableColumn<*>)?.wrapped ?: column

    return when (underlying) {
        is VarCharColumn,
        is TextColumn,
        is TimestampColumn,
        is EnumerationByNameColumn<*>,
        is CustomEnumerationColumn<*> -> ScalarAttributeType.S

        is IntegerColumn,
        is LongColumn,
        is DoubleColumn,
        // enumeration() stores the ordinal as a number; enumerationByName() is the string form.
        is EnumerationColumn<*> -> ScalarAttributeType.N

        is BinaryColumn -> ScalarAttributeType.B

        else -> throw IllegalArgumentException(
            "Column '${column.name}' on table '${table.tableName}' is a " +
                "${underlying::class.simpleName} and cannot be a key attribute. DynamoDB keys must " +
                "be string (S), number (N) or binary (B): use varchar, text, timestamp, " +
                "enumerationByName, customEnumeration, integer, long, double, enumeration or binary."
        )
    }
}
