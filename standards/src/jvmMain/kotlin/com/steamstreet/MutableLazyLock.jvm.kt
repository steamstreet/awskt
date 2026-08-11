package com.steamstreet

/**
 * A real monitor, so a `MutableLazy` read from several threads at once runs the initializer exactly
 * once. Callers depend on this: the initializers in `env` and `events` construct AWS SDK clients by
 * reflection, and running one twice would leak a client.
 */
internal actual class MutableLazyLock {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
