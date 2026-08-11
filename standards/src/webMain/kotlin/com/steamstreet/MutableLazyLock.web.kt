package com.steamstreet

/**
 * JS and Wasm run on a single thread, so there is nothing to exclude.
 */
internal actual class MutableLazyLock {
    actual fun <T> withLock(block: () -> T): T = block()
}
