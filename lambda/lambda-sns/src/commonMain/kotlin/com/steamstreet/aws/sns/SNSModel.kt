@file:Suppress("PropertyName")

package com.steamstreet.aws.sns

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The SNS event envelope and the message-processing loop, in `commonMain` so the same handler body
 * compiles for the JVM and for Kotlin/Native.
 *
 * The JVM's [SNSHandler] and the native `snsLambda` are both shells over [processMessages] — neither
 * carries a second copy of the decode-and-dispatch behaviour.
 */
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
 * Decodes SNS message bodies.
 *
 * Deliberately not `lambdaJson`: this is the same lenient configuration [SNSHandler] has always
 * created for itself, kept identical so moving the loop into common does not change how an existing
 * JVM handler parses a message it has been receiving for years.
 */
internal val snsMessageJson: Json = Json {
    ignoreUnknownKeys = true
}

/**
 * Decode each message in the payload and hand it to [handler], with the enclosing [SnsRecord] as a
 * context parameter so the handler can reach the message id, topic and subject.
 *
 * This is the whole of what an SNS Lambda does, available without a Lambda runtime behind it — call
 * it from a test, from your own `main`, or from either of the wrappers built on it.
 */
public suspend fun <T> SnsPayload.processMessages(
    serializer: KSerializer<T>,
    handler: suspend context(SnsRecord) (T) -> Unit
) {
    Records.forEach { record ->
        handler(record, snsMessageJson.decodeFromString(serializer, record.Sns.Message))
    }
}

/**
 * Hand each message body to [handler] without decoding it.
 *
 * Use this when the topic carries more than one message shape and the handler has to look at the
 * body before it can pick a serializer.
 */
public suspend fun SnsPayload.processRawMessages(
    handler: suspend context(SnsRecord) (String) -> Unit
) {
    Records.forEach { record ->
        handler(record, record.Sns.Message)
    }
}
