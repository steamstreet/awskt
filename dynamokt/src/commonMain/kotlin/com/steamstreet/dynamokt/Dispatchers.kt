package com.steamstreet.dynamokt

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher for blocking-ish I/O fan-out — currently the parallel-scan segments.
 *
 * `expect`/`actual` rather than a blanket `Dispatchers.Default`, and the difference is not
 * cosmetic. `Default` is sized to the CPU count, so collapsing onto it would cap a parallel scan at
 * roughly the number of cores **on the JVM too** — a silent throughput regression on the one
 * platform that was working, in exchange for portability it does not need. `Dispatchers.IO` exists
 * precisely because these coroutines spend their time waiting on the network, not computing.
 */
internal expect val ioDispatcher: CoroutineDispatcher
