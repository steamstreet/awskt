package com.steamstreet.dynamokt.exposed

import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSize
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Batch reads and writes, exercised past DynamoDB's per-request limits (25 writes, 100 keys) so the
 * chunking in the client layer is actually covered rather than assumed.
 */
@Testcontainers
class BatchTest : ExposedTestBase() {

    object Users : Table("batch_users") {
        val id = varchar("id").partitionKey()
        val name = varchar("name")
        val age = integer("age")
    }

    object Orders : Table("batch_orders") {
        val customerId = varchar("customerId").partitionKey()
        val orderId = varchar("orderId").sortKey()
        val amount = integer("amount")
    }

    private data class NewUser(val id: String, val name: String, val age: Int)

    private fun users(count: Int): List<NewUser> =
        (0 until count).map { NewUser("user#$it", "User $it", it) }

    @Test
    fun `batchInsert writes more items than a single batch holds and echoes them back`() = runTest {
        createTable(Users)

        // 60 items is three BatchWriteItem chunks; the old unchunked write would have been rejected.
        val written = Users.batchInsert(database, users(60)) { statement, user ->
            statement[id] = user.id
            statement[name] = user.name
            statement[age] = user.age
        }

        written.shouldHaveSize(60)
        written[0][Users.id].shouldBeEqualTo("user#0")
        written[59][Users.name].shouldBeEqualTo("User 59")

        Users.scan(database).count().shouldBeEqualTo(60L)
        Users.get(database) { Users.id eq "user#42" }!![Users.name].shouldBeEqualTo("User 42")
    }

    @Test
    fun `batchInsert rejects ifNotExists because a batch write carries no condition`() = runTest {
        createTable(Users)

        val failure = assertFailsWith<IllegalArgumentException> {
            Users.batchInsert(database, users(2)) { statement, user ->
                statement[id] = user.id
                statement[name] = user.name
                statement[age] = user.age
                statement.ifNotExists()
            }
        }
        assertTrue(
            failure.message!!.contains("condition"),
            "message should explain conditions are unsupported: ${failure.message}",
        )

        // Nothing was sent: the whole batch is validated before the first request.
        Users.scan(database).count().shouldBeEqualTo(0L)
    }

    @Test
    fun `batchInsert rejects an explicit condition block`() = runTest {
        createTable(Users)

        assertFailsWith<IllegalArgumentException> {
            Users.batchInsert(database, users(2)) { statement, user ->
                statement[id] = user.id
                statement[name] = user.name
                statement[age] = user.age
                statement.condition { Users.age eq 1 }
            }
        }
    }

    @Test
    fun `batchInsert without the partition key is rejected`() = runTest {
        createTable(Users)

        val failure = assertFailsWith<IllegalArgumentException> {
            Users.batchInsert(database, users(2)) { statement, user ->
                statement[name] = user.name
                statement[age] = user.age
            }
        }
        assertTrue(failure.message!!.contains("id"), "message should name the column: ${failure.message}")
    }

    @Test
    fun `batchInsert with no items issues no request`() = runTest {
        createTable(Users)

        val written = Users.batchInsert(database, emptyList<NewUser>()) { statement, user ->
            statement[id] = user.id
        }

        written.shouldHaveSize(0)
        Users.scan(database).count().shouldBeEqualTo(0L)
    }

    @Test
    fun `batchDelete removes more items than a single batch holds`() = runTest {
        createTable(Users)

        Users.batchInsert(database, users(60)) { statement, user ->
            statement[id] = user.id
            statement[name] = user.name
            statement[age] = user.age
        }
        Users.scan(database).count().shouldBeEqualTo(60L)

        Users.batchDelete(database, (0 until 60).map { "user#$it" to null })

        Users.scan(database).count().shouldBeEqualTo(0L)
    }

    @Test
    fun `batchDelete on a composite key table deletes by full key`() = runTest {
        createTable(Orders)

        Orders.batchInsert(database, (0 until 30).toList()) { statement, i ->
            statement[customerId] = "cust#1"
            statement[orderId] = "order#$i"
            statement[amount] = i
        }

        Orders.batchDelete(database, (0 until 30).map { "cust#1" to "order#$it" })

        Orders.scan(database).count().shouldBeEqualTo(0L)
    }

    @Test
    fun `batchDelete with no keys issues no request`() = runTest {
        createTable(Users)

        Users.batchDelete(database, emptyList())

        Users.scan(database).count().shouldBeEqualTo(0L)
    }

    @Test
    fun `keys reads more keys than a single batch get holds`() = runTest {
        createTable(Orders)

        // 150 keys is two BatchGetItem chunks.
        Orders.batchInsert(database, (0 until 150).toList()) { statement, i ->
            statement[customerId] = "cust#1"
            statement[orderId] = "order#$i"
            statement[amount] = i
        }

        val rows = Orders.selectAll(database)
            .where { keys((0 until 150).map { "cust#1" to "order#$it" }) }
            .toList()

        rows.shouldHaveSize(150)
        // Order is undefined across a batch get, so compare as a set.
        rows.map { it[Orders.orderId] }.toSet()
            .shouldBeEqualTo((0 until 150).map { "order#$it" }.toSet())
    }

    @Test
    fun `keys collapses duplicates rather than having DynamoDB reject the request`() = runTest {
        createTable(Orders)

        Orders.batchInsert(database, listOf(1, 2)) { statement, i ->
            statement[customerId] = "cust#1"
            statement[orderId] = "order#$i"
            statement[amount] = i
        }

        val rows = Orders.selectAll(database)
            .where {
                keys(
                    listOf(
                        "cust#1" to "order#1",
                        "cust#1" to "order#1",
                        "cust#1" to "order#2",
                    )
                )
            }
            .toList()

        rows.shouldHaveSize(2)
    }

    @Test
    fun `keys with an empty list returns nothing`() = runTest {
        createTable(Orders)

        Orders.selectAll(database).where { keys(emptyList()) }.toList().shouldHaveSize(0)
    }

    @Test
    fun `keys on a composite key table rejects a null sort key`() = runTest {
        createTable(Orders)

        val failure = assertFailsWith<IllegalArgumentException> {
            Orders.selectAll(database)
                .where { keys(listOf("cust#1" to "order#1", "cust#2" to null)) }
                .toList()
        }
        assertTrue(
            failure.message!!.contains("batch_orders"),
            "message should name the table: ${failure.message}",
        )
        assertTrue(
            failure.message!!.contains("orderId"),
            "message should name the sort key: ${failure.message}",
        )
    }

    @Test
    fun `batchDelete on a composite key table rejects a null sort key`() = runTest {
        createTable(Orders)

        val failure = assertFailsWith<IllegalArgumentException> {
            Orders.batchDelete(database, listOf("cust#1" to null))
        }
        assertTrue(
            failure.message!!.contains("orderId"),
            "message should name the sort key: ${failure.message}",
        )
    }

    @Test
    fun `keys on a table without a sort key rejects a sort key value`() = runTest {
        createTable(Users)

        val failure = assertFailsWith<IllegalArgumentException> {
            Users.selectAll(database)
                .where { keys(listOf("user#1" to "unexpected")) }
                .toList()
        }
        assertTrue(
            failure.message!!.contains("batch_users"),
            "message should name the table: ${failure.message}",
        )
        assertTrue(
            failure.message!!.contains("no sort key"),
            "message should explain the table has no sort key: ${failure.message}",
        )
    }

    @Test
    fun `batchDelete on a table without a sort key rejects a sort key value`() = runTest {
        createTable(Users)

        assertFailsWith<IllegalArgumentException> {
            Users.batchDelete(database, listOf("user#1" to "unexpected"))
        }
    }

    @Test
    fun `keys still projects only the selected columns`() = runTest {
        createTable(Orders)

        Orders.batchInsert(database, listOf(1)) { statement, i ->
            statement[customerId] = "cust#1"
            statement[orderId] = "order#$i"
            statement[amount] = i
        }

        val rows = Orders.select(database, Orders.customerId, Orders.amount)
            .where { keys(listOf("cust#1" to "order#1")) }
            .toList()

        rows.shouldHaveSize(1)
        rows[0][Orders.customerId].shouldBeEqualTo("cust#1")
        rows[0][Orders.amount].shouldBeEqualTo(1)
        rows[0].getOrNull(Orders.orderId).shouldBeNull()
    }
}
