package com.steamstreet.env

import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.getSecretValue
import kotlinx.coroutines.runBlocking

/**
 * Implementation to get secrets from the AWS Secrets Manager
 */
public class SecretsManagerSecretsProvider : SecretsProvider {
    private val client by lazy {
        runBlocking { SecretsManagerClient.fromEnvironment() }
    }

    override suspend fun getSecretValue(secretId: String): String? {
        return client.getSecretValue {
            this.secretId = secretId
        }.secretString
    }
}