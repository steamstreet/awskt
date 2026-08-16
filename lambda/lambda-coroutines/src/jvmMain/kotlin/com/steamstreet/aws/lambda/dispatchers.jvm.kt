package com.steamstreet.aws.lambda

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** `Dispatchers.IO`, which is what the JVM handler base classes have always used. */
public actual val lambdaIODispatcher: CoroutineDispatcher = Dispatchers.IO
