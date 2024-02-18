@file:Suppress("unused", "PropertyName")

package com.steamstreet.aws.sns

import com.steamstreet.aws.lambda.InputLambda
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public class SnsPayload(
    public val Records: List<SnsRecord> = emptyList()
)

@Serializable
public class SnsRecord(
    public val Sns: SnsData,
    public val EventVersion: String,
    public val EventSubscriptionArn: String? = null,
    public val EventSource: String
)

@Serializable
public class SnsData(
    public val SignatureVersion: String,
    public val Timestamp: String,
    public val Signature: String,
    public val SigningCertUrl: String? = null,
    public val MessageId: String,
    public val Message: String,
    public val TopicArn: String,
    public val Subject: String? = null
)

/**
 * Base class for a lambda function that handles SNS messages.
 */
public abstract class SNSHandler<T>(private val serializer: KSerializer<T>) :
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
    public abstract suspend fun handleMessage(message: T)
}