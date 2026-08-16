package com.steamstreet.aws.lambda

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * `Dispatchers.Default`, because Kotlin/Native has no public elastic pool for blocking work —
 * `Dispatchers.IO` is `internal` there as of coroutines 1.10.2.
 */
public actual val lambdaIODispatcher: CoroutineDispatcher = Dispatchers.Default
