package com.steamstreet.dynamokt.exposed

import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test

@Testcontainers
class DatabaseScopeTest : ExposedTestBase() {

    object Users : Table("users") {
        val id = varchar("id").partitionKey()
        val name = varchar("name")
        val email = varchar("email")
        val age = integer("age")
    }

    object Orders : Table("orders") {
        val orderId = varchar("orderId").partitionKey()
        val userId = varchar("userId")
        val total = integer("total")
        val status = varchar("status")
    }

    @Test
    fun `test inDatabase scoped table operations`() = runTest {
        createTable(Users)

        // Bind table to database
        val usersDb = Users.inDatabase(database)

        // Use without passing database each time
        usersDb.insert {
            this[Users.id] = "user#123"
            this[Users.name] = "John Doe"
            this[Users.email] = "john@example.com"
            this[Users.age] = 30
        }

        // Get
        val user = usersDb.get { Users.id eq "user#123" }
        user.shouldNotBeNull()
        user[Users.name].shouldBeEqualTo("John Doe")
        user[Users.age].shouldBeEqualTo(30)

        // Update
        usersDb.update({ Users.id eq "user#123" }) {
            this[Users.age] = 31
            this[Users.email] = "john.doe@example.com"
        }

        val updated = usersDb.get { Users.id eq "user#123" }!!
        updated[Users.age].shouldBeEqualTo(31)
        updated[Users.email].shouldBeEqualTo("john.doe@example.com")

        // Delete
        usersDb.delete { Users.id eq "user#123" }
        val deleted = usersDb.get { Users.id eq "user#123" }
        deleted.shouldBeEqualTo(null)
    }

    @Test
    fun `test withTables scope for multiple operations`() = runTest {
        createTable(Users)
        createTable(Orders)

        // Execute multiple operations in a scoped context
        database.withTables {
            // Insert user
            insert(Users) {
                this[Users.id] = "user#456"
                this[Users.name] = "Jane Smith"
                this[Users.email] = "jane@example.com"
                this[Users.age] = 28
            }

            // Insert order for that user
            insert(Orders) {
                this[Orders.orderId] = "order#789"
                this[Orders.userId] = "user#456"
                this[Orders.total] = 15000
                this[Orders.status] = "PENDING"
            }

            // Update order status
            update(Orders, { Orders.orderId eq "order#789" }) {
                this[Orders.status] = "SHIPPED"
            }

            // Verify user exists
            val user = get(Users) { Users.id eq "user#456" }
            user.shouldNotBeNull()
            user[Users.name].shouldBeEqualTo("Jane Smith")

            // Verify order updated
            val order = get(Orders) { Orders.orderId eq "order#789" }
            order.shouldNotBeNull()
            order[Orders.status].shouldBeEqualTo("SHIPPED")
        }
    }

    @Test
    fun `test withTables with explicit inScope`() = runTest {
        createTable(Users)

        database.withTables {
            val usersScoped = Users.inScope()

            usersScoped.insert {
                this[Users.id] = "user#999"
                this[Users.name] = "Bob"
                this[Users.age] = 40
            }

            val user = usersScoped.get { Users.id eq "user#999" }
            user.shouldNotBeNull()
            user[Users.name].shouldBeEqualTo("Bob")

            usersScoped.update({ Users.id eq "user#999" }) {
                this[Users.age] = 41
            }

            val updated = usersScoped.get { Users.id eq "user#999" }!!
            updated[Users.age].shouldBeEqualTo(41)
        }
    }

    @Test
    fun `test mixing scoped and non-scoped operations`() = runTest {
        createTable(Users)

        val usersDb = Users.inDatabase(database)

        // Scoped operation
        usersDb.insert {
            this[Users.id] = "user#111"
            this[Users.name] = "Alice"
            this[Users.age] = 25
        }

        // Traditional non-scoped operation still works
        val user = Users.get(database) { Users.id eq "user#111" }
        user.shouldNotBeNull()
        user[Users.name].shouldBeEqualTo("Alice")

        // Update with scoped
        usersDb.update({ Users.id eq "user#111" }) {
            this[Users.age] = 26
        }

        // Verify with non-scoped
        val updated = Users.get(database) { Users.id eq "user#111" }!!
        updated[Users.age].shouldBeEqualTo(26)
    }

    @Test
    fun `test multiple tables with inDatabase`() = runTest {
        createTable(Users)
        createTable(Orders)

        val usersDb = Users.inDatabase(database)
        val ordersDb = Orders.inDatabase(database)

        // Insert user
        usersDb.insert {
            this[Users.id] = "user#777"
            this[Users.name] = "Charlie"
            this[Users.age] = 35
        }

        // Insert orders for user
        ordersDb.insert {
            this[Orders.orderId] = "order#001"
            this[Orders.userId] = "user#777"
            this[Orders.total] = 5000
            this[Orders.status] = "PENDING"
        }

        ordersDb.insert {
            this[Orders.orderId] = "order#002"
            this[Orders.userId] = "user#777"
            this[Orders.total] = 8000
            this[Orders.status] = "COMPLETED"
        }

        // Verify
        val user = usersDb.get { Users.id eq "user#777" }!!
        user[Users.name].shouldBeEqualTo("Charlie")

        val order1 = ordersDb.get { Orders.orderId eq "order#001" }!!
        order1[Orders.status].shouldBeEqualTo("PENDING")

        val order2 = ordersDb.get { Orders.orderId eq "order#002" }!!
        order2[Orders.status].shouldBeEqualTo("COMPLETED")
    }

    @Test
    fun `test infix to syntax works in scoped context`() = runTest {
        createTable(Users)

        val usersDb = Users.inDatabase(database)

        usersDb.insert {
            Users.id to "user#infix"
            Users.name to "Infix Test"
            Users.email to "infix@example.com"
            Users.age to 33
        }

        val user = usersDb.get { Users.id eq "user#infix" }!!
        user[Users.name].shouldBeEqualTo("Infix Test")
        user[Users.age].shouldBeEqualTo(33)
    }
}
