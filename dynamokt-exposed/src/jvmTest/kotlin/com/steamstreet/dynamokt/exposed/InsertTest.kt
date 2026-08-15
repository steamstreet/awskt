package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.ConditionalCheckFailedException
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers
class InsertTest : ExposedTestBase() {

    object Users : Table("users") {
        val id = varchar("id").partitionKey()
        val name = varchar("name")
        val email = varchar("email")
        val age = integer("age")
    }

    @Test
    fun `test basic insert`() = runTest {
        createTable(Users)

        // Insert using Exposed style syntax
        val inserted = Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John Doe"
            it[email] = "john@example.com"
            it[age] = 30
        }

        // Verify
        inserted[Users.id].shouldBeEqualTo("user#123")
        inserted[Users.name].shouldBeEqualTo("John Doe")
        inserted[Users.email].shouldBeEqualTo("john@example.com")
        inserted[Users.age].shouldBeEqualTo(30)

        // Verify it's in the database
        val retrieved = Users.get(database) { Users.id eq "user#123" }!!
        retrieved[Users.name].shouldBeEqualTo("John Doe")
    }

    @Test
    fun `test insert with ifNotExists syntax exists`() = runTest {
        createTable(Users)

        // Test that the ifNotExists() API exists and can be called
        Users.insert(database) {
            it[id] = "user#789"
            it[name] = "Bob"
            it[email] = "bob@example.com"
            it[age] = 40
            it.ifNotExists()
        }

        // Verify item was inserted
        val user = Users.get(database) { Users.id eq "user#789" }!!
        user[Users.name].shouldBeEqualTo("Bob")
    }

    @Test
    fun `test insert with ifNotExists allows first insert`() = runTest {
        createTable(Users)

        // First insert with ifNotExists should succeed
        val inserted = Users.insert(database) {
            it[id] = "user#new"
            it[name] = "New User"
            it[email] = "new@example.com"
            it[age] = 22
            it.ifNotExists()
        }

        inserted[Users.name].shouldBeEqualTo("New User")

        // Verify it's in database
        val user = Users.get(database) { Users.id eq "user#new" }!!
        user[Users.name].shouldBeEqualTo("New User")
    }

    @Test
    fun `test insert returns correct ResultRow`() = runTest {
        createTable(Users)

        val result = Users.insert(database) {
            it[id] = "user#result"
            it[name] = "Result Test"
            it[email] = "result@example.com"
            it[age] = 35
        }

        // Can immediately access values from result
        result[Users.id].shouldBeEqualTo("user#result")
        result[Users.name].shouldBeEqualTo("Result Test")
        result[Users.email].shouldBeEqualTo("result@example.com")
        result[Users.age].shouldBeEqualTo(35)
    }

    @Test
    fun `test insert without the partition key is rejected`() = runTest {
        createTable(Users)

        // Previously this reached DynamoDB and came back as an opaque ValidationException.
        val failure = assertFailsWith<IllegalArgumentException> {
            Users.insert(database) {
                it[name] = "No Key"
                it[email] = "nokey@example.com"
                it[age] = 1
            }
        }
        assertTrue(failure.message!!.contains("users"), "message should name the table: ${failure.message}")
        assertTrue(failure.message!!.contains("id"), "message should name the column: ${failure.message}")
    }

    @Test
    fun `test insert replaces an existing item wholesale`() = runTest {
        createTable(Users)

        Users.insert(database) {
            it[id] = "user#put"
            it[name] = "First"
            it[email] = "first@example.com"
            it[age] = 30
        }

        // insert is PutItem: the second write replaces the item, dropping the attributes it omits.
        Users.insert(database) {
            it[id] = "user#put"
            it[name] = "Second"
        }

        val user = Users.get(database) { Users.id eq "user#put" }!!
        user[Users.name].shouldBeEqualTo("Second")
        user.getOrNull(Users.email).shouldBeNull()
    }

    @Test
    fun `test ifNotExists gives Exposed-style insert semantics`() = runTest {
        createTable(Users)

        Users.insert(database) {
            it[id] = "user#once"
            it[name] = "First"
            it[email] = "first@example.com"
            it[age] = 30
        }

        assertFailsWith<ConditionalCheckFailedException> {
            Users.insert(database) {
                it[id] = "user#once"
                it[name] = "Second"
                it[email] = "second@example.com"
                it[age] = 31
                it.ifNotExists()
            }
        }

        Users.get(database) { Users.id eq "user#once" }!![Users.name].shouldBeEqualTo("First")
    }
}
