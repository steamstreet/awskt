package com.steamstreet.collections

/**
 * Returns true if the iterable contains any item that matches the predicate
 */
public inline fun <T> Iterable<T>.contains(predicate: (T) -> Boolean): Boolean {
    return firstOrNull(predicate) != null
}

/**
 * Filter a list, updating the original list
 */
public fun <T> MutableList<T>.filterInPlace(predicate: (T) -> Boolean) {
    val iterator = iterator()
    while (iterator.hasNext()) {
        val item = iterator.next()
        if (!predicate(item)) {
            iterator.remove()
        }
    }
}

/**
 * Returns a list containing all elements that are not `null`.
 */
public fun <K, T : Any> Map<K, T?>.filterNotNullValues(): Map<K, T> = mapNotNull {
    it.value?.let { value -> it.key to value }
}.toMap()