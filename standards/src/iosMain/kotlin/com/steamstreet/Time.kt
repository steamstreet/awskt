@file:OptIn(ExperimentalTime::class)

package com.steamstreet

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

public actual fun epochMillis(): Long {
    // this uses the Kotlin date time library, which we are trying
    // to avoid, but it seems to be the easiest way for iOS.
    return Clock.System.now().toEpochMilliseconds()
}
