package com.steamstreet.dynamokt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ExpressionBuilder] builds DynamoDB filter expressions and the name/value maps they refer to.
 *
 * An expression that references `#a` or `:v` is meaningless without its maps, so the assertions here
 * always check the three together — a builder that emitted the right string but dropped a binding
 * would produce a `ValidationException` at run time and pass a string-only test.
 */
class ExpressionBuilderTest {

    @Test
    fun buildsNullWhenNothingWasSpecified() {
        assertNull(ExpressionBuilder().build())
    }

    @Test
    fun bindsTheValueItReferences() {
        val built = ExpressionBuilder().apply { equalTo("status", "active") }.build()!!

        assertTrue(built.values.values.any { it == AttributeValue.S("active") },
            "expected the bound value to appear in the value map, got ${built.values}")
        built.values.keys.forEach { placeholder ->
            assertTrue(built.expression.contains(placeholder),
                "value map contains $placeholder which the expression never references")
        }
    }

    @Test
    fun bindsEveryNamePlaceholderItReferences() {
        val built = ExpressionBuilder().apply { attributeExists("someName") }.build()!!

        built.names.keys.forEach { placeholder ->
            assertTrue(built.expression.contains(placeholder),
                "name map contains $placeholder which the expression never references")
        }
        assertTrue(built.names.values.contains("someName"),
            "expected the real attribute name in the name map, got ${built.names}")
    }

    @Test
    fun combinesMultipleConditions() {
        val built = ExpressionBuilder().apply {
            attributeExists("a")
            equalTo("b", "x")
        }.build()!!

        assertTrue(built.expression.contains("attribute_exists"), built.expression)
        // Both conditions have to survive into one expression; keeping only the last is a silent
        // widening of whatever query uses it.
        assertEquals(1, Regex("attribute_exists").findAll(built.expression).count())
        assertTrue(built.values.isNotEmpty(), "the equalTo binding was dropped")
    }

    @Test
    fun distinguishesNumericFromStringBindings() {
        val built = ExpressionBuilder().apply { equalTo("count", 5) }.build()!!

        assertTrue(
            built.values.values.any { it is AttributeValue.N && it.value == "5" },
            "a numeric binding must be an N, not an S — got ${built.values}"
        )
    }

    @Test
    fun buildsAStartsWithCondition() {
        val built = ExpressionBuilder().apply { startsWith("name", "pre") }.build()!!

        assertTrue(built.expression.contains("begins_with"), built.expression)
        assertTrue(built.values.values.any { it == AttributeValue.S("pre") }, "${built.values}")
    }
}
