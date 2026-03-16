package com.steamstreet.aws.lambda

import com.steamstreet.env.Env
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Common context interface for Lambda invocations, abstracting over
 * the JVM AWS Context and Native Runtime API headers.
 */
public interface LambdaContext {
    public val requestId: String
    public val functionName: String
    public val remainingTimeInMillis: Int
}

@OptIn(ExperimentalSerializationApi::class)
public val lambdaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

public var logIncoming: Boolean = Env.optional("LogIncomingData")?.toBoolean() ?: true

public lateinit var lambdaContext: LambdaContext
