package com.steamstreet

/**
 * **Kotlin/Native gets no mutual exclusion here, deliberately.** The Kotlin common and Native
 * standard libraries have no blocking lock, and the alternatives all cost more than the problem is
 * worth today: a `pthread_mutex_t` on the native heap would have to be freed by hand and every
 * `MutableLazy` is a long-lived singleton, a spin lock would burn a core for the length of an
 * initializer that in practice does network or reflection work, and `kotlinx.atomicfu` would add a
 * third-party dependency to the POM of the module every other module depends on.
 *
 * What this costs: two threads whose first read of the same delegate races can both run the
 * initializer, and the last writer wins. It is not a memory-safety problem — Kotlin/Native's
 * memory model makes reference field reads and writes atomic, so no torn value is observable — it
 * only means the initializer is not guaranteed to run exactly once. Every current caller passes
 * `Duration.INFINITE` and an idempotent initializer, so nothing depends on the stronger guarantee.
 *
 * If a native caller ever does need run-exactly-once, add `kotlinx.atomicfu` and use
 * `kotlinx.atomicfu.locks.SynchronizedObject` here; the rest of [MutableLazy] already assumes the
 * lock does its job.
 */
internal actual class MutableLazyLock {
    actual fun <T> withLock(block: () -> T): T = block()
}
