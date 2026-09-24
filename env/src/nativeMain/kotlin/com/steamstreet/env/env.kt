package com.steamstreet.env

import com.steamstreet.awskt.logging.`is`
import com.steamstreet.awskt.logging.log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getenv
import platform.posix.setenv

/**
 * The provider that resolves `Secret_` values. Defaults to [SecretsManagerSecretsProvider]; assign
 * another to replace it, for example in tests.
 */
public var secrets: SecretsProvider = SecretsManagerSecretsProvider()

@OptIn(ExperimentalForeignApi::class)
private fun rawEnvironmentVariable(key: String): String? = getenv(key)?.toKString()

/**
 * Reads an environment variable, following the same conventions as the JVM:
 *
 * - A value of the form `Secret_<secretId>` is replaced by that secret's value from [secrets].
 *   `Secret_<secretId>.<jsonKey>` reads one key of a JSON secret. The id may be a name or a full
 *   ARN; everything after the **last** `.` is taken as the key.
 * - A separate variable named `Secret_<key>` does the same for `key`, with the secret reference as
 *   its value, and takes precedence over `key` itself.
 * - The value `_NoValue` reads as null.
 *
 * A secret that cannot be read, for any reason, is logged as a warning and reads as null, as on
 * the JVM. `AppConfig.` values are a JVM-only convention and are returned unresolved here.
 *
 * Resolving a secret blocks the calling thread for the duration of a Secrets Manager call.
 */
public actual fun getEnvironmentVariable(key: String): String? {
    val value = rawEnvironmentVariable(key)
    val secretReference = rawEnvironmentVariable("Secret_$key")
        ?: value?.takeIf { it.startsWith("Secret_") }?.removePrefix("Secret_")

    return when {
        secretReference != null -> runBlocking { resolveSecret(key, secretReference) }
        value == "_NoValue" -> null
        else -> value
    }
}

private suspend fun resolveSecret(key: String, secretReference: String): String? = try {
    val secretString = secrets.getSecretValue(secretReference.substringBeforeLast("."))
    if (secretString == null) {
        log.warning("No secret found for $secretReference")
        null
    } else if (secretReference.contains('.')) {
        val valueKey = secretReference.substringAfterLast(".")
        Json.parseToJsonElement(secretString).jsonObject[valueKey]?.jsonPrimitive?.contentOrNull
    } else {
        secretString
    }
} catch (e: Throwable) {
    log.warning("Error retrieving secret", e) {
        "key" `is` key
        "secretKey" `is` secretReference
    }
    null
}

/**
 * Unlike the JVM actual, which shadows the environment with an `ENV.`-prefixed system property,
 * this writes the real process environment — a native process has nowhere else to put it. The
 * value is therefore visible to anything else in the process that reads `getenv`, and inherited by
 * child processes.
 */
@OptIn(ExperimentalForeignApi::class)
public actual fun registerEnvironmentVariable(key: String, value: String) {
    setenv(key, value, 1)
}

public actual fun getIntEnvironmentVariable(key: String): Int? =
    getEnvironmentVariable(key)?.toIntOrNull()
