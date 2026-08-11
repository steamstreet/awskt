package com.steamstreet

import kotlin.time.Clock

/**
 * `Clock` is `kotlin.time.Clock` from the standard library, not `kotlinx.datetime.Clock` — this
 * costs the module no dependency. The JVM and JS actuals reach for the platform clock directly
 * only because they predate it.
 */
public actual fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
