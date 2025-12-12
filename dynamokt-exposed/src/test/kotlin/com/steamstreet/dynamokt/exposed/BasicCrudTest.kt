package com.steamstreet.dynamokt.exposed

import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test

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

    @Test
    fun `test insert and get`() = runTest {
        createTable(Users)

        // Insert a user
        val inserted = Users.insert(database) {
            this[Users.id] = "user#123"
            this[Users.name] = "John Doe"
            this[Users.email] = "john@example.com"
            this[Users.age] = 30
            this[Users.active] = true
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
            this[Users.id] = "user#456"
            this[Users.name] = "Jane Doe"
            this[Users.age] = 25
            this[Users.active] = false
        }

        // Update the user
        val updated = Users.update(database, { Users.id eq "user#456" }) {
            this[Users.name] = "Jane Smith"
            this[Users.age] = 26
            this[Users.active] = true
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
            this[Users.id] = "user#789"
            this[Users.name] = "Bob Smith"
            this[Users.age] = 40
            this[Users.active] = true
        }

        // Verify it exists
        Users.get(database) { Users.id eq "user#789" }.shouldNotBeNull()

        // Delete the user
        Users.delete(database) { Users.id eq "user#789" }

        // Verify it's gone
        Users.get(database) { Users.id eq "user#789" }.shouldBeNull()
    }

    @Test
    fun `test increment`() = runTest {
        createTable(Users)

        // Insert user with age
        Users.insert(database) {
            this[Users.id] = "user#100"
            this[Users.name] = "Test User"
            this[Users.age] = 20
            this[Users.active] = true
        }

        // Increment age
        Users.update(database, { Users.id eq "user#100" }) {
            this.increment(Users.age, 5)
        }

        // Verify
        val user = Users.get(database) { Users.id eq "user#100" }!!
        user[Users.age].shouldBeEqualTo(25)
    }

    @Test
    fun `test with composite key`() = runTest {
        createTable(Orders)

        // Insert order
        Orders.insert(database) {
            this[Orders.customerId] = "customer#1"
            this[Orders.orderId] = "order#001"
            this[Orders.amount] = 100
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
            this[Orders.amount] = 150
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
            this[Products.id] = "product#1"
            this[Products.name] = "Widget"
        }

        val product1 = Products.get(database) { Products.id eq "product#1" }!!
        product1.getOrNull(Products.description).shouldBeNull()

        // Insert with a nullable field
        Products.insert(database) {
            this[Products.id] = "product#2"
            this[Products.name] = "Gadget"
            @Suppress("UNCHECKED_CAST")
            this[Products.description as Column<String>] = "A cool gadget"
        }

        val product2 = Products.get(database) { Products.id eq "product#2" }!!
        product2[Products.description].shouldBeEqualTo("A cool gadget")
    }
}

fun <T> T?.shouldNotBeNull() {
    if (this == null) {
        throw AssertionError("Expected value to not be null")
    }
}
