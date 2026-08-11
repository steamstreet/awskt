package com.steamstreet

/**
 * The mutual exclusion [MutableLazy] uses to keep concurrent first reads from each running the
 * initializer.
 *
 * This is a platform concern rather than a common one because the Kotlin common standard library
 * has no lock. Only the JVM actual takes one; see the individual actuals for what each platform
 * guarantees.
 */
internal expect class MutableLazyLock() {
    fun <T> withLock(block: () -> T): T
}
