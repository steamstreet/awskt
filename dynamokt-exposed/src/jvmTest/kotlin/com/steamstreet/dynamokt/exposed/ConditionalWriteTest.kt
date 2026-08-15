package com.steamstreet.dynamokt.exposed

import com.steamstreet.awskt.dynamodb.ConditionalCheckFailedException
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    // -- Non-key conditions in the where clause --------------------------------------------------
    //
    // `{ id eq x and version eq 1 }` reads as optimistic locking, so it has to behave that way:
    // the non-key conjuncts become the operation's ConditionExpression rather than being dropped.

    @Test
    fun `test update where clause condition beyond the key is enforced`() = runTest {
        createTable(Users)

        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
        }

        // Matching version: the update goes through.
        val updated = Users.update(database, { (Users.id eq "user#123") and (Users.version eq 1) }) {
            it[name] = "John Updated"
            it[version] = 2
        }
        updated[Users.name].shouldBeEqualTo("John Updated")
        updated[Users.version].shouldBeEqualTo(2)

        // Stale version: the same where clause must now fail rather than write unconditionally.
        assertFailsWith<ConditionalCheckFailedException> {
            Users.update(database, { (Users.id eq "user#123") and (Users.version eq 1) }) {
                it[name] = "Stale Writer"
                it[version] = 3
            }
        }

        val user = Users.get(database) { Users.id eq "user#123" }!!
        user[Users.name].shouldBeEqualTo("John Updated")
        user[Users.version].shouldBeEqualTo(2)
    }

    @Test
    fun `test update where clause condition is ANDed with an explicit condition`() = runTest {
        createTable(Users)

        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
            it[status] = "active"
        }

        // Where residual holds, explicit condition does not: the whole thing must fail.
        assertFailsWith<ConditionalCheckFailedException> {
            Users.update(database, { (Users.id eq "user#123") and (Users.version eq 1) }) {
                it[name] = "Nope"
                it.condition { status eq "inactive" }
            }
        }

        // Both hold: it succeeds.
        val updated = Users.update(database, { (Users.id eq "user#123") and (Users.version eq 1) }) {
            it[name] = "John Updated"
            it.condition { status eq "active" }
        }
        updated[Users.name].shouldBeEqualTo("John Updated")
    }

    @Test
    fun `test delete where clause condition beyond the key is enforced`() = runTest {
        createTable(Users)

        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
        }

        assertFailsWith<ConditionalCheckFailedException> {
            Users.delete(database) { (Users.id eq "user#123") and (Users.version eq 9) }
        }
        // The item is still there - the version condition rejected the delete.
        Users.get(database) { Users.id eq "user#123" }!![Users.name].shouldBeEqualTo("John")

        val deleted = Users.delete(database) { (Users.id eq "user#123") and (Users.version eq 1) }
        deleted.shouldBeEqualTo(true)
        Users.get(database) { Users.id eq "user#123" } shouldBeEqualTo null
    }

    // -- Update is an upsert unless told otherwise -----------------------------------------------

    @Test
    fun `test update creates a missing item by default`() = runTest {
        createTable(Users)

        // DynamoDB's UpdateItem is an upsert. This documents the (deliberately unchanged) default.
        Users.update(database, { Users.id eq "user#ghost" }) {
            it[name] = "Created By Update"
        }

        Users.get(database) { Users.id eq "user#ghost" }!![Users.name]
            .shouldBeEqualTo("Created By Update")
    }

    @Test
    fun `test ifExists prevents an update from creating a missing item`() = runTest {
        createTable(Users)

        assertFailsWith<ConditionalCheckFailedException> {
            Users.update(database, { Users.id eq "user#ghost" }) {
                it[name] = "Created By Update"
                it.ifExists()
            }
        }

        Users.get(database) { Users.id eq "user#ghost" } shouldBeEqualTo null
    }

    @Test
    fun `test ifExists allows an update to an existing item`() = runTest {
        createTable(Users)

        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
        }

        val updated = Users.update(database, { Users.id eq "user#123" }) {
            it[name] = "John Updated"
            it.ifExists()
        }
        updated[Users.name].shouldBeEqualTo("John Updated")
    }

    @Test
    fun `test ifExists combines with an explicit condition`() = runTest {
        createTable(Users)

        Users.insert(database) {
            it[id] = "user#123"
            it[name] = "John"
            it[version] = 1
        }

        // Order must not matter: condition {} set first, then ifExists().
        assertFailsWith<ConditionalCheckFailedException> {
            Users.update(database, { Users.id eq "user#123" }) {
                it[name] = "Nope"
                it.condition { version eq 7 }
                it.ifExists()
            }
        }

        // ... and the other way round.
        assertFailsWith<ConditionalCheckFailedException> {
            Users.update(database, { Users.id eq "user#123" }) {
                it[name] = "Nope"
                it.ifExists()
                it.condition { version eq 7 }
            }
        }

        Users.get(database) { Users.id eq "user#123" }!![Users.name].shouldBeEqualTo("John")
    }

    // -- Update expression overlap guard ---------------------------------------------------------

    @Test
    fun `test setting and incrementing the same column is rejected`() = runTest {
        createTable(Users)

        val failure = assertFailsWith<IllegalArgumentException> {
            Users.update(database, { Users.id eq "user#123" }) {
                it[version] = 5
                it.increment(version, 1)
            }
        }
        assertTrue(failure.message!!.contains("version"), "message should name the column: ${failure.message}")
    }

    @Test
    fun `test setting and removing the same column is rejected`() = runTest {
        createTable(Users)

        val failure = assertFailsWith<IllegalArgumentException> {
            Users.update(database, { Users.id eq "user#123" }) {
                it[status] = "active"
                it.remove(status)
            }
        }
        assertTrue(failure.message!!.contains("status"), "message should name the column: ${failure.message}")
    }
}
