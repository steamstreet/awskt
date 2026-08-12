package com.steamstreet.dynamokt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `commonTest` coverage for the pure-logic parts of `dynamokt`.
 *
 * Before this existed, `dynamokt` had only `jvmTest` — every one of its native targets ran **zero**
 * tests while still reporting a successful build. Everything here is deliberately free of AWS,
 * LocalStack and Testcontainers so it runs on `macosArm64` and `linuxX64` as well as the JVM.
 *
 * The binary cases are the point of the file. Per the plan's Risk 11, a naive `data class` over
 * `ByteArray` gives reference equality, which silently breaks [findDifferences] — and a repo-wide
 * grep confirmed that no existing test touched B, BS, NULL or NS at all.
 */
class AttributeDiffTest {

    @Test
    fun reportsNoDifferenceBetweenIdenticalRecords() {
        val record = mapOf(
            "name" to AttributeValue.S("value"),
            "count" to AttributeValue.N("3"),
        )

        assertEquals(emptyList(), findDifferences(record, record.toMap()))
    }

    @Test
    fun reportsChangedAddedAndRemovedKeys() {
        val before = mapOf(
            "kept" to AttributeValue.S("same"),
            "changed" to AttributeValue.S("before"),
            "removed" to AttributeValue.S("gone"),
        )
        val after = mapOf(
            "kept" to AttributeValue.S("same"),
            "changed" to AttributeValue.S("after"),
            "added" to AttributeValue.S("new"),
        )

        assertEquals(
            setOf("changed", "removed", "added"),
            findDifferences(before, after).toSet()
        )
    }

    /**
     * Binary attributes with equal *content* must compare equal. With reference equality every read
     * of an unchanged binary attribute reports a spurious difference — which, for callers using
     * this to decide whether to write, means writing on every pass.
     */
    @Test
    fun treatsBinaryAttributesWithEqualContentAsUnchanged() {
        val before = mapOf("blob" to AttributeValue.B(byteArrayOf(1, 2, 3)))
        val after = mapOf("blob" to AttributeValue.B(byteArrayOf(1, 2, 3)))

        assertEquals(emptyList(), findDifferences(before, after))
    }

    @Test
    fun detectsAChangedBinaryAttribute() {
        val before = mapOf("blob" to AttributeValue.B(byteArrayOf(1, 2, 3)))
        val after = mapOf("blob" to AttributeValue.B(byteArrayOf(1, 2, 4)))

        assertEquals(listOf("blob"), findDifferences(before, after))
    }

    /** Binary *sets* are unordered on the wire, so element order must not register as a change. */
    @Test
    fun treatsBinarySetsAsUnordered() {
        val before = mapOf("blobs" to AttributeValue.Bs(listOf(byteArrayOf(1), byteArrayOf(2))))
        val after = mapOf("blobs" to AttributeValue.Bs(listOf(byteArrayOf(2), byteArrayOf(1))))

        assertEquals(emptyList(), findDifferences(before, after))
    }

    @Test
    fun detectsAChangedBinarySet() {
        val before = mapOf("blobs" to AttributeValue.Bs(listOf(byteArrayOf(1), byteArrayOf(2))))
        val after = mapOf("blobs" to AttributeValue.Bs(listOf(byteArrayOf(1), byteArrayOf(3))))

        assertEquals(listOf("blobs"), findDifferences(before, after))
    }

    @Test
    fun treatsStringSetsAsUnordered() {
        val before = mapOf("tags" to AttributeValue.Ss(listOf("a", "b")))
        val after = mapOf("tags" to AttributeValue.Ss(listOf("b", "a")))

        assertEquals(emptyList(), findDifferences(before, after))
    }

    @Test
    fun distinguishesNullFromAbsent() {
        val withNull = mapOf("maybe" to AttributeValue.Null())
        val without = emptyMap<String, AttributeValue>()

        assertEquals(listOf("maybe"), findDifferences(withNull, without))
    }

    @Test
    fun treatsANullRecordAsEveryKeyDiffering() {
        val record = mapOf("a" to AttributeValue.S("1"), "b" to AttributeValue.S("2"))

        assertEquals(setOf("a", "b"), findDifferences(record, null).toSet())
        assertEquals(setOf("a", "b"), findDifferences(null, record).toSet())
        assertEquals(emptyList(), findDifferences(null, null))
    }

    /**
     * `N` is a 38-digit decimal on the wire. Comparing through a `Double` would make these two
     * values equal, which is the precision bug the plan flags as sitting in the 2.3.x prior art.
     */
    @Test
    fun detectsANumericChangeBeyondDoublePrecision() {
        val before = mapOf("n" to AttributeValue.N("12345678901234567890.0987654321"))
        val after = mapOf("n" to AttributeValue.N("12345678901234567890.0987654322"))

        assertEquals(listOf("n"), findDifferences(before, after))
    }

    @Test
    fun diffsNestedMapsByPath() {
        val before = mapOf("outer" to AttributeValue.M(mapOf("inner" to AttributeValue.S("before"))))
        val after = mapOf("outer" to AttributeValue.M(mapOf("inner" to AttributeValue.S("after"))))

        assertTrue(diff(before, after).contains("outer"), "expected the changed key to be reported")
    }
}
