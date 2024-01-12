package com.steamstreet

import kotlin.test.Test
import kotlin.test.assertEquals

class TestLazyClass {
    var x by mutableLazy {
        10
    }
}

/**
 * Test for the mutable lazy functionality.
 */
class MutableLazyTest {
    @Test
    fun basics() {
        val value1 = TestLazyClass()
        assertEquals(value1.x, 10)
        value1.x = 20

        assertEquals(value1.x, 20)

        val value2 = TestLazyClass()
        value2.x = 30
        assertEquals(value2.x, 30)
    }
}