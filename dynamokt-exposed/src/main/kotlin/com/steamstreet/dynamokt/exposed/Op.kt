package com.steamstreet.dynamokt.exposed

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
     * Not equal operator
     */
    public infix fun <T> Column<T>.neq(value: T): Op<Boolean> = NeOp(this, value)

    /**
     * Check if attribute exists
     */
    public fun Column<*>.exists(): Op<Boolean> = AttributeExistsOp(this)

    /**
     * Check if attribute does not exist
     */
    public fun Column<*>.notExists(): Op<Boolean> = AttributeNotExistsOp(this)
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

/**
 * Extract all conditions from a where clause operation.
 * Note: This is primarily used for key condition extraction for queries,
 * so attribute existence operations and neq are not included.
 */
internal fun extractConditions(op: Op<Boolean>): List<ColumnCondition> {
    val conditions = mutableListOf<ColumnCondition>()

    fun collect(operation: Op<Boolean>) {
        when (operation) {
            is EqOp<*> -> conditions.add(ColumnCondition.Eq(operation.column, operation.value))
            is GtOp<*> -> conditions.add(ColumnCondition.Gt(operation.column, operation.value))
            is LtOp<*> -> conditions.add(ColumnCondition.Lt(operation.column, operation.value))
            is GeOp<*> -> conditions.add(ColumnCondition.Ge(operation.column, operation.value))
            is LeOp<*> -> conditions.add(ColumnCondition.Le(operation.column, operation.value))
            is BetweenOp<*> -> conditions.add(ColumnCondition.Between(operation.column, operation.from, operation.to))
            is BeginsWithOp -> conditions.add(ColumnCondition.BeginsWith(operation.column, operation.prefix))
            is AndOp -> {
                collect(operation.left)
                collect(operation.right)
            }
            // These are used for condition expressions, not key/filter conditions
            is NeOp<*> -> { /* Not used for key conditions */ }
            is AttributeExistsOp -> { /* Not used for key conditions */ }
            is AttributeNotExistsOp -> { /* Not used for key conditions */ }
        }
    }

    collect(op)
    return conditions
}

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
internal fun extractAllConditionColumns(op: Op<Boolean>): Set<Column<*>> {
    return extractConditions(op).map {
        when (it) {
            is ColumnCondition.Eq -> it.column
            is ColumnCondition.Gt -> it.column
            is ColumnCondition.Lt -> it.column
            is ColumnCondition.Ge -> it.column
            is ColumnCondition.Le -> it.column
            is ColumnCondition.Between -> it.column
            is ColumnCondition.BeginsWith -> it.column
        }
    }.toSet()
}
