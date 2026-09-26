package com.steamstreet.awskt.sts

import com.steamstreet.awskt.signing.AwsCredentials
import kotlin.time.Duration

/**
 * Request and response types for STS.
 *
 * Nothing here is `@Serializable`: STS speaks the query protocol, and `Wire.kt` is the only encoder
 * and reader. See `aws-sns`'s model for the same rule.
 */

/**
 * `AssumeRole`.
 *
 * The MFA fields (`SerialNumber`, `TokenCode`) and `ProvidedContexts` are not modelled: nothing this
 * library serves can answer an MFA prompt, and provided contexts belong to Identity Center's trusted
 * identity propagation. Both are reachable through [Sts.client].
 *
 * @param roleArn the role to assume.
 * @param roleSessionName the name CloudTrail records for this session, and the last segment of the
 *   assumed-role ARN. 2 to 64 characters of `[\w+=,.@-]`. Required, and worth choosing deliberately:
 *   it is how an audit tells one caller's use of a shared role from another's.
 * @param duration how long the credentials last. Null takes the service default of **one hour**.
 *   At least 15 minutes, and at most the role's `MaxSessionDuration` — or **one hour, whatever that
 *   setting says, when the caller is itself an assumed role** ("role chaining"). A Lambda assuming a
 *   role is always role chaining, so a longer duration there is a `ValidationError`.
 * @param externalId the value a cross-account trust policy's `sts:ExternalId` condition expects.
 * @param policy an inline session policy, as JSON. It can only *narrow* the role's permissions.
 * @param policyArns managed policies to use as session policies, also only narrowing.
 * @param tags session tags, which become `aws:PrincipalTag` values for the session.
 * @param transitiveTagKeys keys among [tags] that persist into any role this session assumes next.
 * @param sourceIdentity an identity that sticks to this session and every role chained from it.
 */
public data class AssumeRoleRequest(
    val roleArn: String,
    val roleSessionName: String,
    val duration: Duration? = null,
    val externalId: String? = null,
    val policy: String? = null,
    val policyArns: List<String> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    val transitiveTagKeys: List<String> = emptyList(),
    val sourceIdentity: String? = null,
)

/**
 * The result of `AssumeRole`.
 *
 * Printing this is safe: [AwsCredentials.toString] reports the access key id and never the secret or
 * the session token.
 *
 * @property credentials the temporary credentials, with [AwsCredentials.expiresAtEpochMillis] taken
 *   from STS's `Expiration`. Always carries a session token.
 * @property packedPolicySize the session policies and tags as a percentage of STS's packed size
 *   limit. Worth watching when it approaches 100: past it the call fails with
 *   [PackedPolicyTooLargeException].
 */
public data class AssumeRoleResponse(
    val credentials: AwsCredentials,
    val assumedRoleUser: AssumedRoleUser?,
    val packedPolicySize: Int?,
    val sourceIdentity: String?,
)

/**
 * The principal an `AssumeRole` session acts as.
 *
 * @property arn `arn:aws:sts::<account>:assumed-role/<role-name>/<session-name>` — the ARN that
 *   appears in resource policies' error messages and in CloudTrail.
 * @property assumedRoleId `<role-id>:<session-name>`.
 */
public data class AssumedRoleUser(
    val arn: String,
    val assumedRoleId: String,
)

/**
 * The result of `GetCallerIdentity`: who the credentials in use belong to.
 *
 * @property account the 12-digit account id.
 * @property arn the caller's ARN — for an assumed role, the `assumed-role/…/<session>` form.
 * @property userId the unique id: an IAM user's id, or `<role-id>:<session-name>` for a role.
 */
public data class CallerIdentity(
    val account: String,
    val arn: String,
    val userId: String,
)
