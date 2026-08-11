package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.TransactionCanceledException
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith

@Testcontainers
class TransactionTest : ExposedTestBase() {

    object Accounts : Table("accounts") {
        val id = varchar("id").partitionKey()
        val owner = varchar("owner")
        val balance = integer("balance")
        val status = varchar("status")
    }

    object Transfers : Table("transfers") {
        val id = varchar("id").partitionKey()
        val amount = integer("amount")
        val state = varchar("state")
    }

    private suspend fun createTables() {
        createTable(Accounts)
        createTable(Transfers)
    }

    private suspend fun account(id: String, balance: Int, status: String = "ACTIVE") {
        Accounts.insert(database) {
            it[Accounts.id] = id
            it[owner] = "owner-$id"
            it[Accounts.balance] = balance
            it[Accounts.status] = status
        }
    }

    @Test
    fun `test transaction commits writes across tables`() = runTest {
        createTables()
        account("account#1", 500)
        account("account#2", 100)

        database.transaction {
            Accounts.update({ Accounts.id eq "account#1" }) {
                it.increment(Accounts.balance, -100)
            }
            Accounts.update({ Accounts.id eq "account#2" }) {
                it.increment(Accounts.balance, 100)
            }
            Transfers.insert {
                it[id] = "transfer#1"
                it[amount] = 100
                it[state] = "COMPLETE"
            }
        }

        Accounts.get(database) { Accounts.id eq "account#1" }!![Accounts.balance].shouldBeEqualTo(400)
        Accounts.get(database) { Accounts.id eq "account#2" }!![Accounts.balance].shouldBeEqualTo(200)
        Transfers.get(database) { Transfers.id eq "transfer#1" }!![Transfers.state].shouldBeEqualTo("COMPLETE")
    }

    @Test
    fun `test transaction rolls back all writes when a condition fails`() = runTest {
        createTables()
        account("account#1", 50)
        account("account#2", 100)

        // The debit requires a balance of at least 100, which account#1 does not have.
        assertFailsWith<TransactionCanceledException> {
            database.transaction {
                Accounts.update({ Accounts.id eq "account#1" }) {
                    it.increment(Accounts.balance, -100)
                    it.condition { Accounts.balance ge 100 }
                }
                Accounts.update({ Accounts.id eq "account#2" }) {
                    it.increment(Accounts.balance, 100)
                }
                Transfers.insert {
                    it[id] = "transfer#1"
                    it[amount] = 100
                    it[state] = "COMPLETE"
                }
            }
        }

        // Neither the credit nor the insert should have been applied.
        Accounts.get(database) { Accounts.id eq "account#1" }!![Accounts.balance].shouldBeEqualTo(50)
        Accounts.get(database) { Accounts.id eq "account#2" }!![Accounts.balance].shouldBeEqualTo(100)
        Transfers.get(database) { Transfers.id eq "transfer#1" } shouldBeEqualTo null
    }

    @Test
    fun `test transaction supports insert update and delete together`() = runTest {
        createTables()
        account("account#1", 500)
        account("account#2", 100)

        database.transaction {
            Accounts.delete { Accounts.id eq "account#2" }
            Accounts.update({ Accounts.id eq "account#1" }) {
                it[Accounts.status] = "CLOSED"
            }
            Transfers.insert {
                it[id] = "transfer#final"
                it[amount] = 100
                it[state] = "SETTLED"
            }
        }

        Accounts.get(database) { Accounts.id eq "account#2" } shouldBeEqualTo null
        Accounts.get(database) { Accounts.id eq "account#1" }!![Accounts.status].shouldBeEqualTo("CLOSED")
        Transfers.get(database) { Transfers.id eq "transfer#final" }.shouldNotBeNull()
    }

    @Test
    fun `test insert with ifNotExists is rejected inside a transaction when item exists`() = runTest {
        createTables()
        account("account#1", 500)

        assertFailsWith<TransactionCanceledException> {
            database.transaction {
                Accounts.insert {
                    it[id] = "account#1"
                    it[owner] = "someone else"
                    it[balance] = 0
                    it[status] = "ACTIVE"
                    it.ifNotExists()
                }
            }
        }

        Accounts.get(database) { Accounts.id eq "account#1" }!![Accounts.owner]
            .shouldBeEqualTo("owner-account#1")
    }

    @Test
    fun `test condition check passes without writing to the checked item`() = runTest {
        createTables()
        account("account#1", 500)

        database.transaction {
            Accounts.conditionCheck({ Accounts.id eq "account#1" }) {
                Accounts.status eq "ACTIVE"
            }
            Transfers.insert {
                it[id] = "transfer#1"
                it[amount] = 25
                it[state] = "COMPLETE"
            }
        }

        // The checked account is untouched.
        val account = Accounts.get(database) { Accounts.id eq "account#1" }!!
        account[Accounts.balance].shouldBeEqualTo(500)
        account[Accounts.status].shouldBeEqualTo("ACTIVE")
        Transfers.get(database) { Transfers.id eq "transfer#1" }.shouldNotBeNull()
    }

    @Test
    fun `test failing condition check cancels the transaction`() = runTest {
        createTables()
        account("account#1", 500, status = "FROZEN")

        assertFailsWith<TransactionCanceledException> {
            database.transaction {
                Accounts.conditionCheck({ Accounts.id eq "account#1" }) {
                    Accounts.status eq "ACTIVE"
                }
                Transfers.insert {
                    it[id] = "transfer#1"
                    it[amount] = 25
                    it[state] = "COMPLETE"
                }
            }
        }

        Transfers.get(database) { Transfers.id eq "transfer#1" } shouldBeEqualTo null
    }

    @Test
    fun `test transaction is not committed when the block throws`() = runTest {
        createTables()

        assertFailsWith<IllegalStateException> {
            database.transaction {
                Transfers.insert {
                    it[id] = "transfer#1"
                    it[amount] = 25
                    it[state] = "COMPLETE"
                }
                error("something went wrong")
            }
        }

        Transfers.get(database) { Transfers.id eq "transfer#1" } shouldBeEqualTo null
    }

    @Test
    fun `test empty transaction is a no-op`() = runTest {
        createTables()

        val result = database.transaction {
            size shouldBeEqualTo 0
            isEmpty() shouldBeEqualTo true
            "done"
        }

        result shouldBeEqualTo "done"
    }

    @Test
    fun `test transaction returns the value of the block`() = runTest {
        createTables()

        val transferId = database.transaction {
            val newId = "transfer#42"
            Transfers.insert {
                it[id] = newId
                it[amount] = 42
                it[state] = "COMPLETE"
            }
            newId
        }

        transferId shouldBeEqualTo "transfer#42"
        Transfers.get(database) { Transfers.id eq transferId }!![Transfers.amount].shouldBeEqualTo(42)
    }

    @Test
    fun `test two operations on the same item are rejected`() = runTest {
        createTables()
        account("account#1", 500)

        assertFailsWith<IllegalArgumentException> {
            database.transaction {
                Accounts.update({ Accounts.id eq "account#1" }) {
                    it.increment(Accounts.balance, -100)
                }
                Accounts.update({ Accounts.id eq "account#1" }) {
                    it[Accounts.status] = "CLOSED"
                }
            }
        }

        Accounts.get(database) { Accounts.id eq "account#1" }!![Accounts.balance].shouldBeEqualTo(500)
    }

    @Test
    fun `test the same key in different tables is allowed`() = runTest {
        createTables()
        account("shared#1", 500)

        database.transaction {
            Accounts.update({ Accounts.id eq "shared#1" }) {
                it[Accounts.status] = "CLOSED"
            }
            Transfers.insert {
                it[id] = "shared#1"
                it[amount] = 1
                it[state] = "COMPLETE"
            }
        }

        Accounts.get(database) { Accounts.id eq "shared#1" }!![Accounts.status].shouldBeEqualTo("CLOSED")
        Transfers.get(database) { Transfers.id eq "shared#1" }.shouldNotBeNull()
    }

    @Test
    fun `test update with no changes is rejected`() = runTest {
        createTables()
        account("account#1", 500)

        assertFailsWith<IllegalArgumentException> {
            database.transaction {
                Accounts.update({ Accounts.id eq "account#1" }) {
                    it.condition { Accounts.status eq "ACTIVE" }
                }
            }
        }
    }

    @Test
    fun `test transactionGet returns rows in request order`() = runTest {
        createTables()
        account("account#1", 500)
        account("account#2", 100)
        Transfers.insert(database) {
            it[id] = "transfer#1"
            it[amount] = 75
            it[state] = "COMPLETE"
        }

        val rows = database.transactionGet {
            Accounts.get { Accounts.id eq "account#2" }
            Transfers.get { Transfers.id eq "transfer#1" }
            Accounts.get { Accounts.id eq "account#1" }
        }

        rows.size shouldBeEqualTo 3
        rows[0]!![Accounts.balance].shouldBeEqualTo(100)
        rows[1]!![Transfers.amount].shouldBeEqualTo(75)
        rows[2]!![Accounts.balance].shouldBeEqualTo(500)
    }

    @Test
    fun `test transactionGet returns null for missing items`() = runTest {
        createTables()
        account("account#1", 500)

        val rows = database.transactionGet {
            Accounts.get { Accounts.id eq "account#1" }
            Accounts.get { Accounts.id eq "account#missing" }
        }

        rows[0].shouldNotBeNull()
        rows[1] shouldBeEqualTo null
    }

    @Test
    fun `test empty transactionGet returns an empty list`() = runTest {
        createTables()

        database.transactionGet { }.size shouldBeEqualTo 0
    }

    @Test
    fun `test bound tables can be used inside a transaction`() = runTest {
        createTables()
        account("account#1", 500)

        val accounts = database.bind(Accounts)
        val transfers = database.bind(Transfers)

        database.transaction {
            accounts.update({ Accounts.id eq "account#1" }) {
                it.increment(Accounts.balance, -50)
            }
            transfers.insert {
                it[id] = "transfer#1"
                it[amount] = 50
                it[state] = "COMPLETE"
            }
        }

        Accounts.get(database) { Accounts.id eq "account#1" }!![Accounts.balance].shouldBeEqualTo(450)
        Transfers.get(database) { Transfers.id eq "transfer#1" }!![Transfers.amount].shouldBeEqualTo(50)
    }
}
