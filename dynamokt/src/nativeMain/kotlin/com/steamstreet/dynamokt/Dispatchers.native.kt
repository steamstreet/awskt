package com.steamstreet.dynamokt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * **`Dispatchers.IO` does not exist on Kotlin/Native.** kotlinx-coroutines 1.10.2 declares it
 * `internal` outside the JVM, so the `expect`/`actual` in `Dispatchers.kt` — written to avoid
 * collapsing the JVM onto `Dispatchers.Default` — cannot give Native the same thing.
 *
 * `Dispatchers.Default` here is a deliberate starting point rather than a considered concurrency
 * policy, and it carries exactly the cost the common declaration warns about: it is sized to the
 * core count, so a parallel scan on Native runs about as many segments at once as the machine has
 * cores, however much of that time is spent waiting on the network. On a 2-vCPU Lambda that is two
 * segments in flight.
 *
 * The alternative, `newFixedThreadPoolContext`, is `@DelicateCoroutinesApi`, allocates real threads
 * eagerly and has no owner to close it. Picking a size for it is a decision that wants a measured
 * native Lambda to justify it, which is M7. Revisit there; nothing before M7 runs a parallel scan
 * on Native.
 */
internal actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default
