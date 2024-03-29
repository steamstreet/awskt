package com.steamstreet.env

import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.getSecretValue
import com.steamstreet.env.Env.optional
import com.steamstreet.mutableLazy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public var secrets: SecretsManagerClient by mutableLazy {
    runBlocking {
        SecretsManagerClient.fromEnvironment()
    }
}

public actual fun getEnvironmentVariable(key: String): String? = runBlocking {
    // we can encode an environment variable as a secret, which will retrieve it using the AWS secrets manager.
    val value = System.getProperty("ENV.$key") ?: System.getenv(key)
    var secretKey = System.getenv("Secret_$key")
    if (secretKey != null || value?.startsWith("Secret_") == true) {
        try {
            secretKey = secretKey ?: value.removePrefix("Secret_")
            secrets.getSecretValue {
                secretId = secretKey.substringBeforeLast(".")
            }.secretString?.let {
                if (secretKey.contains('.')) {
                    val valueKey = secretKey.substringAfterLast(".")
                    Json.parseToJsonElement(it).jsonObject[valueKey]?.jsonPrimitive?.contentOrNull
                } else {
                    it
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    } else if (value == "_NoValue") {
        null
    } else if (value?.startsWith("AppConfig") == true) {
        val default = value.substringAfter(":", "").ifBlank { null }
        val (_, app, environment, config, configValue) = value.substringBefore(":").split(".")
        AppConfig.getString(app, environment, config, configValue, default)
    } else {
        value
    }
}

/**
 * Register an environment variable. This simple implementation installs system properties with an "ENV." prefix.
 * This allows other tools to be used to install variables (like the command line).
 */
public actual fun registerEnvironmentVariable(key: String, value: String) {
    System.setProperty("ENV.$key", value)
}

public actual fun getIntEnvironmentVariable(key: String): Int? {
    return optional(key)?.toIntOrNull()
}