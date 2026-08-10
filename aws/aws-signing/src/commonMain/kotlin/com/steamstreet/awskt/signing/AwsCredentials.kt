package com.steamstreet.awskt.signing

/**
 * A set of AWS credentials used to sign a request.
 *
 * @param accessKeyId the AWS access key id.
 * @param secretAccessKey the AWS secret access key.
 * @param sessionToken the session token, present for all temporary credentials — which in practice
 *   means every credential a Lambda, ECS task or assumed role ever holds.
 * @param expiresAtEpochMillis when these credentials expire, if that is knowable.
 *
 * ### On [expiresAtEpochMillis] being null
 *
 * This is **null in Lambda**, and callers must not treat null as "does not expire". Lambda publishes
 * its execution-role credentials through `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` /
 * `AWS_SESSION_TOKEN` and publishes no expiry alongside them, so code running there cannot discover
 * when its own credentials die.
 *
 * That matters most for presigned URLs: a presigned URL stops working when the credentials that
 * signed it expire, regardless of `X-Amz-Expires`. See [SigV4Config.expiresInSeconds].
 */
public class AwsCredentials(
    public val accessKeyId: String,
    public val secretAccessKey: String,
    public val sessionToken: String? = null,
    public val expiresAtEpochMillis: Long? = null,
) {
    /** Deliberately omits the secret and the session token. Do not "improve" this. */
    override fun toString(): String = "AwsCredentials(accessKeyId=$accessKeyId)"
}
