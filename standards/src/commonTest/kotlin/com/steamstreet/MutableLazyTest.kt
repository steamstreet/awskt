package com.steamstreet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class TestLazyClass {
    var x by mutableLazy {
        10
    }
}

/**
 * Counts how many times the initializer runs, which is the only way to tell a cached read from a
 * recomputed one.
 */
private class Counting(timeout: Duration) {
    var initializations = 0
        private set

    var value: Int by cached(timeout) {
        ++initializations
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

    /**
     * `mutableLazy` is `cached(Duration.INFINITE)`, and `Duration.INFINITE.inWholeMilliseconds` is
     * `Long.MAX_VALUE` — the value most likely to be mishandled by an expiry check that adds the
     * timeout to a timestamp instead of subtracting timestamps.
     */
    @Test
    fun infiniteTimeoutInitializesOnce() {
        val subject = Counting(Duration.INFINITE)

        repeat(5) { subject.value }

        assertEquals(1, subject.initializations)
        assertEquals(1, subject.value)
    }

    /**
     * A read taken well inside a finite timeout must serve the cached value. This is the case the
     * pre-3.0 implementation got backwards: it re-ran the initializer on every read until the
     * timeout expired, and then stopped re-running it.
     */
    @Test
    fun unexpiredFiniteTimeoutInitializesOnce() {
        val subject = Counting(1.hours)

        repeat(5) { subject.value }

        assertEquals(1, subject.initializations)
    }

    /**
     * The other side of the same check: a timeout that has always elapsed must recompute on every
     * read.
     */
    @Test
    fun expiredTimeoutRecomputes() {
        val subject = Counting(Duration.ZERO)

        // Each read is itself a recomputation, so the reads have to be asserted one at a time.
        assertEquals(1, subject.value)
        assertEquals(2, subject.value)
        assertEquals(3, subject.value)

        assertEquals(3, subject.initializations)
    }

    /**
     * Assigning a value pins it — the timeout stops applying, however stale the value gets.
     */
    @Test
    fun explicitValueSurvivesExpiry() {
        val subject = Counting(Duration.ZERO)
        subject.value = 99

        repeat(3) { assertEquals(99, subject.value) }

        assertEquals(0, subject.initializations)
    }
}
