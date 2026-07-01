package com.steamstreet.dynamokt.exposed

import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith

@Testcontainers
class ConditionalWriteTest : ExposedTestBase() {

    object Users : Table("users") {
        val id = varchar("id").partitionKey()
        val name = varchar("name")
        val version = integer("version")
        val status = varchar("status")
    }

    @Test
    fun `test conditional update with version check succeeds`() = runTest {
        createTable(Users)

        // Insert initial item with version 1
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
        }

        // Update with correct version check
        val updated = Users.update(database, { Users.id eq "user#123" }) {
            it[name] = "John Updated"
            it[version] = 2
            it.condition { version eq 1 }
        }

        updated[Users.name].shouldBeEqualTo("John Updated")
        updated[Users.version].shouldBeEqualTo(2)
    }

    @Test
    fun `test conditional update with version check fails when version mismatch`() = runTest {
        createTable(Users)

        // Insert initial item with version 1
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
        }

        // Try to update with wrong version check
        assertFailsWith<ConditionalCheckFailedException> {
            Users.update(database, { Users.id eq "user#123" }) {
                it[name] = "John Updated"
                it[version] = 2
                it.condition { version eq 5 }  // Wrong version
            }
        }

        // Verify item was not updated
        val user = Users.get(database) { Users.id eq "user#123" }!!
        user[Users.name].shouldBeEqualTo("John")
        user[Users.version].shouldBeEqualTo(1)
    }

    @Test
    fun `test conditional update with attribute exists`() = runTest {
        createTable(Users)

        // Insert initial item
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
        }

        // Update only if status attribute exists - should fail
        assertFailsWith<ConditionalCheckFailedException> {
            Users.update(database, { Users.id eq "user#123" }) {
                it[name] = "John Updated"
                it.condition { status.exists() }
            }
        }
    }

    @Test
    fun `test conditional update with attribute not exists`() = runTest {
        createTable(Users)

        // Insert initial item without status
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
        }

        // Update only if status attribute does not exist - should succeed
        val updated = Users.update(database, { Users.id eq "user#123" }) {
            it[status] = "active"
            it.condition { status.notExists() }
        }

        updated[Users.status].shouldBeEqualTo("active")
    }

    @Test
    fun `test conditional update with compound condition`() = runTest {
        createTable(Users)

        // Insert initial item
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
            it[status] = "active"
        }

        // Update with compound condition
        val updated = Users.update(database, { Users.id eq "user#123" }) {
            it[name] = "John Updated"
            it[version] = 2
            it.condition { (version eq 1) and (status eq "active") }
        }

        updated[Users.name].shouldBeEqualTo("John Updated")
        updated[Users.version].shouldBeEqualTo(2)
    }

    @Test
    fun `test conditional delete succeeds when condition met`() = runTest {
        createTable(Users)

        // Insert initial item
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[status] = "inactive"
        }

        // Delete only if status is inactive
        Users.delete(database, { Users.id eq "user#123" }) {
            it.condition { status eq "inactive" }
        }

        // Verify item was deleted
        val user = Users.get(database) { Users.id eq "user#123" }
        user shouldBeEqualTo null
    }

    @Test
    fun `test conditional delete fails when condition not met`() = runTest {
        createTable(Users)

        // Insert initial item
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[status] = "active"
        }

        // Try to delete only if status is inactive - should fail
        assertFailsWith<ConditionalCheckFailedException> {
            Users.delete(database, { Users.id eq "user#123" }) {
                it.condition { status eq "inactive" }
            }
        }

        // Verify item was not deleted
        val user = Users.get(database) { Users.id eq "user#123" }!!
        user[Users.name].shouldBeEqualTo("John")
    }

    @Test
    fun `test insert with condition using DSL`() = runTest {
        createTable(Users)

        // First insert should succeed
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
            it.condition { id.notExists() }
        }

        // Second insert with same ID should fail
        assertFailsWith<ConditionalCheckFailedException> {
            Users.insert(database) {
                it[id] = "user#123"
                it[name] = "Jane"
                it[version] = 1
                it.condition { id.notExists() }
            }
        }

        // Verify original was not overwritten
        val user = Users.get(database) { Users.id eq "user#123" }!!
        user[Users.name].shouldBeEqualTo("John")
    }

    @Test
    fun `test conditional update with attribute_not_exists OR comparison`() = runTest {
        createTable(Users)

        // Insert an item with an existing version (acts as a timestamp for last-writer-wins).
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 5
        }

        // A newer write (incoming version 10) should win: attribute_not_exists(version) OR version <= 10.
        val updated = Users.update(database, { Users.id eq "user#123" }) {
            it[name] = "John Newer"
            it[version] = 10
            it.condition { version.notExists() or (version le 10) }
        }
        updated[Users.name].shouldBeEqualTo("John Newer")
        updated[Users.version].shouldBeEqualTo(10)

        // A stale write (incoming version 7) should be rejected: 10 is not <= 7 and the attribute exists.
        assertFailsWith<ConditionalCheckFailedException> {
            Users.update(database, { Users.id eq "user#123" }) {
                it[name] = "John Stale"
                it[version] = 7
                it.condition { version.notExists() or (version le 7) }
            }
        }

        // Verify the stale write did not take effect.
        val user = Users.get(database) { Users.id eq "user#123" }!!
        user[Users.name].shouldBeEqualTo("John Newer")
        user[Users.version].shouldBeEqualTo(10)
    }

    @Test
    fun `test conditional insert with attribute_not_exists OR succeeds on first write`() = runTest {
        createTable(Users)

        // First write: item does not exist yet, so attribute_not_exists(version) is satisfied.
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 3
            it.condition { version.notExists() or (version le 3) }
        }

        val user = Users.get(database) { Users.id eq "user#123" }!!
        user[Users.version].shouldBeEqualTo(3)
    }

    @Test
    fun `test conditional update with neq operator`() = runTest {
        createTable(Users)

        // Insert initial item
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[status] = "active"
        }

        // Update only if status is not "deleted"
        val updated = Users.update(database, { Users.id eq "user#123" }) {
            it[name] = "John Updated"
            it.condition { status neq "deleted" }
        }

        updated[Users.name].shouldBeEqualTo("John Updated")
    }

    @Test
    fun `test conditional update with neq operator fails`() = runTest {
        createTable(Users)

        // Insert initial item with status "deleted"
        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[status] = "deleted"
        }

        // Try to update only if status is not "deleted" - should fail
        assertFailsWith<ConditionalCheckFailedException> {
            Users.update(database, { Users.id eq "user#123" }) {
                it[name] = "John Updated"
                it.condition { status neq "deleted" }
            }
        }
    }
}
