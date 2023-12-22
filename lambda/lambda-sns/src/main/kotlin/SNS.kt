@file:Suppress("unused", "PropertyName")

package com.steamstreet.aws.sns

import com.steamstreet.aws.lambda.InputLambda
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
class SnsPayload(
    val Records: List<SnsRecord> = emptyList()
)

@Serializable
class SnsRecord(
    val Sns: SnsData,
    val EventVersion: String,
    val EventSubscriptionArn: String? = null,
    val EventSource: String
)

@Serializable
class SnsData(
    val SignatureVersion: String,
    val Timestamp: String,
    val Signature: String,
    val SigningCertUrl: String? = null,
    val MessageId: String,
    val Message: String,
    val TopicArn: String,
    val Subject: String? = null
)

/**
 * Base class for a lambda function that handles SNS messages.
 */
abstract class SNSHandler<T>(private val serializer: KSerializer<T>) :
    InputLambda<SnsPayload>(SnsPayload.serializer()) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun handle(input: SnsPayload) {
        input.Records.forEach {
            val message = it.Sns.Message
            val messagePayload = json.decodeFromString(serializer, message)

            it.apply {
                handleMessage(messagePayload)
            }
        }
    }

    /**
     * Handle an individual message
     */
    context(SnsRecord)
    abstract suspend fun handleMessage(message: T)
}