package com.steamstreet.dynamokt.exposed

import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Conditions that are not key conditions of the chosen index must reach DynamoDB as a
 * FilterExpression. Before this they were parsed and then dropped, so a query returned rows the
 * caller had explicitly excluded.
 */
@Testcontainers
class FilterExpressionTest : ExposedTestBase() {

    object Invoices : Table("filter_invoices") {
        val customerId = varchar("customerId").partitionKey()
        val invoiceId = varchar("invoiceId").sortKey()
        val status = varchar("status")
        val amount = integer("amount")
        val version = integer("version")
        val tags = list("tags", varchar("tag"))
    }

    private suspend fun insertInvoice(
        customer: String,
        invoice: String,
        status: String,
        amount: Int = 10,
        version: Int = 1,
        tags: List<String> = emptyList()
    ) {
        Invoices.insert(database) {
            it[customerId] = customer
            it[invoiceId] = invoice
            it[Invoices.status] = status
            it[Invoices.amount] = amount
            it[Invoices.version] = version
            it[Invoices.tags] = tags
        }
    }

    @Test
    fun `non-key equality on a query becomes a filter`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")
        insertInvoice("cust#1", "inv#2", "cancelled")
        insertInvoice("cust#1", "inv#3", "active")

        val rows = Invoices.selectAll(database)
            .where { (Invoices.customerId eq "cust#1") and (Invoices.status eq "active") }
            .toList()

        rows.map { it[Invoices.invoiceId] }.shouldBeEqualTo(listOf("inv#1", "inv#3"))
    }

    @Test
    fun `sort key condition and non-key condition combine`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "2023#1", "active")
        insertInvoice("cust#1", "2024#1", "active")
        insertInvoice("cust#1", "2024#2", "cancelled")

        val rows = Invoices.selectAll(database)
            .where {
                (Invoices.customerId eq "cust#1") and
                    (Invoices.invoiceId beginsWith "2024#") and
                    (Invoices.status eq "active")
            }
            .toList()

        rows.map { it[Invoices.invoiceId] }.shouldBeEqualTo(listOf("2024#1"))
    }

    @Test
    fun `full key plus a residual condition is demoted to a filtered query`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active", version = 3)

        val matching = Invoices.selectAll(database)
            .where {
                (Invoices.customerId eq "cust#1") and
                    (Invoices.invoiceId eq "inv#1") and
                    (Invoices.version eq 3)
            }
            .toList()
        matching.shouldHaveSize(1)

        // The residual is evaluated server-side, so a stale version yields nothing rather than
        // the item the GetItem route used to return regardless.
        val stale = Invoices.selectAll(database)
            .where {
                (Invoices.customerId eq "cust#1") and
                    (Invoices.invoiceId eq "inv#1") and
                    (Invoices.version eq 99)
            }
            .toList()
        stale.shouldHaveSize(0)
    }

    @Test
    fun `firstOrNull honours the filter`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "cancelled")
        insertInvoice("cust#1", "inv#2", "cancelled")
        insertInvoice("cust#1", "inv#3", "active")

        val row = Invoices.selectAll(database)
            .where { (Invoices.customerId eq "cust#1") and (Invoices.status eq "active") }
            .firstOrNull()

        row!![Invoices.invoiceId].shouldBeEqualTo("inv#3")
    }

    @Test
    fun `singleOrNull honours the filter`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "cancelled")
        insertInvoice("cust#1", "inv#2", "active")

        val row = Invoices.selectAll(database)
            .where { (Invoices.customerId eq "cust#1") and (Invoices.status eq "active") }
            .singleOrNull()

        row!![Invoices.invoiceId].shouldBeEqualTo("inv#2")
    }

    // =========================================================================
    // New operators
    // =========================================================================

    @Test
    fun `inList filters a scan`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")
        insertInvoice("cust#1", "inv#2", "pending")
        insertInvoice("cust#1", "inv#3", "cancelled")

        val rows = Invoices.scan(database)
            .where { Invoices.status inList listOf("active", "pending") }
            .toList()

        rows.map { it[Invoices.invoiceId] }.sorted().shouldBeEqualTo(listOf("inv#1", "inv#2"))
    }

    @Test
    fun `inList rejects an empty or oversized operand list`() {
        assertFailsWith<IllegalArgumentException> {
            SqlExpressionBuilder().run { Invoices.status inList emptyList() }
        }
        assertFailsWith<IllegalArgumentException> {
            SqlExpressionBuilder().run { Invoices.status inList (1..101).map { "s$it" } }
        }
    }

    @Test
    fun `contains matches a substring`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "partially-active")
        insertInvoice("cust#1", "inv#2", "cancelled")

        val rows = Invoices.scan(database)
            .where { Invoices.status contains "active" }
            .toList()

        rows.map { it[Invoices.invoiceId] }.shouldBeEqualTo(listOf("inv#1"))
    }

    @Test
    fun `contains matches a list element`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active", tags = listOf("urgent", "q1"))
        insertInvoice("cust#1", "inv#2", "active", tags = listOf("q1"))

        val rows = Invoices.scan(database)
            .where { Invoices.tags contains "urgent" }
            .toList()

        rows.map { it[Invoices.invoiceId] }.shouldBeEqualTo(listOf("inv#1"))
    }

    @Test
    fun `not negates a condition`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")
        insertInvoice("cust#1", "inv#2", "cancelled")

        val rows = Invoices.scan(database)
            .where { not(Invoices.status eq "cancelled") }
            .toList()

        rows.map { it[Invoices.invoiceId] }.shouldBeEqualTo(listOf("inv#1"))
    }

    @Test
    fun `new operators are usable as query filters`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")
        insertInvoice("cust#1", "inv#2", "pending")
        insertInvoice("cust#1", "inv#3", "cancelled")

        val rows = Invoices.selectAll(database)
            .where {
                (Invoices.customerId eq "cust#1") and
                    (Invoices.status inList listOf("active", "pending")) and
                    not(Invoices.status eq "pending")
            }
            .toList()

        rows.map { it[Invoices.invoiceId] }.shouldBeEqualTo(listOf("inv#1"))
    }

    // =========================================================================
    // count()
    // =========================================================================

    @Test
    fun `count counts query rows without transferring them`() = runTest {
        createTable(Invoices)

        (1..5).forEach { insertInvoice("cust#1", "inv#$it", if (it % 2 == 0) "active" else "cancelled") }
        insertInvoice("cust#2", "inv#9", "active")

        Invoices.selectAll(database)
            .where { Invoices.customerId eq "cust#1" }
            .count()
            .shouldBeEqualTo(5L)

        Invoices.selectAll(database)
            .where { (Invoices.customerId eq "cust#1") and (Invoices.status eq "active") }
            .count()
            .shouldBeEqualTo(2L)
    }

    @Test
    fun `count counts scan rows`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")
        insertInvoice("cust#2", "inv#2", "active")
        insertInvoice("cust#3", "inv#3", "cancelled")

        Invoices.scan(database).count().shouldBeEqualTo(3L)

        Invoices.scan(database)
            .where { Invoices.status eq "active" }
            .count()
            .shouldBeEqualTo(2L)
    }

    @Test
    fun `count on a full key lookup is zero or one`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")

        Invoices.selectAll(database)
            .where { (Invoices.customerId eq "cust#1") and (Invoices.invoiceId eq "inv#1") }
            .count()
            .shouldBeEqualTo(1L)

        Invoices.selectAll(database)
            .where { (Invoices.customerId eq "cust#1") and (Invoices.invoiceId eq "missing") }
            .count()
            .shouldBeEqualTo(0L)
    }

    @Test
    fun `count is capped by limit`() = runTest {
        createTable(Invoices)

        (1..6).forEach { insertInvoice("cust#1", "inv#$it", "active") }

        Invoices.selectAll(database)
            .where { Invoices.customerId eq "cust#1" }
            .limit(2)
            .count()
            .shouldBeEqualTo(2L)
    }

    @Test
    fun `count is unsupported for keys queries`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")

        assertFailsWith<UnsupportedOperationException> {
            Invoices.selectAll(database)
                .where { keys(listOf("cust#1" to "inv#1")) }
                .count()
        }
    }

    // =========================================================================
    // Builder hygiene
    // =========================================================================

    @Test
    fun `where twice throws`() {
        val query = Invoices.selectAll(database).where { Invoices.customerId eq "cust#1" }

        assertFailsWith<IllegalStateException> {
            query.where { Invoices.status eq "active" }
        }
    }

    @Test
    fun `andWhere combines with the existing predicate`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")
        insertInvoice("cust#1", "inv#2", "cancelled")

        val rows = Invoices.selectAll(database)
            .where { Invoices.customerId eq "cust#1" }
            .andWhere { Invoices.status eq "active" }
            .toList()

        rows.map { it[Invoices.invoiceId] }.shouldBeEqualTo(listOf("inv#1"))
    }

    @Test
    fun `andWhere sets the predicate when there is none`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")
        insertInvoice("cust#2", "inv#2", "active")

        val rows = Invoices.selectAll(database)
            .andWhere { Invoices.customerId eq "cust#1" }
            .toList()

        rows.shouldHaveSize(1)
    }

    @Test
    fun `keys cannot be combined with other conditions`() = runTest {
        createTable(Invoices)

        val query = Invoices.selectAll(database).where {
            keys(listOf("cust#1" to "inv#1")) and (Invoices.status eq "active")
        }

        assertFailsWith<IllegalArgumentException> { query.toList() }
    }

    @Test
    fun `a malformed page token is rejected`() {
        assertFailsWith<InvalidPageTokenException> {
            Invoices.selectAll(database).startAfter("this is not a token")
        }
    }

    @Test
    fun `a null page token is a no-op`() = runTest {
        createTable(Invoices)

        insertInvoice("cust#1", "inv#1", "active")

        val rows = Invoices.selectAll(database)
            .where { Invoices.customerId eq "cust#1" }
            .startAfter(null)
            .toList()

        rows.shouldHaveSize(1)
    }
}
