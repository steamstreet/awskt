package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.AttributeDefinition
import com.steamstreet.awskt.dynamodb.BillingMode
import com.steamstreet.awskt.dynamodb.CreateTableRequest
import com.steamstreet.awskt.dynamodb.KeySchemaElement
import com.steamstreet.awskt.dynamodb.KeyType
import com.steamstreet.awskt.dynamodb.Projection
import com.steamstreet.awskt.dynamodb.ProjectionType
import com.steamstreet.awskt.dynamodb.ScalarAttributeType
import com.steamstreet.awskt.dynamodb.GlobalSecondaryIndex as SdkGlobalSecondaryIndex
import com.steamstreet.awskt.dynamodb.LocalSecondaryIndex as SdkLocalSecondaryIndex
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Index selection has to consider every candidate, not just the first one whose partition key is
 * pinned: a secondary index whose sort key is also constrained beats a bare partition-key match on
 * the base table, and an LSI is reachable at all only because candidates are ranked rather than
 * short-circuited.
 *
 * Each test distinguishes the chosen index by *result order*, which is the one observable
 * difference between reading the same rows through the table and through an index.
 */
@Testcontainers
class IndexSelectionTest : ExposedTestBase() {

    /** Base sort key `docId` and GSI sort key `updatedAt` deliberately disagree on ordering. */
    object Docs : Table("index_docs") {
        val ownerId = varchar("ownerId").partitionKey()
        val docId = varchar("docId").sortKey()
        val updatedAt = varchar("updatedAt")

        val byUpdated = gsi("byUpdated") {
            partitionKey(ownerId)
            sortKey(updatedAt)
        }
    }

    /** Same shape, but the second sort key is an LSI. */
    object Notes : Table("index_notes") {
        val ownerId = varchar("ownerId").partitionKey()
        val noteId = varchar("noteId").sortKey()
        val priority = varchar("priority")
        val body = varchar("body")

        val byPriority = lsi("byPriority") {
            sortKey(priority)
        }
    }

    private suspend fun createDocsTable() {
        database.client.createTable(
            CreateTableRequest(
                tableName = Docs.tableName,
                keySchema = listOf(
                    KeySchemaElement(Docs.ownerId.name, KeyType.Hash),
                    KeySchemaElement(Docs.docId.name, KeyType.Range),
                ),
                attributeDefinitions = listOf(
                    AttributeDefinition(Docs.ownerId.name, ScalarAttributeType.S),
                    AttributeDefinition(Docs.docId.name, ScalarAttributeType.S),
                    AttributeDefinition(Docs.updatedAt.name, ScalarAttributeType.S),
                ),
                globalSecondaryIndexes = listOf(
                    SdkGlobalSecondaryIndex(
                        indexName = "byUpdated",
                        keySchema = listOf(
                            KeySchemaElement(Docs.ownerId.name, KeyType.Hash),
                            KeySchemaElement(Docs.updatedAt.name, KeyType.Range),
                        ),
                        projection = Projection(ProjectionType.All),
                    ),
                ),
                billingMode = BillingMode.PayPerRequest,
            ),
        )
    }

    private suspend fun createNotesTable() {
        database.client.createTable(
            CreateTableRequest(
                tableName = Notes.tableName,
                keySchema = listOf(
                    KeySchemaElement(Notes.ownerId.name, KeyType.Hash),
                    KeySchemaElement(Notes.noteId.name, KeyType.Range),
                ),
                attributeDefinitions = listOf(
                    AttributeDefinition(Notes.ownerId.name, ScalarAttributeType.S),
                    AttributeDefinition(Notes.noteId.name, ScalarAttributeType.S),
                    AttributeDefinition(Notes.priority.name, ScalarAttributeType.S),
                ),
                localSecondaryIndexes = listOf(
                    SdkLocalSecondaryIndex(
                        indexName = "byPriority",
                        keySchema = listOf(
                            KeySchemaElement(Notes.ownerId.name, KeyType.Hash),
                            KeySchemaElement(Notes.priority.name, KeyType.Range),
                        ),
                        projection = Projection(ProjectionType.All),
                    ),
                ),
                billingMode = BillingMode.PayPerRequest,
            ),
        )
    }

    private suspend fun insertDoc(doc: String, updated: String) {
        Docs.insert(database) {
            it[ownerId] = "owner#1"
            it[docId] = doc
            it[updatedAt] = updated
        }
    }

    private suspend fun insertNote(note: String, prio: String) {
        Notes.insert(database) {
            it[ownerId] = "owner#1"
            it[noteId] = note
            it[priority] = prio
            it[body] = "body of $note"
        }
    }

    @Test
    fun `a GSI with a constrained sort key beats a bare table partition key match`() = runTest {
        createDocsTable()

        insertDoc("a", "2024-03")
        insertDoc("b", "2024-01")
        insertDoc("c", "2024-02")

        val rows = Docs.selectAll(database)
            .where { (Docs.ownerId eq "owner#1") and (Docs.updatedAt ge "2024-01") }
            .toList()

        // Ordered by updatedAt, not by docId: the read went through byUpdated. On the base table
        // (the old behaviour, with updatedAt dropped entirely) this would be a, b, c.
        rows.map { it[Docs.docId] }.shouldBeEqualTo(listOf("b", "c", "a"))
    }

    @Test
    fun `a bare partition key match still uses the base table`() = runTest {
        createDocsTable()

        insertDoc("a", "2024-03")
        insertDoc("b", "2024-01")

        val rows = Docs.selectAll(database)
            .where { Docs.ownerId eq "owner#1" }
            .toList()

        rows.map { it[Docs.docId] }.shouldBeEqualTo(listOf("a", "b"))
    }

    @Test
    fun `an LSI sort key range is used`() = runTest {
        createNotesTable()

        insertNote("n1", "3")
        insertNote("n2", "1")
        insertNote("n3", "2")

        val rows = Notes.selectAll(database)
            .where { (Notes.ownerId eq "owner#1") and (Notes.priority ge "1") }
            .toList()

        rows.map { it[Notes.noteId] }.shouldBeEqualTo(listOf("n2", "n3", "n1"))
    }

    @Test
    fun `an LSI query may be consistently read`() = runTest {
        createNotesTable()

        insertNote("n1", "3")
        insertNote("n2", "1")

        // An LSI, unlike a GSI, supports ConsistentRead - the flag must be forwarded rather than
        // forced off for every secondary index.
        val consistent = Database(database.client, defaultConsistentRead = true)

        val rows = Notes.selectAll(consistent)
            .where { (Notes.ownerId eq "owner#1") and (Notes.priority le "3") }
            .toList()

        rows.map { it[Notes.noteId] }.shouldBeEqualTo(listOf("n2", "n1"))
    }

    @Test
    fun `a GSI query is never consistently read`() = runTest {
        createDocsTable()

        insertDoc("a", "2024-03")

        val consistent = Database(database.client, defaultConsistentRead = true)

        // DynamoDB rejects ConsistentRead on a GSI, so this only succeeds because the flag is
        // suppressed for global indices.
        val rows = Docs.selectAll(consistent)
            .where { (Docs.ownerId eq "owner#1") and (Docs.updatedAt ge "2024-01") }
            .toList()

        rows.map { it[Docs.docId] }.shouldBeEqualTo(listOf("a"))
    }

    // =========================================================================
    // Sort key condition coalescing
    // =========================================================================

    @Test
    fun `ge and le on the sort key coalesce into BETWEEN`() = runTest {
        createNotesTable()

        insertNote("n1", "1")
        insertNote("n2", "2")
        insertNote("n3", "3")
        insertNote("n4", "4")

        // Two separate key conditions on one key attribute are rejected by DynamoDB; this only
        // reaches the service as a single BETWEEN.
        val rows = Notes.selectAll(database)
            .where {
                (Notes.ownerId eq "owner#1") and
                    (Notes.noteId ge "n2") and
                    (Notes.noteId le "n3")
            }
            .toList()

        rows.map { it[Notes.noteId] }.shouldBeEqualTo(listOf("n2", "n3"))
    }

    @Test
    fun `other multi-condition sort key combinations are rejected`() = runTest {
        createNotesTable()

        val gtLt = Notes.selectAll(database).where {
            (Notes.ownerId eq "owner#1") and (Notes.noteId gt "n1") and (Notes.noteId lt "n4")
        }
        assertFailsWith<IllegalArgumentException> { gtLt.toList() }

        val twoPrefixes = Notes.selectAll(database).where {
            (Notes.ownerId eq "owner#1") and
                (Notes.noteId beginsWith "n") and
                (Notes.noteId beginsWith "n1")
        }
        assertFailsWith<IllegalArgumentException> { twoPrefixes.toList() }
    }

    @Test
    fun `a non-key-condition on the chosen index key is rejected`() = runTest {
        createNotesTable()

        // `neq` cannot be a key condition and cannot go into a FilterExpression either, because
        // noteId is a key attribute of the queried table.
        val query = Notes.selectAll(database).where {
            (Notes.ownerId eq "owner#1") and (Notes.noteId neq "n1")
        }

        assertFailsWith<IllegalArgumentException> { query.toList() }
    }

    @Test
    fun `a top level or has no extractable key`() = runTest {
        createNotesTable()

        val query = Notes.selectAll(database).where {
            (Notes.ownerId eq "owner#1") or (Notes.ownerId eq "owner#2")
        }

        assertFailsWith<NoIndexMatchException> { query.toList() }
    }

    @Test
    fun `a filter may reference table keys that are not keys of the queried index`() = runTest {
        createDocsTable()

        insertDoc("a", "2024-03")
        insertDoc("b", "2024-01")
        insertDoc("c", "2024-02")

        // docId is the table's sort key but not part of byUpdated, so it is a legal filter here.
        val rows = Docs.selectAll(database)
            .where {
                (Docs.ownerId eq "owner#1") and
                    (Docs.updatedAt ge "2024-01") and
                    (Docs.docId neq "c")
            }
            .toList()

        rows.map { it[Docs.docId] }.shouldBeEqualTo(listOf("b", "a"))
    }
}
