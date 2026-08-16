@file:Suppress("unused")

package com.steamstreet.aws.sns

import com.steamstreet.aws.lambda.InputLambda
import kotlinx.serialization.KSerializer

/**
 * Base class for a lambda function that handles SNS messages.
 *
 * Unchanged as an API. The body is now a call to the common [processMessages], so a JVM handler and
 * a native one built with `snsLambda` decode and dispatch through the same code.
 */
public abstract class SNSHandler<T>(private val serializer: KSerializer<T>) :
    InputLambda<SnsPayload>(SnsPayload.serializer()) {

    override suspend fun handle(input: SnsPayload) {
        input.processMessages(serializer) { message ->
            handleMessage(message)
        }
    }

    /**
     * Handle an individual message
     */
    context(record: SnsRecord)
    public abstract suspend fun handleMessage(message: T)
}
