package com.steamstreet.aws.lambda

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher to use for handler work that may block — a network call per record, a database
 * round trip, anything where the batch is worth running concurrently.
 *
 * This exists because `Dispatchers.IO` cannot be named from `commonMain`: as of coroutines 1.10.2 it
 * is public on the JVM and `internal` on Kotlin/Native, so a shared batch-processing loop that wants
 * it has to reach it through an `expect`.
 *
 * On the JVM this is `Dispatchers.IO`, unchanged from what the handler base classes have always
 * used — a large pool sized for blocking work, which is the right shape for a Lambda fanning a batch
 * of records out to a downstream service.
 *
 * On Native it is `Dispatchers.Default`. That is a real difference in degree of parallelism, not
 * just a rename, and it is the honest mapping: a Kotlin/Native Lambda has no equivalent elastic
 * blocking pool to offer. A handler that fans out a large batch will see less concurrency there than
 * on the JVM.
 */
public expect val lambdaIODispatcher: CoroutineDispatcher
