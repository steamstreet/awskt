@file:OptIn(ExperimentalTime::class)

package com.steamstreet

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

public actual fun epochMillis(): Long {
    return Clock.System.now().toEpochMilliseconds()
}
