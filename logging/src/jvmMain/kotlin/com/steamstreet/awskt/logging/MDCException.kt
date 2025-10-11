package com.steamstreet.awskt.logging

import com.steamstreet.exceptions.MDCExceptionMixin
import org.slf4j.MDC

public open class MDCException(message: String?, cause: Throwable? = null) : Exception(message, cause),
    MDCExceptionMixin {
    override val mdcAttributes: MutableMap<String, Any?> = MDC.getCopyOfContextMap()?.toMutableMap() ?: mutableMapOf()
}