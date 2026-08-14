package com.steamstreet.awskt.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64

/**
 * Serializes a `ByteArray` as an AWS-JSON `blob`, which on the wire is a **base64 string**.
 *
 * ### Why this lives in `aws-core` rather than in a service module
 *
 * Blobs are a protocol feature, not a service feature. Two of the modules built on this transport
 * need one already — KMS carries five of them (`Plaintext`, `CiphertextBlob`, `Message`,
 * `Signature`) and Secrets Manager carries `SecretBinary` — and a second, subtly different copy is
 * exactly the kind of drift that produces a client which round-trips its own bytes correctly and
 * disagrees with AWS at the margins. `aws-dynamodb` does **not** use this: DynamoDB's `B` and `BS`
 * attribute values are base64 too, but they are reached through `AttributeValueSerializer`, which
 * has to make the same decision inside a polymorphic union and so cannot delegate to a plain
 * primitive serializer.
 *
 * ### Standard alphabet, padded, strict
 *
 * [Base64.Default] is RFC 4648 §4 with padding, which is what every AWS-JSON service emits and the
 * only form they accept. Not the URL-safe alphabet ([Base64.UrlSafe]) and not the MIME variant,
 * which would accept — and silently skip — embedded line breaks.
 *
 * ### What a malformed blob does
 *
 * [Base64.decode] throws `IllegalArgumentException` on input that is not valid base64, and this
 * deliberately does not catch it. The only way to reach that state is AWS returning a response body
 * that is not what it says it is, and converting that into a null or an empty array would hand the
 * caller a *plausible* value — an empty plaintext, a zero-length signature — in place of a loud
 * failure. It arrives at the call site as a deserialization failure from inside `callJson`, after
 * the retry loop has already accepted the response, so it is not retried: a body that does not
 * parse this attempt will not parse the next one either.
 */
public object Base64BlobSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.steamstreet.awskt.core.Base64Blob", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.Default.encode(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.Default.decode(decoder.decodeString())
}
