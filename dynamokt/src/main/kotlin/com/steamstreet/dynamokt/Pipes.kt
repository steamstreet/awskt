package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
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
                0 -> AttributeValue.S(decodeStringElement(descriptor, index))
                1 -> AttributeValue.N(decodeStringElement(descriptor, index))
                2 -> AttributeValue.B(
                        Base64.getDecoder().decode(decodeStringElement(descriptor, index))
                )

                3 -> AttributeValue.Bool(decodeBooleanElement(descriptor, index))
                4 -> AttributeValue.L(
                    decodeSerializableElement(
                        descriptor,
                        index,
                        ListSerializer(this@AttributeValueSerializer)
                    )
                )

                5 -> AttributeValue.M(
                    decodeSerializableElement(
                        descriptor,
                        index,
                        MapSerializer(String.serializer(), this@AttributeValueSerializer)
                    )
                )

                6 -> AttributeValue.Ss(
                    decodeSerializableElement(
                        descriptor,
                        index,
                        ListSerializer(String.serializer())
                    )
                )

                7 -> AttributeValue.Ns(
                    decodeSerializableElement(
                        descriptor,
                        index,
                        ListSerializer(String.serializer())
                    )
                )

                else -> throw SerializationException("Unexpected index: $index")
            }
        }
    }

    override fun serialize(encoder: Encoder, value: AttributeValue) {
        encoder.encodeStructure(descriptor) {
            when {
                value.asSOrNull() != null -> encodeStringElement(descriptor, 0, value.asS())
                value.asNOrNull() != null -> encodeStringElement(descriptor, 1, value.asN())
                value.asBOrNull() != null -> encodeStringElement(
                    descriptor,
                    2,
                    Base64.getEncoder().encodeToString(value.asB())
                )

                value.asBoolOrNull() != null -> encodeBooleanElement(descriptor, 3, value.asBool())
                !value.asLOrNull().isNullOrEmpty() -> encodeSerializableElement(
                    descriptor, 4,
                    ListSerializer(AttributeValueSerializer()), value.asL()
                )

                !value.asMOrNull().isNullOrEmpty() -> encodeSerializableElement(
                    descriptor, 5,
                    MapSerializer(String.serializer(), AttributeValueSerializer()), value.asM()
                )

                !value.asSsOrNull().isNullOrEmpty() -> encodeSerializableElement(
                    descriptor,
                    6,
                    ListSerializer(String.serializer()),
                    value.asSs()
                )

                !value.asNsOrNull().isNullOrEmpty() -> encodeSerializableElement(
                    descriptor,
                    6,
                    ListSerializer(String.serializer()),
                    value.asNs()
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
    public val createDateTime: Long,
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
)

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