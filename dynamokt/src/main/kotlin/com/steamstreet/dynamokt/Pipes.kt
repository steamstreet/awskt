package com.steamstreet.dynamokt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.util.*

/**
 * Serializer for attributes receives in EventBridge Pipes from a dynamodb stream.
 */
public class AttributeValueSerializer : KSerializer<AttributeValue> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("AttributeValue") {
            element<String>("S", isOptional = true)
            element<String>("N", isOptional = true)
            element<String>("B", isOptional = true)
            element<Boolean>("BOOL", isOptional = true)
            element<JsonElement>("L", isOptional = true)
            element<JsonObject>("M", isOptional = true)
            element<JsonElement>("SS", isOptional = true)
            element<JsonElement>("NS", isOptional = true)
        }

    override fun deserialize(decoder: Decoder): AttributeValue {
        return decoder.decodeStructure(descriptor) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> AttributeValue.builder().s(decodeStringElement(descriptor, index)).build()
                1 -> AttributeValue.builder().n(decodeStringElement(descriptor, index)).build()
                2 -> AttributeValue.builder().b(
                    SdkBytes.fromByteArray(
                        Base64.getDecoder().decode(decodeStringElement(descriptor, index))
                    )
                ).build()

                3 -> AttributeValue.builder().bool(decodeBooleanElement(descriptor, index)).build()
                4 -> AttributeValue.builder().l(
                    decodeSerializableElement(
                        descriptor,
                        index,
                        ListSerializer(this@AttributeValueSerializer)
                    )
                ).build()

                5 -> AttributeValue.builder().m(
                    decodeSerializableElement(
                        descriptor,
                        index,
                        MapSerializer(String.serializer(), this@AttributeValueSerializer)
                    )
                ).build()

                6 -> AttributeValue.builder().ss(
                    decodeSerializableElement(
                        descriptor,
                        index,
                        ListSerializer(String.serializer())
                    )
                ).build()

                7 -> AttributeValue.builder().ns(
                    decodeSerializableElement(
                        descriptor,
                        index,
                        ListSerializer(String.serializer())
                    )
                ).build()

                else -> throw SerializationException("Unexpected index: $index")
            }
        }
    }

    override fun serialize(encoder: Encoder, value: AttributeValue) {
        encoder.encodeStructure(descriptor) {
            when {
                value.s() != null -> encodeStringElement(descriptor, 0, value.s())
                value.n() != null -> encodeStringElement(descriptor, 1, value.n())
                value.b() != null -> encodeStringElement(
                    descriptor,
                    2,
                    Base64.getEncoder().encodeToString(value.b().asByteArray())
                )

                value.bool() != null -> encodeBooleanElement(descriptor, 3, value.bool())
                !value.l().isNullOrEmpty() -> encodeSerializableElement(
                    descriptor, 4,
                    ListSerializer(AttributeValueSerializer()), value.l()
                )

                !value.m().isNullOrEmpty() -> encodeSerializableElement(
                    descriptor, 5,
                    MapSerializer(String.serializer(), AttributeValueSerializer()), value.m()
                )

                !value.ss().isNullOrEmpty() -> encodeSerializableElement(
                    descriptor,
                    6,
                    ListSerializer(String.serializer()),
                    value.ss()
                )

                !value.ns().isNullOrEmpty() -> encodeSerializableElement(
                    descriptor,
                    6,
                    ListSerializer(String.serializer()),
                    value.ns()
                )
            }
        }
    }
}

@Serializable
public class DynamoStreamEvent(
    public val eventID: String,
    public val eventName: String,
    public val eventVersion: String,
    public val eventSource: String,
    public val awsRegion: String,
    public val dynamodb: DynamoStreamEventDetail,
    public val eventSourceARN: String
)

@Serializable
public class DynamoStreamEventDetail(
    @SerialName("ApproximateCreationDateTime")
    private val approximateCreationDateTime: Double,

    @SerialName("Keys")
    public val keys: Map<String, @Serializable(with = AttributeValueSerializer::class) AttributeValue>,
    @SerialName("NewImage")
    public val new: Map<String, @Serializable(with = AttributeValueSerializer::class) AttributeValue>? = null,
    @SerialName("OldImage")
    public val old: Map<String, @Serializable(with = AttributeValueSerializer::class) AttributeValue>? = null,

    @SerialName("SequenceNumber")
    public val sequenceNumber: String? = null,

    @SerialName("SizeBytes")
    public val size: Long? = null,

    @SerialName("StreamViewType")
    public val viewType: String? = null
) {
    public val createDateTime: Long = approximateCreationDateTime.toLong()
}

/**
 * Get the pair of values from the new and old images
 */
public fun DynamoStreamEventDetail.valuePair(attributeName: String): Pair<AttributeValue?, AttributeValue?> {
    return old?.get(attributeName) to new?.get(attributeName)
}

public fun DynamoStreamEvent.oldAndNew(session: DynamoKtSession): Pair<Item?, Item?> {
    return oldItem(session) to newItem(session)
}

public fun DynamoStreamEvent.newItem(session: DynamoKtSession): Item? {
    return dynamodb.new?.let {
        session.unloaded(dynamodb.keys + it, false)
    }
}

public fun DynamoStreamEvent.oldItem(session: DynamoKtSession): Item? {
    return dynamodb.old?.let {
        session.unloaded(dynamodb.keys + it, false)
    }
}

/**
 * Create an event bridge detail rule that matches the given key.
 *
 * Examples:
 * - eventBridgeKeyRule("pk", listOf("media"))
 * - eventBridgeKeyRule("sk", listOf(prefix("url:"))
 */
public fun eventBridgeKeyRule(keyName: String, match: List<Any>): Map<String, Any> {
    return mapOf(
        "dynamodb" to mapOf(
            "Keys" to mapOf(
                keyName to mapOf(
                    "S" to match
                )
            )
        )
    )
}

/**
 * Mapping for the incoming dynamo event when it can include multiple records.
 */
@Serializable
public class DynamoStreamRecords(
    @SerialName("Records")
    public val records: List<DynamoStreamEvent>
)