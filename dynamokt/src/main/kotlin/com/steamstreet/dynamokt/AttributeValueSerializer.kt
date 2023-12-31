package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
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
 * Serializer for attributes. Uses the format of EventBridge Pipes from a dynamodb stream.
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

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: AttributeValue) {
        encoder.encodeStructure(descriptor) {
            when (value) {
                is AttributeValue.S -> encodeStringElement(descriptor, 0, value.asS())
                is AttributeValue.N -> encodeStringElement(descriptor, 1, value.asN())
                is AttributeValue.B -> encodeStringElement(
                    descriptor,
                    2,
                    Base64.getEncoder().encodeToString(value.asB())
                )

                is AttributeValue.Bool -> encodeBooleanElement(descriptor, 3, value.asBool())
                is AttributeValue.L -> encodeSerializableElement(
                    descriptor, 4,
                    ListSerializer(AttributeValueSerializer()), value.asL()
                )

                is AttributeValue.M -> encodeSerializableElement(
                    descriptor, 5,
                    MapSerializer(String.serializer(), AttributeValueSerializer()), value.asM()
                )

                is AttributeValue.Ss -> encodeSerializableElement(
                    descriptor,
                    6,
                    ListSerializer(String.serializer()),
                    value.asSs()
                )

                is AttributeValue.Ns -> encodeSerializableElement(
                    descriptor,
                    7,
                    ListSerializer(String.serializer()),
                    value.asNs()
                )

                is AttributeValue.Bs -> {
                    encodeSerializableElement(descriptor, 8,
                        ListSerializer(String.serializer()),
                        value.asBs().map {
                            Base64.getEncoder().encodeToString(it)
                        }
                    )
                }

                is AttributeValue.Null -> encoder.encodeNull()
                is AttributeValue.SdkUnknown -> throw IllegalArgumentException("Unknown attribute type")
            }
        }
    }
}