package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.BillingMode
import com.steamstreet.awskt.dynamodb.ProvisionedThroughput
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SchemaUtils] turns a [Table] definition into a real table, so the assertions are made against a
 * real DynamoDB: a table that was created correctly is one that serves the reads and writes the
 * definition promises. Checking the request object instead would only restate the mapping code.
 *
 * The projection tests are the sharpest of these - `projectionType` and `nonKeyAttributes` had no
 * consumer at all before, and an index that projects the wrong attributes is invisible until a
 * query silently comes back missing a value.
 */
@Testcontainers
class SchemaUtilsTest : ExposedTestBase() {

    object Simple : Table("schema_simple") {
        val id = varchar("id").partitionKey()
        val name = varchar("name")
    }

    object Composite : Table("schema_composite") {
        val customerId = varchar("customerId").partitionKey()
        val orderId = varchar("orderId").sortKey()
        val amount = integer("amount")
    }

    object Numeric : Table("schema_numeric") {
        val bucket = long("bucket").partitionKey()
        val seq = integer("seq").sortKey()
        val payload = varchar("payload")
    }

    object ProjectAll : Table("schema_project_all") {
        val id = varchar("id").partitionKey()
        val category = varchar("category")
        val title = varchar("title")
        val secret = varchar("secret")

        val byCategory = gsi("byCategory") {
            partitionKey(category)
            sortKey(title)
        }
    }

    object ProjectInclude : Table("schema_project_include") {
        val id = varchar("id").partitionKey()
        val category = varchar("category")
        val title = varchar("title")
        val secret = varchar("secret")

        val byCategory = gsi("byCategory") {
            partitionKey(category)
            projectionType = ProjectionType.INCLUDE
            nonKeyAttributes = listOf("title")
        }
    }

    object ProjectKeysOnly : Table("schema_project_keys") {
        val id = varchar("id").partitionKey()
        val category = varchar("category")
        val title = varchar("title")

        val byCategory = gsi("byCategory") {
            partitionKey(category)
            projectionType = ProjectionType.KEYS_ONLY
        }
    }

    object WithLsi : Table("schema_lsi") {
        val ownerId = varchar("ownerId").partitionKey()
        val noteId = varchar("noteId").sortKey()
        val priority = varchar("priority")
        val body = varchar("body")

        val byPriority = lsi("byPriority") {
            sortKey(priority)
        }
    }

    /** A boolean is a legal attribute but never a legal key. */
    object BoolKey : Table("schema_bool_key") {
        val flag = bool("flag").partitionKey()
        val name = varchar("name")
    }

    /** INCLUDE with nothing to include is a projection that projects nothing extra. */
    object EmptyInclude : Table("schema_empty_include") {
        val id = varchar("id").partitionKey()
        val category = varchar("category")

        val byCategory = gsi("byCategory") {
            partitionKey(category)
            projectionType = ProjectionType.INCLUDE
        }
    }

    // =========================================================================
    // Table creation
    // =========================================================================

    @Test
    fun `creates a partition-key-only table`() = runTest {
        SchemaUtils.createTable(database, Simple)

        Simple.insert(database) {
            it[id] = "user#1"
            it[name] = "Alice"
        }

        Simple.get(database) { Simple.id eq "user#1" }!![Simple.name].shouldBeEqualTo("Alice")
    }

    @Test
    fun `creates a composite-key table`() = runTest {
        SchemaUtils.createTable(database, Composite)

        Composite.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#002"
            it[amount] = 20
        }
        Composite.insert(database) {
            it[customerId] = "cust#1"
            it[orderId] = "order#001"
            it[amount] = 10
        }

        // The sort key exists and orders the partition, which is what makes this a composite table
        // rather than two items that happen to share an attribute.
        val rows = Composite.selectAll(database).where { Composite.customerId eq "cust#1" }.toList()
        rows.map { it[Composite.orderId] }.shouldBeEqualTo(listOf("order#001", "order#002"))
    }

    @Test
    fun `creates a table with numeric key columns`() = runTest {
        SchemaUtils.createTable(database, Numeric)

        listOf(2, 9, 10).forEach { n ->
            Numeric.insert(database) {
                it[bucket] = 1L
                it[seq] = n
                it[payload] = "p$n"
            }
        }

        val rows = Numeric.selectAll(database)
            .where { (Numeric.bucket eq 1L) and (Numeric.seq ge 9) }
            .toList()

        // Numeric ordering, not lexicographic: the keys really were declared as N, not S, so 10
        // sorts after 9 instead of before it.
        rows.map { it[Numeric.seq] }.shouldBeEqualTo(listOf(9, 10))
    }

    @Test
    fun `creates a table with a provisioned billing mode`() = runTest {
        SchemaUtils.createTable(
            database,
            Simple,
            billingMode = BillingMode.Provisioned,
            provisionedThroughput = ProvisionedThroughput(readCapacityUnits = 5, writeCapacityUnits = 5),
        )

        Simple.insert(database) {
            it[id] = "user#1"
            it[name] = "Alice"
        }

        Simple.get(database) { Simple.id eq "user#1" }!![Simple.name].shouldBeEqualTo("Alice")
    }

    // =========================================================================
    // Secondary indices and their projections
    // =========================================================================

    @Test
    fun `creates a GSI projecting all attributes`() = runTest {
        SchemaUtils.createTable(database, ProjectAll)

        ProjectAll.insert(database) {
            it[id] = "doc#1"
            it[category] = "books"
            it[title] = "Dune"
            it[secret] = "hidden"
        }

        val row = ProjectAll.selectAll(database)
            .where { ProjectAll.category eq "books" }
            .single()

        row[ProjectAll.title].shouldBeEqualTo("Dune")
        // ALL means everything, including attributes that are keys of neither the table nor the index.
        row[ProjectAll.secret].shouldBeEqualTo("hidden")
    }

    @Test
    fun `creates a GSI with an INCLUDE projection carrying only the named attributes`() = runTest {
        SchemaUtils.createTable(database, ProjectInclude)

        ProjectInclude.insert(database) {
            it[id] = "doc#1"
            it[category] = "books"
            it[title] = "Dune"
            it[secret] = "hidden"
        }

        val row = ProjectInclude.selectAll(database)
            .where { ProjectInclude.category eq "books" }
            .single()

        row[ProjectInclude.id].shouldBeEqualTo("doc#1")
        row[ProjectInclude.title].shouldBeEqualTo("Dune")
        // nonKeyAttributes named title and not secret; the index really was built that way.
        assertFalse(row.hasValue(ProjectInclude.secret), "secret is not projected into byCategory")
    }

    @Test
    fun `creates a GSI with a KEYS_ONLY projection`() = runTest {
        SchemaUtils.createTable(database, ProjectKeysOnly)

        ProjectKeysOnly.insert(database) {
            it[id] = "doc#1"
            it[category] = "books"
            it[title] = "Dune"
        }

        val row = ProjectKeysOnly.selectAll(database)
            .where { ProjectKeysOnly.category eq "books" }
            .single()

        row[ProjectKeysOnly.id].shouldBeEqualTo("doc#1")
        row[ProjectKeysOnly.category].shouldBeEqualTo("books")
        assertFalse(row.hasValue(ProjectKeysOnly.title), "KEYS_ONLY projects no non-key attributes")
    }

    @Test
    fun `creates a table with a local secondary index`() = runTest {
        SchemaUtils.createTable(database, WithLsi)

        listOf("n1" to "3", "n2" to "1", "n3" to "2").forEach { (note, prio) ->
            WithLsi.insert(database) {
                it[ownerId] = "owner#1"
                it[noteId] = note
                it[priority] = prio
                it[body] = "body of $note"
            }
        }

        val rows = WithLsi.selectAll(database)
            .where { (WithLsi.ownerId eq "owner#1") and (WithLsi.priority ge "1") }
            .toList()

        // Ordered by priority rather than by noteId, so the read went through byPriority.
        rows.map { it[WithLsi.noteId] }.shouldBeEqualTo(listOf("n2", "n3", "n1"))
        // The LSI defaults to an ALL projection, so non-key attributes come back.
        rows.first()[WithLsi.body].shouldBeEqualTo("body of n2")
    }

    // =========================================================================
    // createMissingTables / dropTable
    // =========================================================================

    @Test
    fun `createMissingTables creates every table and is idempotent`() = runTest {
        SchemaUtils.createMissingTables(database, Simple, Composite)

        Simple.insert(database) {
            it[id] = "user#1"
            it[name] = "Alice"
        }

        // A second call must leave the existing tables - and their contents - alone rather than
        // failing with ResourceInUseException or recreating them empty.
        SchemaUtils.createMissingTables(database, Simple, Composite)

        Simple.get(database) { Simple.id eq "user#1" }!![Simple.name].shouldBeEqualTo("Alice")
        Composite.scan(database).toList().shouldHaveSize(0)
    }

    @Test
    fun `createMissingTables recreates a dropped table`() = runTest {
        SchemaUtils.createTable(database, Simple)
        Simple.insert(database) {
            it[id] = "user#1"
            it[name] = "Alice"
        }

        SchemaUtils.dropTable(database, Simple)
        SchemaUtils.createMissingTables(database, Simple)

        Simple.scan(database).toList().shouldHaveSize(0)
    }

    @Test
    fun `tables are created under the resolved name`() = runTest {
        val prefixed = Database(database.client, tableNameMapper = { "test_$it" })

        SchemaUtils.createTable(prefixed, Simple)

        Simple.insert(prefixed) {
            it[id] = "user#1"
            it[name] = "Alice"
        }
        Simple.get(prefixed) { Simple.id eq "user#1" }!![Simple.name].shouldBeEqualTo("Alice")

        // The unprefixed name was never created, which is the whole point of the mapper.
        SchemaUtils.createMissingTables(database, Simple)
        Simple.scan(database).toList().shouldHaveSize(0)
    }

    // =========================================================================
    // Definition errors, all raised before anything is sent
    // =========================================================================

    @Test
    fun `a key column that is not S, N or B is rejected`() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            SchemaUtils.createTable(database, BoolKey)
        }
        assertTrue(
            failure.message!!.contains("flag"),
            "message should name the offending column: ${failure.message}",
        )
    }

    @Test
    fun `an INCLUDE projection naming no attributes is rejected`() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            SchemaUtils.createTable(database, EmptyInclude)
        }
        assertTrue(
            failure.message!!.contains("nonKeyAttributes"),
            "message should explain what is missing: ${failure.message}",
        )
    }

    @Test
    fun `billing mode and throughput must agree`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            SchemaUtils.createTable(database, Simple, billingMode = BillingMode.Provisioned)
        }

        assertFailsWith<IllegalArgumentException> {
            SchemaUtils.createTable(
                database,
                Simple,
                billingMode = BillingMode.PayPerRequest,
                provisionedThroughput = ProvisionedThroughput(5, 5),
            )
        }
    }
}
