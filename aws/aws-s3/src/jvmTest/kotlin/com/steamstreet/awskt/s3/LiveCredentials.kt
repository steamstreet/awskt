package com.steamstreet.awskt.s3

import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.smithy.kotlin.runtime.collections.emptyAttributes
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.signing.AwsCredentials

/**
 * Resolves a named AWS profile through the SDK and bridges it into our own provider.
 *
 * Necessary rather than stylistic: `defaultCredentialsProvider()` reads **environment variables
 * only** — no profile file, no SSO, no IMDS — so without this bridge a live test could only run by
 * exporting raw keys into a shell. Here the secret is read inside the test JVM by the SDK and never
 * reaches a shell, a log or a build file.
 */
internal fun profileCredentials(profileName: String): AwsCredentialsProvider {
    val sdk = ProfileCredentialsProvider(profileName = profileName)
    return AwsCredentialsProvider {
        val resolved = sdk.resolve(emptyAttributes())
        AwsCredentials(
            accessKeyId = resolved.accessKeyId,
            secretAccessKey = resolved.secretAccessKey,
            sessionToken = resolved.sessionToken,
            expiresAtEpochMillis = resolved.expiration?.epochSeconds?.times(1_000),
        )
    }
}
