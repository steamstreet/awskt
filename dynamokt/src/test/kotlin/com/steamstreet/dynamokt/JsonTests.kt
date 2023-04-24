package com.steamstreet.dynamokt

import org.amshove.kluent.*
import kotlin.test.Test

/**
 * Tests for converting to and from json strings for serialization tasks that are Kotlin optimized.
 */
class JsonTests {
    @Test
    fun simple() {
        val jsonString = mapOf(
            "name" to "Jon".attributeValue()
        ).toJsonItemString()

        // just a dumb check to make sure something got written
        jsonString.shouldContain("Jon")

        with(jsonString.fromJsonToItem()) {
            keys.shouldHaveSingleItem().shouldBeEqualTo("name")

            this["name"].shouldNotBeNull().s().shouldBeEqualTo("Jon")
        }
    }

    @Test
    fun listsVsSets() {
        val jsonString = mapOf(
            "name-set" to setOf("Jon", "Steve", "Joe").attributeValue(),
            "name-list" to listOf("Joe", "Gord", "Frank").map { it.attributeValue() }.attributeValue()
        ).toJsonItemString()

        // make sure there is an 'ss' and 'l' attribute
        jsonString.shouldContain("\"ss\"")
        jsonString.shouldContain("\"l\"")

        with(jsonString.fromJsonToItem()) {
            with(this["name-set"].shouldNotBeNull()) {
                hasSs().shouldBeTrue()
                ss().shouldHaveSize(3).shouldContainAll(listOf("Jon", "Steve", "Joe"))
            }
            with(this["name-list"].shouldNotBeNull()) {
                hasL().shouldBeTrue()
            }
        }
    }
}