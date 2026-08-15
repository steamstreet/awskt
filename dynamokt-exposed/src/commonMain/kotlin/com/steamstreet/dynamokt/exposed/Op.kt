package com.steamstreet.dynamokt.exposed

import com.steamstreet.dynamokt.AttributeValue

/**
 * Base class for SQL-like operations/expressions.
 * Similar to Exposed's Op class.
 */
public sealed class Op<T>

/**
 * Equality operation
 */
public class EqOp<T>(public val column: Column<T>, public val value: T) : Op<Boolean>()

/**
 * Greater than operation
 */
public class GtOp<T : Comparable<T>>(public val column: Column<T>, public val value: T) : Op<Boolean>()

/**
 * Less than operation
 */
public class LtOp<T : Comparable<T>>(public val column: Column<T>, public val value: T) : Op<Boolean>()

/**
 * Greater than or equal operation
 */
public class GeOp<T : Comparable<T>>(public val column: Column<T>, public val value: T) : Op<Boolean>()

/**
 * Less than or equal operation
 */
public class LeOp<T : Comparable<T>>(public val column: Column<T>, public val value: T) : Op<Boolean>()

/**
 * Between operation (inclusive)
 */
public class BetweenOp<T : Comparable<T>>(
    public val column: Column<T>,
    public val from: T,
    public val to: T
) : Op<Boolean>()

/**
 * Begins with operation (for strings)
 */
public class BeginsWithOp(public val column: Column<String>, public val prefix: String) : Op<Boolean>()

/**
 * AND operation to combine multiple conditions
 */
public class AndOp(public val left: Op<Boolean>, public val right: Op<Boolean>) : Op<Boolean>()

/**
 * OR operation to combine multiple conditions.
 * Primarily useful for conditional writes, e.g. `attribute_not_exists(ts) OR ts <= :incoming`.
 */
public class OrOp(public val left: Op<Boolean>, public val right: Op<Boolean>) : Op<Boolean>()

/**
 * Not equal operation
 */
public class NeOp<T>(public val column: Column<T>, public val value: T) : Op<Boolean>()

/**
 * Attribute exists check (for conditional writes)
 */
public class AttributeExistsOp(public val column: Column<*>) : Op<Boolean>()

/**
 * Attribute not exists check (for conditional writes)
 */
public class AttributeNotExistsOp(public val column: Column<*>) : Op<Boolean>()

/**
 * Negation of another operation, rendered as `NOT (...)`.
 * Never usable as a key condition; it can only appear in filter or condition expressions.
 */
public class NotOp(public val op: Op<Boolean>) : Op<Boolean>()

/**
 * Membership test, rendered as `#a IN (:v1, :v2, ...)`.
 * DynamoDB caps the operand list at 100 entries; never usable as a key condition.
 */
public class InListOp<T>(public val column: Column<T>, public val values: List<T>) : Op<Boolean>()

/**
 * `contains(#a, :v)` - substring containment for string attributes, element
 * membership for list attributes.
 *
 * The comparand is already converted to an [AttributeValue] because the two
 * builder overloads convert through different columns: the string form through
 * the attribute's own column, the list form through its element column.
 * Never usable as a key condition.
 */
public class ContainsOp(public val column: Column<*>, public val value: AttributeValue) : Op<Boolean>()

/**
 * Batch keys operation - signals that the query should use BatchGetItem
 * with the specified list of primary key values.
 */
public class KeysOp(public val keys: List<Pair<Any, Any?>>) : Op<Boolean>()

/**
 * Represents an arithmetic increment expression for update operations.
 * Used to support Exposed-style syntax: it[column] = column + 1
 */
public class IncrementExpr<T : Number>(
    public val column: Column<T>,
    public val amount: T
)

/**
 * Plus operator for Int columns to create increment expressions.
 * Enables syntax: it[count] = count + 1
 */
public operator fun Column<Int>.plus(amount: Int): IncrementExpr<Int> = IncrementExpr(this, amount)

/**
 * Plus operator for Long columns to create increment expressions.
 * Enables syntax: it[count] = count + 1L
 */
public operator fun Column<Long>.plus(amount: Long): IncrementExpr<Long> = IncrementExpr(this, amount)

/**
 * Minus operator for Int columns to create decrement expressions.
 * Enables syntax: it[count] = count - 1
 */
public operator fun Column<Int>.minus(amount: Int): IncrementExpr<Int> = IncrementExpr(this, -amount)

/**
 * Minus operator for Long columns to create decrement expressions.
 * Enables syntax: it[count] = count - 1L
 */
public operator fun Column<Long>.minus(amount: Long): IncrementExpr<Long> = IncrementExpr(this, -amount)

/**
 * Expression builder for where clauses.
 * Provides context for building conditional expressions.
 */
public open class SqlExpressionBuilder {
    /**
     * Equality operator for columns
     */
    public infix fun <T> Column<T>.eq(value: T): Op<Boolean> = EqOp(this, value)

    /**
     * Greater than operator
     */
    public infix fun <T : Comparable<T>> Column<T>.gt(value: T): Op<Boolean> = GtOp(this, value)

    /**
     * Less than operator
     */
    public infix fun <T : Comparable<T>> Column<T>.lt(value: T): Op<Boolean> = LtOp(this, value)

    /**
     * Greater than or equal operator
     */
    public infix fun <T : Comparable<T>> Column<T>.ge(value: T): Op<Boolean> = GeOp(this, value)

    /**
     * Less than or equal operator
     */
    public infix fun <T : Comparable<T>> Column<T>.le(value: T): Op<Boolean> = LeOp(this, value)

    /**
     * Between operator (inclusive)
     */
    public fun <T : Comparable<T>> Column<T>.between(from: T, to: T): Op<Boolean> = BetweenOp(this, from, to)

    /**
     * Begins with operator (for strings)
     */
    public infix fun Column<String>.beginsWith(prefix: String): Op<Boolean> = BeginsWithOp(this, prefix)

    /**
     * AND operator for combining conditions
     */
    public infix fun Op<Boolean>.and(other: Op<Boolean>): Op<Boolean> = AndOp(this, other)

    /**
     * OR operator for combining conditions.
     * Useful for conditional writes such as `attribute_not_exists(ts) OR ts <= incoming`.
     */
    public infix fun Op<Boolean>.or(other: Op<Boolean>): Op<Boolean> = OrOp(this, other)

    /**
     * Not equal operator
     */
    public infix fun <T> Column<T>.neq(value: T): Op<Boolean> = NeOp(this, value)

    /**
     * Negate a condition. Only valid in filter and condition expressions, never
     * as a key condition.
     */
    public fun not(op: Op<Boolean>): Op<Boolean> = NotOp(op)

    /**
     * Membership test against a literal list, rendered as `#a IN (...)`.
     * DynamoDB accepts between 1 and 100 operands.
     */
    public infix fun <T> Column<T>.inList(values: List<T>): Op<Boolean> {
        require(values.isNotEmpty()) { "inList requires at least one value" }
        require(values.size <= 100) { "inList supports at most 100 values, got ${values.size}" }
        return InListOp(this, values)
    }

    /**
     * Substring containment for string attributes.
     */
    public infix fun Column<String>.contains(substring: String): Op<Boolean> =
        ContainsOp(this, AttributeValue.S(substring))

    /**
     * Element membership for list attributes. The element is converted through
     * the list's element column, so it is encoded exactly as it was stored.
     */
    public infix fun <T> Column<List<T>>.contains(element: T): Op<Boolean> {
        @Suppress("UNCHECKED_CAST")
        val elements = (this as? ListColumn<T>)?.elementColumn
        requireNotNull(elements) {
            "contains(element) requires a column declared with Table.list(...); ${this.name} is not one"
        }
        return ContainsOp(this, elements.toAttributeValue(element))
    }

    /**
     * Check if attribute exists
     */
    public fun Column<*>.exists(): Op<Boolean> = AttributeExistsOp(this)

    /**
     * Check if attribute does not exist
     */
    public fun Column<*>.notExists(): Op<Boolean> = AttributeNotExistsOp(this)

    /**
     * Specify exact keys to retrieve using BatchGetItem.
     * Each pair is (partitionKey, sortKey) - sortKey is null for tables without one.
     *
     * Example:
     * ```
     * Users.selectAll(database).where { keys(listOf("user#1" to null, "user#2" to null)) }
     * ```
     */
    public fun keys(keys: List<Pair<Any, Any?>>): Op<Boolean> = KeysOp(keys)
}

/**
 * Extract key values from a where clause operation.
 * Returns a map of columns to their values (only for equality operations).
 */
internal fun extractKeyValues(op: Op<Boolean>): Map<Column<*>, Any?> {
    val values = mutableMapOf<Column<*>, Any?>()

    fun collect(operation: Op<Boolean>) {
        when (operation) {
            is EqOp<*> -> {
                values[operation.column] = operation.value
            }
            is AndOp -> {
                collect(operation.left)
                collect(operation.right)
            }
            else -> { /* ignore non-eq operations for key extraction */ }
        }
    }

    collect(op)
    return values
}

/**
 * Represents parsed condition information for a column
 */
internal sealed class ColumnCondition {
    public data class Eq(val column: Column<*>, val value: Any?) : ColumnCondition()
    public data class Gt(val column: Column<*>, val value: Any?) : ColumnCondition()
    public data class Lt(val column: Column<*>, val value: Any?) : ColumnCondition()
    public data class Ge(val column: Column<*>, val value: Any?) : ColumnCondition()
    public data class Le(val column: Column<*>, val value: Any?) : ColumnCondition()
    public data class Between(val column: Column<*>, val from: Any?, val to: Any?) : ColumnCondition()
    public data class BeginsWith(val column: Column<*>, val prefix: String) : ColumnCondition()
}

/** The column an already-parsed condition applies to. */
internal val ColumnCondition.conditionColumn: Column<*>
    get() = when (this) {
        is ColumnCondition.Eq -> this.column
        is ColumnCondition.Gt -> this.column
        is ColumnCondition.Lt -> this.column
        is ColumnCondition.Ge -> this.column
        is ColumnCondition.Le -> this.column
        is ColumnCondition.Between -> this.column
        is ColumnCondition.BeginsWith -> this.column
    }

/**
 * Flatten the top-level AND spine into its conjuncts. Everything else - `OR`
 * subtrees, `NOT`, single comparisons - comes back as a single element, because
 * only a top-level conjunct can be split between a key condition and a filter.
 */
internal fun flattenAnd(op: Op<Boolean>): List<Op<Boolean>> = when (op) {
    is AndOp -> flattenAnd(op.left) + flattenAnd(op.right)
    else -> listOf(op)
}

/**
 * The [ColumnCondition] form of an op, or null when the op can never be a
 * DynamoDB key condition (`neq`, `IN`, `contains`, `NOT`, existence checks,
 * `OR` subtrees, `keys()`).
 */
internal fun keyConditionOrNull(op: Op<Boolean>): ColumnCondition? = when (op) {
    is EqOp<*> -> ColumnCondition.Eq(op.column, op.value)
    is GtOp<*> -> ColumnCondition.Gt(op.column, op.value)
    is LtOp<*> -> ColumnCondition.Lt(op.column, op.value)
    is GeOp<*> -> ColumnCondition.Ge(op.column, op.value)
    is LeOp<*> -> ColumnCondition.Le(op.column, op.value)
    is BetweenOp<*> -> ColumnCondition.Between(op.column, op.from, op.to)
    is BeginsWithOp -> ColumnCondition.BeginsWith(op.column, op.prefix)
    else -> null
}

/**
 * Extract all conditions from a where clause operation.
 * Note: This is primarily used for key condition extraction for queries,
 * so attribute existence operations and neq are not included.
 */
internal fun extractConditions(op: Op<Boolean>): List<ColumnCondition> =
    flattenAnd(op).mapNotNull { keyConditionOrNull(it) }

/**
 * Get all columns that have equality conditions
 */
internal fun extractEqColumns(op: Op<Boolean>): Set<Column<*>> {
    return extractConditions(op)
        .filterIsInstance<ColumnCondition.Eq>()
        .map { it.column }
        .toSet()
}

/**
 * Get all columns involved in any condition
 */
internal fun extractAllConditionColumns(op: Op<Boolean>): Set<Column<*>> =
    extractConditions(op).map { it.conditionColumn }.toSet()

/**
 * Every column referenced anywhere in the tree, including inside `OR`, `NOT`
 * and the operators that can only ever be filters. Used to report unusable
 * queries and to reject filters over the queried index's own key attributes.
 */
internal fun referencedColumns(op: Op<Boolean>): Set<Column<*>> {
    val columns = mutableSetOf<Column<*>>()

    fun collect(operation: Op<Boolean>) {
        when (operation) {
            is EqOp<*> -> columns.add(operation.column)
            is NeOp<*> -> columns.add(operation.column)
            is GtOp<*> -> columns.add(operation.column)
            is LtOp<*> -> columns.add(operation.column)
            is GeOp<*> -> columns.add(operation.column)
            is LeOp<*> -> columns.add(operation.column)
            is BetweenOp<*> -> columns.add(operation.column)
            is BeginsWithOp -> columns.add(operation.column)
            is InListOp<*> -> columns.add(operation.column)
            is ContainsOp -> columns.add(operation.column)
            is AttributeExistsOp -> columns.add(operation.column)
            is AttributeNotExistsOp -> columns.add(operation.column)
            is NotOp -> collect(operation.op)
            is AndOp -> {
                collect(operation.left)
                collect(operation.right)
            }
            is OrOp -> {
                collect(operation.left)
                collect(operation.right)
            }
            is KeysOp -> { /* references the key columns implicitly, never by column */ }
        }
    }

    collect(op)
    return columns
}

/**
 * True when a `keys()` op appears anywhere in the tree. `keys()` routes to
 * BatchGetItem and cannot be combined with anything else.
 */
internal fun containsKeysOp(op: Op<Boolean>): Boolean = when (op) {
    is KeysOp -> true
    is AndOp -> containsKeysOp(op.left) || containsKeysOp(op.right)
    is OrOp -> containsKeysOp(op.left) || containsKeysOp(op.right)
    is NotOp -> containsKeysOp(op.op)
    else -> false
}
