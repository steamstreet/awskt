package com.steamstreet.awskt.credentials.sdk

import aws.sdk.kotlin.runtime.auth.credentials.CredentialsNotLoadedException
import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderException
import aws.smithy.kotlin.runtime.collections.emptyAttributes
import com.steamstreet.awskt.core.AwsCredentialsNotFoundException
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.CachedCredentialsProvider
import com.steamstreet.awskt.signing.AwsCredentials

/**
 * Presents an AWS SDK credentials provider as an awskt one.
 *
 * Whatever [delegate] can resolve — a named profile, SSO, `credential_process`, web identity, ECS
 * container credentials, IMDS — becomes usable by every awskt client, none of which `aws-core`
 * carries itself. Access key, secret, session token and expiry are carried across; the SDK's
 * attributes are not, because nothing in awskt reads them.
 *
 * The SDK's failure to find credentials surfaces as [AwsCredentialsNotFoundException], the same
 * type `aws-core`'s own chain throws, so a caller has one exception to handle whichever chain is in
 * effect. Anything else the delegate throws propagates unchanged.
 *
 * This does not cache. The SDK's own chain already does, and [sdkDefaultChainCredentialsProvider]
 * adds awskt's cache on top; wrap anything else in [CachedCredentialsProvider] if it resolves
 * expensively.
 */
public class SdkCredentialsProvider(
    private val delegate: CredentialsProvider,
) : AwsCredentialsProvider {

    override suspend fun resolve(): AwsCredentials {
        val credentials = try {
            delegate.resolve(emptyAttributes())
        } catch (e: CredentialsProviderException) {
            throw AwsCredentialsNotFoundException(e.message ?: "The AWS SDK found no credentials", e)
        } catch (e: CredentialsNotLoadedException) {
            throw AwsCredentialsNotFoundException(e.message ?: "The AWS SDK found no credentials", e)
        }
        return AwsCredentials(
            accessKeyId = credentials.accessKeyId,
            secretAccessKey = credentials.secretAccessKey,
            sessionToken = credentials.sessionToken,
            // smithy-kotlin's Instant exposes seconds and sub-second nanos, not millis.
            expiresAtEpochMillis = credentials.expiration?.let {
                it.epochSeconds * 1_000L + it.nanosecondsOfSecond / 1_000_000L
            },
        )
    }

    /** Never render the wrapped secret; the SDK providers' own toString does not either. */
    override fun toString(): String = "SdkCredentialsProvider($delegate)"
}

/**
 * The AWS SDK's default credential chain, as an awskt provider: system properties, environment,
 * web identity, the shared config and credentials files (so profiles and SSO), ECS container
 * credentials, and IMDS, in that order.
 *
 * This is the JVM answer to `aws-core`'s environment-only default. Install it once at startup to
 * make it the default for every awskt client that is not given a provider explicitly:
 *
 * ```
 * AwsCredentialsDefaults.provider = sdkDefaultChainCredentialsProvider()
 * ```
 *
 * Or hand it to a single client's `credentialsProvider`.
 *
 * The result is wrapped in [CachedCredentialsProvider], so it behaves like the built-in default:
 * lock-free on a cache hit, and serving a still-valid credential when a refresh fails.
 *
 * The underlying SDK provider owns an HTTP engine for the container and IMDS endpoints. It is meant
 * to live as long as the process; build one and share it rather than building one per client.
 *
 * @param profileName the profile to read from the shared config files, in place of `AWS_PROFILE`
 *   or `default`.
 * @param region the region for the STS calls that web identity and SSO make, when the environment
 *   and profile do not supply one.
 */
public fun sdkDefaultChainCredentialsProvider(
    profileName: String? = null,
    region: String? = null,
): AwsCredentialsProvider = CachedCredentialsProvider(
    SdkCredentialsProvider(DefaultChainCredentialsProvider(profileName = profileName, region = region)),
)
