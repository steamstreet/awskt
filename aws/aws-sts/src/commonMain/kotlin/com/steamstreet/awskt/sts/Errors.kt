package com.steamstreet.awskt.sts

import com.steamstreet.awskt.core.AwsServiceException

/**
 * Base for every STS failure.
 *
 * Extends `aws-core`'s [AwsServiceException] so every failure carries `code`, `statusCode`,
 * `requestId` and `extendedRequestId`.
 *
 * ### The codes are the query protocol's, and two of them disagree with their shape names
 *
 * `MalformedPolicyDocumentException` arrives as `<Code>MalformedPolicyDocument</Code>` and
 * `PackedPolicyTooLargeException` as `PackedPolicyTooLarge`, while `ExpiredTokenException` and
 * `RegionDisabledException` keep the suffix on the wire. The strings below were taken from the AWS
 * SDK's generated `AssumeRole` deserializer rather than from documentation, as `aws-sns`'s were.
 *
 * **The failure a caller meets most is not one of them.** A trust policy that does not name the
 * caller, or a caller without `sts:AssumeRole`, answers `AccessDenied` — an unmodelled code, so it
 * arrives as a plain [StsException] with that [code].
 */
public open class StsException(
    code: String?,
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : AwsServiceException(code, message, statusCode, requestId, extendedRequestId, cause)

/**
 * The session token on the credentials that *called* STS has expired.
 *
 * Wire code `ExpiredTokenException`. This is about the source credentials, not the role being
 * assumed: a long-lived process whose own credentials were never refreshed.
 */
public class ExpiredTokenException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : StsException("ExpiredTokenException", message, statusCode, requestId, extendedRequestId, cause)

/** The session policy is not valid policy JSON. Wire code `MalformedPolicyDocument`. */
public class MalformedPolicyDocumentException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : StsException("MalformedPolicyDocument", message, statusCode, requestId, extendedRequestId, cause)

/**
 * The session policies and tags together exceed STS's packed size limit.
 *
 * Wire code `PackedPolicyTooLarge`. [AssumeRoleResponse.packedPolicySize] reports how close a
 * successful call came.
 */
public class PackedPolicyTooLargeException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : StsException("PackedPolicyTooLarge", message, statusCode, requestId, extendedRequestId, cause)

/**
 * STS is not activated in this region for the account.
 *
 * Wire code `RegionDisabledException`. Regional STS endpoints can be deactivated per account in IAM
 * account settings, and this client always uses the regional endpoint — see [Sts].
 */
public class RegionDisabledException(
    message: String?,
    statusCode: Int,
    requestId: String? = null,
    extendedRequestId: String? = null,
    cause: Throwable? = null,
) : StsException("RegionDisabledException", message, statusCode, requestId, extendedRequestId, cause)

/**
 * Maps `aws-core`'s protocol-level exception onto this module's hierarchy.
 *
 * Unknown codes — `AccessDenied` and `ValidationError` among them — fall through to [StsException]
 * with the code intact. Every branch threads `cause = e` and both request ids.
 */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: AwsServiceException) {
    throw when (e.code) {
        "ExpiredTokenException" ->
            ExpiredTokenException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "MalformedPolicyDocument" ->
            MalformedPolicyDocumentException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "PackedPolicyTooLarge" ->
            PackedPolicyTooLargeException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        "RegionDisabledException" ->
            RegionDisabledException(e.message, e.statusCode, e.requestId, e.extendedRequestId, e)

        else -> StsException(e.code, e.message, e.statusCode, e.requestId, e.extendedRequestId, e)
    }
}
