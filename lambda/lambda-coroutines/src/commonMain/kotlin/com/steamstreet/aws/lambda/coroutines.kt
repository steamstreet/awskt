package com.steamstreet.aws.lambda

import com.steamstreet.env.Env
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The invocation context, abstracting over the JVM AWS `Context` and the Native Runtime API headers.
 *
 * This exists so that handler code compiled for both the JVM and Kotlin/Native can read the request
 * id and the remaining time without knowing which runtime it is on. On the JVM it is a thin wrapper
 * over the AWS `Context` ([JvmLambdaContext]); on Native it is built from the
 * `Lambda-Runtime-*` response headers.
 */
public interface LambdaContext {
    public val requestId: String
    public val functionName: String

    /**
     * Milliseconds left before Lambda kills the invocation.
     *
     * Implementations MUST recompute this on every read. It is the value handlers use to decide
     * whether there is time for another unit of work, so a snapshot taken once at the start of the
     * invocation would report the full timeout no matter how long the handler had already been
     * running — an error that only shows up under load, as work started with no time left to
     * finish it.
     */
    public val remainingTimeInMillis: Int
}

/**
 * The context for the invocation currently on this thread/worker.
 *
 * `lateinit` because it is set by the runtime at the top of each invocation, before any handler code
 * runs.
 *
 * **A process-global, and therefore sound only while Lambda delivers one invocation at a time per
 * execution environment.** That has always held for the classic on-demand execution environment, but
 * Lambda Managed Instances can dispatch concurrent invocations into a single environment, where a
 * second invocation would overwrite this before the first had finished reading it — handing the
 * first handler the wrong request id and the wrong `remainingTimeInMillis`. Neither runtime supports
 * that mode today; the limitation is written up on
 * `com.steamstreet.aws.lambda.native.LambdaRuntime` and tracked as Risk 34 in
 * `NATIVE-AWS-CLIENT-PLAN.md`. Moving this onto the coroutine context is the fix when it is needed.
 */
public lateinit var lambdaContext: LambdaContext

@OptIn(ExperimentalSerializationApi::class)
public val lambdaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

public var logIncoming: Boolean = Env.optional("LogIncomingData")?.toBoolean() ?: true
