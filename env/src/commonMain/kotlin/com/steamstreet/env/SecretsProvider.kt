package com.steamstreet.env

/**
 * Supplies the value of a secret for the `Secret_` convention in [getEnvironmentVariable].
 *
 * The JVM and native targets each install a default that reads AWS Secrets Manager: the AWS SDK on
 * the JVM, and awskt's own `aws-secretsmanager` on native. Assign `secrets` to replace it, for
 * example in tests.
 */
public interface SecretsProvider {
    /** The secret's string value, or null when it has none. */
    public suspend fun getSecretValue(secretId: String): String?
}
