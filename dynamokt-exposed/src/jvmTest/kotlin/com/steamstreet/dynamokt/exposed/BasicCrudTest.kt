package com.steamstreet.dynamokt.exposed

import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers
class BasicCrudTest : ExposedTestBase() {

    // Define test tables
    object Users : Table("users") {
        val id = varchar("id").partitionKey()
        val name = varchar("name")
        val email = varchar("email")
        val age = integer("age")
        val active = bool("active")
    }

    object Orders : Table("orders") {
        val customerId = varchar("customerId").partitionKey()
        val orderId = varchar("orderId").sortKey()
        val amount = integer("amount")
    }

    object Products : Table("products") {
        val id = varchar("id").partitionKey()
        val name = varchar("name")
        val description = varchar("description").nullable()
    }

    object Counters : Table("counters") {
        val id = varchar("id").partitionKey()
        val hits = long("hits")
    }

    @Test
    fun `test insert and get`() = runTest {
        createTable(Users)

        // Insert a user
        val inserted = Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John Doe"
            it[email] = "john@example.com"
            it[age] = 30
            it[active] = true
        }

        // Verify inserted values
        inserted[Users.id].shouldBeEqualTo("user#123")
        inserted[Users.name].shouldBeEqualTo("John Doe")
        inserted[Users.age].shouldBeEqualTo(30)

        // Get the user
        val user = Users.get(database) { Users.id eq "user#123" }
        user!!.shouldNotBeNull()
        user[Users.name].shouldBeEqualTo("John Doe")
        user[Users.email].shouldBeEqualTo("john@example.com")
        user[Users.age].shouldBeEqualTo(30)
        user[Users.active].shouldBeEqualTo(true)
    }

    @Test
    fun `test update`() = runTest {
        createTable(Users)

        // Insert initial user
        Users.insert(database) {
            it[id] = "user#456"
            it[name] = "Jane Doe"
            it[age] = 25
            it[active] = false
        }

        // Update the user
        val updated = Users.update(database, { Users.id eq "user#456" }) {
            it[name] = "Jane Smith"
            it[age] = 26
            it[active] = true
        }

        // Verify updated values
        updated[Users.name].shouldBeEqualTo("Jane Smith")
        updated[Users.age].shouldBeEqualTo(26)
        updated[Users.active].shouldBeEqualTo(true)

        // Get and verify
        val user = Users.get(database) { Users.id eq "user#456" }!!
        user[Users.name].shouldBeEqualTo("Jane Smith")
        user[Users.age].shouldBeEqualTo(26)
    }

    @Test
    fun `test delete`() = runTest {
        createTable(Users)

        // Insert a user
        Users.insert(database) {
            it[id] = "user#789"
            it[name] = "Bob Smith"
            it[age] = 40
            it[active] = true
        }

        // Verify it exists
        Users.get(database) { Users.id eq "user#789" }.shouldNotBeNull()

        // Delete the user
        Users.delete(database) { Users.id eq "user#789" }

        // Verify it's gone
        Users.get(database) { Users.id eq "user#789" }.shouldBeNull()
    }

    @Test
    fun `test increment with Exposed style syntax`() = runTest {
        createTable(Users)

        // Insert user with age
        Users.insert(database) {
            it[id] = "user#100"
            it[name] = "Test User"
            it[age] = 20
            it[active] = true
        }

        // Increment age using Exposed-style syntax: it[column] = column + amount
        Users.update(database, { Users.id eq "user#100" }) {
            it[age] = age + 5
        }

        // Verify
        val user = Users.get(database) { Users.id eq "user#100" }!!
        user[Users.age].shouldBeEqualTo(25)
    }

    @Test
    fun `test increment by one`() = runTest {
        createTable(Users)

        // Insert user with age
        Users.insert(database) {
            it[id] = "user#101"
            it[name] = "Counter User"
            it[age] = 0
            it[active] = true
        }

        // Increment by 1 using Exposed-style syntax
        Users.update(database, { Users.id eq "user#101" }) {
            it[age] = age + 1
        }

        val user = Users.get(database) { Users.id eq "user#101" }!!
        user[Users.age].shouldBeEqualTo(1)

        // Increment again
        Users.update(database, { Users.id eq "user#101" }) {
            it[age] = age + 1
        }

        val user2 = Users.get(database) { Users.id eq "user#101" }!!
        user2[Users.age].shouldBeEqualTo(2)
    }

    @Test
    fun `test increment with legacy method`() = runTest {
        createTable(Users)

        // Insert user with age
        Users.insert(database) {
            it[id] = "user#102"
            it[name] = "Legacy User"
            it[age] = 10
            it[active] = true
        }

        // Increment using legacy method still works
        Users.update(database, { Users.id eq "user#102" }) {
            it.increment(age, 5)
        }

        // Verify
        val user = Users.get(database) { Users.id eq "user#102" }!!
        user[Users.age].shouldBeEqualTo(15)
    }

    @Test
    fun `test with composite key`() = runTest {
        createTable(Orders)

        // Insert order
        Orders.insert(database) {
            it[customerId] = "customer#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }

        // Get with composite key
        val order = Orders.get(database) {
            (Orders.customerId eq "customer#1") and (Orders.orderId eq "order#001")
        }!!
        order[Orders.customerId].shouldBeEqualTo("customer#1")
        order[Orders.orderId].shouldBeEqualTo("order#001")
        order[Orders.amount].shouldBeEqualTo(100)

        // Update with composite key
        Orders.update(database, {
            (Orders.customerId eq "customer#1") and (Orders.orderId eq "order#001")
        }) {
            it[amount] = 150
        }

        val updated = Orders.get(database) {
            (Orders.customerId eq "customer#1") and (Orders.orderId eq "order#001")
        }!!
        updated[Orders.amount].shouldBeEqualTo(150)

        // Delete with composite key
        Orders.delete(database) {
            (Orders.customerId eq "customer#1") and (Orders.orderId eq "order#001")
        }
        Orders.get(database) {
            (Orders.customerId eq "customer#1") and (Orders.orderId eq "order#001")
        }.shouldBeNull()
    }

    @Test
    fun `test nullable columns`() = runTest {
        createTable(Products)

        // Insert without nullable field
        Products.insert(database) {
            it[id] = "product#1"
            it[name] = "Widget"
        }

        val product1 = Products.get(database) { Products.id eq "product#1" }!!
        product1.getOrNull(Products.description).shouldBeNull()

        // Insert with a nullable field
        Products.insert(database) {
            it[id] = "product#2"
            it[name] = "Gadget"
            @Suppress("UNCHECKED_CAST")
            it[description as Column<String>] = "A cool gadget"
        }

        val product2 = Products.get(database) { Products.id eq "product#2" }!!
        product2[Products.description].shouldBeEqualTo("A cool gadget")

        // getOrNull on a present, non-null nullable column must not throw (previously a
        // ClassCastException from calling asNull() on a non-Null AttributeValue).
        product2.getOrNull(Products.description).shouldBeEqualTo("A cool gadget")
    }

    // -- Delete reports whether anything was there -----------------------------------------------

    @Test
    fun `test delete reports whether an item existed`() = runTest {
        createTable(Users)

        // Nothing at that key: DynamoDB's delete is idempotent, so this is a success, but false.
        Users.delete(database) { Users.id eq "user#absent" }.shouldBeEqualTo(false)

        Users.insert(database) {
            it[id] = "user#present"
            it[name] = "Present"
            it[age] = 30
            it[active] = true
        }

        Users.delete(database) { Users.id eq "user#present" }.shouldBeEqualTo(true)
        // ... and deleting it a second time now reports false.
        Users.delete(database) { Users.id eq "user#present" }.shouldBeEqualTo(false)
    }

    @Test
    fun `test conditional delete reports whether an item existed`() = runTest {
        createTable(Users)

        Users.delete(database, { Users.id eq "user#absent" }) { }.shouldBeEqualTo(false)

        Users.insert(database) {
            it[id] = "user#present"
            it[name] = "Present"
            it[age] = 30
            it[active] = true
        }

        Users.delete(database, { Users.id eq "user#present" }) {
            it.condition { active eq true }
        }.shouldBeEqualTo(true)
    }

    // -- Long increments -------------------------------------------------------------------------

    @Test
    fun `test increment a long column`() = runTest {
        createTable(Counters)

        Counters.insert(database) {
            it[id] = "counter#1"
            it[hits] = 5_000_000_000L
        }

        Counters.update(database, { Counters.id eq "counter#1" }) {
            it.increment(Counters.hits, 3L)
        }
        Counters.get(database) { Counters.id eq "counter#1" }!![Counters.hits]
            .shouldBeEqualTo(5_000_000_003L)

        // Default amount is 1.
        Counters.update(database, { Counters.id eq "counter#1" }) {
            it.increment(Counters.hits)
        }
        Counters.get(database) { Counters.id eq "counter#1" }!![Counters.hits]
            .shouldBeEqualTo(5_000_000_004L)

        // Negative amounts decrement.
        Counters.update(database, { Counters.id eq "counter#1" }) {
            it.increment(Counters.hits, -4L)
        }
        Counters.get(database) { Counters.id eq "counter#1" }!![Counters.hits]
            .shouldBeEqualTo(5_000_000_000L)
    }

    // -- Where clauses that cannot identify a single item ----------------------------------------

    @Test
    fun `test update without the partition key is rejected`() = runTest {
        createTable(Users)

        val failure = assertFailsWith<IllegalArgumentException> {
            Users.update(database, { Users.name eq "John Doe" }) {
                it[age] = 31
            }
        }
        assertTrue(failure.message!!.contains("id"), "message should name the key: ${failure.message}")
    }

    @Test
    fun `test update without the sort key is rejected`() = runTest {
        createTable(Orders)

        val failure = assertFailsWith<IllegalArgumentException> {
            Orders.update(database, { Orders.customerId eq "customer#1" }) {
                it[amount] = 150
            }
        }
        assertTrue(
            failure.message!!.contains("orderId"),
            "message should name the sort key: ${failure.message}",
        )
    }

    @Test
    fun `test a range condition on the sort key is rejected for update and delete`() = runTest {
        createTable(Orders)

        // beginsWith cannot pin a single item, so it is the same error as omitting the sort key.
        assertFailsWith<IllegalArgumentException> {
            Orders.update(database, {
                (Orders.customerId eq "customer#1") and (Orders.orderId beginsWith "order#")
            }) {
                it[amount] = 150
            }
        }

        assertFailsWith<IllegalArgumentException> {
            Orders.delete(database) {
                (Orders.customerId eq "customer#1") and (Orders.orderId beginsWith "order#")
            }
        }
    }

    @Test
    fun `test keys is rejected for update and delete`() = runTest {
        createTable(Users)

        assertFailsWith<IllegalArgumentException> {
            Users.delete(database) { keys(listOf("user#1" to null, "user#2" to null)) }
        }

        assertFailsWith<IllegalArgumentException> {
            Users.update(database, { keys(listOf("user#1" to null)) }) {
                it[age] = 1
            }
        }
    }

    @Test
    fun `test a non-key condition on a composite key table is applied as a condition`() = runTest {
        createTable(Orders)

        Orders.insert(database) {
            it[customerId] = "customer#1"
            it[orderId] = "order#001"
            it[amount] = 100
        }

        // Full key plus a non-key conjunct: the conjunct becomes the ConditionExpression.
        Orders.update(database, {
            (Orders.customerId eq "customer#1") and (Orders.orderId eq "order#001") and
                (Orders.amount eq 100)
        }) {
            it[amount] = 150
        }

        Orders.get(database) {
            (Orders.customerId eq "customer#1") and (Orders.orderId eq "order#001")
        }!![Orders.amount].shouldBeEqualTo(150)
    }
}

fun <T> T?.shouldNotBeNull() {
    if (this == null) {
        throw AssertionError("Expected value to not be null")
    }
}
