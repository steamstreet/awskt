package com.steamstreet.aws.lambda.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import ch.qos.logback.core.encoder.Encoder
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.LambdaRuntime
import com.amazonaws.services.lambda.runtime.LambdaRuntimeInternal

/**
 * SLF4J appender for lambda.
 */
class AwsLambdaAppender : UnsynchronizedAppenderBase<ILoggingEvent?>() {
    private val logger: LambdaLogger = LambdaRuntime.getLogger()

    @Suppress("MemberVisibilityCanBePrivate")
    val encoder: Encoder<ILoggingEvent>? = null

    init {
        LambdaRuntimeInternal.setUseLog4jAppender(true)
    }

    override fun append(event: ILoggingEvent?) {
        encoder?.encode(event)?.let {
            logger.log(it)
        }
    }
}