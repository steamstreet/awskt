package com.steamstreet.env

import com.steamstreet.awskt.secretsmanager.SecretsManager
import com.steamstreet.awskt.secretsmanager.getSecretString

/**
 * Reads secrets from AWS Secrets Manager through awskt's `aws-secretsmanager` client. This is the
 * native counterpart of the JVM provider of the same name, which uses the AWS SDK.
 *
 * The client resolves region and credentials the way every awskt client does, which in a Lambda
 * means the execution role's credentials from the environment. It is built on first use, so a
 * process that never reads a `Secret_` variable never constructs it.
 *
 * Nothing is cached, matching the JVM: every read of a `Secret_` variable is a call to Secrets
 * Manager. Read such variables once and keep the value.
 */
public class SecretsManagerSecretsProvider(
    client: () -> SecretsManager = { SecretsManager() },
) : SecretsProvider {
    private val client by lazy(client)

    override suspend fun getSecretValue(secretId: String): String? = client.getSecretString(secretId)
}
