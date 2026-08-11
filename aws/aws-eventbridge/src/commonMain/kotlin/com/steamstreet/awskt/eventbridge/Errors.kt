package com.steamstreet.awskt.eventbridge

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every EventBridge failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId` — declared now rather than later because re-parenting a
 * public exception hierarchy after publication is an API revision.
 */
public open class EventBridgeException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId)

/** The named event bus does not exist. */
public class ResourceNotFoundException(message: String?, statusCode: Int, requestId: String? = null) :
    EventBridgeException("ResourceNotFoundException", message, statusCode, requestId)

/** The request was structurally rejected — a malformed `Detail`, or an entry over the size limit. */
public class ValidationException(message: String?, statusCode: Int, requestId: String? = null) :
    EventBridgeException("ValidationException", message, statusCode, requestId)

/**
 * The account's `PutEvents` rate was exceeded.
 *
 * Note this is the *whole-request* throttle. A batch that is merely partially rejected does not
 * raise anything at all — it returns HTTP 200 with per-entry `ErrorCode`s in the body. See
 * [PutEventsResponse].
 */
public class ThrottlingException(message: String?, statusCode: Int, requestId: String? = null) :
    EventBridgeException("ThrottlingException", message, statusCode, requestId)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes fall through to [EventBridgeException] rather than being swallowed, so an
 * operation added downstream through the extension seam still gets a useful typed failure without
 * registering anything.
 */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: AwsServiceException) {
    throw when (e.code) {
        "ResourceNotFoundException" -> ResourceNotFoundException(e.message, e.statusCode, e.requestId)
        "ValidationException" -> ValidationException(e.message, e.statusCode, e.requestId)
        "ThrottlingException" -> ThrottlingException(e.message, e.statusCode, e.requestId)
        else -> EventBridgeException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId)
    }
}
