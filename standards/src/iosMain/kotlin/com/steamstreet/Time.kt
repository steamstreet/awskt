package com.steamstreet

import kotlinx.datetime.Clock

public actual fun epochMillis(): Long {
    // this uses the Kotlin date time library, which we are trying
    // to avoid, but it seems to be the easiest way for iOS.
    return Clock.System.now().toEpochMilliseconds()
}
