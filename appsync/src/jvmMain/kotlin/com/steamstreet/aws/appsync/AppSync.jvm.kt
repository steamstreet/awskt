package com.steamstreet.aws.appsync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/**
 * Get the AppSyncContext from the InputStream of a Lambda.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun InputStream.appSyncContext(): AppSyncContext =
    appSyncParser.decodeFromStream(AppSyncContext.serializer(), this)
