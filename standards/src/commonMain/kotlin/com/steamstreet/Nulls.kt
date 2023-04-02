package com.steamstreet

public fun <A : Any, T> whenNotNull(a: A?, block: (A) -> T): T? {
    return if (a != null) block(a)
    else null
}

public fun <A : Any, B : Any, T> whenNotNull(a: A?, b: B?, block: (A, B) -> T): T? {
    return if (a != null && b != null) block(a, b)
    else null
}

public fun <A : Any, B : Any, C : Any, T> whenNotNull(a: A?, b: B?, c: C?, block: (A, B, C) -> T): T? {
    return if (a != null && b != null && c != null) block(a, b, c)
    else null
}
