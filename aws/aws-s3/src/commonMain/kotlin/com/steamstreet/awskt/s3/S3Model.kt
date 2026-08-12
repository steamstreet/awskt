package com.steamstreet.awskt.s3

/**
 * Request and response types.
 *
 * ### Two rules that are not style preferences
 *
 * **Nothing here that carries a `ByteArray` is a `data class`.** Decision 2 states the rule for
 * `AttributeValue.B`/`Bs` and it applies unchanged: a generated `copy()`, `componentN()` and
 * `toString()` all expose the array — `toString()` by printing an identity hash that looks like
 * content, `equals` by comparing references so two identical payloads compare unequal. The types
 * that carry bytes get hand-written `equals`/`hashCode` over `contentEquals`/`contentHashCode`.
 * Types with no `ByteArray` are ordinary data classes and gain `copy()` legitimately.
 *
 * **Nothing here is `@Serializable`.** S3 is a REST protocol: metadata rides in headers and the
 * body is opaque bytes. If you find yourself reaching for `@SerialName` you have wandered into
 * bucket listing or multipart, which are out of scope for v1.
 *
 * ### Every date-shaped header is an unparsed `String`
 *
 * Stated once rather than decided per field. `lastModified` is IMF-fixdate passed straight through.
 * No parse failure can fail a response, because there is no parse. `Expires` is omitted entirely:
 * S3 returns whatever string the uploader set, frequently not a valid date at all, which is exactly
 * why the AWS SDKs had to add a separate `ExpiresString` field after the fact.
 */

/** A GET. */
public data class GetObjectRequest(
    val bucket: String,
    val key: String,
    val versionId: String? = null,
    /** e.g. `bytes=0-9`. A ranged GET answers 206 and bypasses most of the memory ceiling. */
    val range: String? = null,
    val ifMatch: String? = null,
    val ifNoneMatch: String? = null,
    val ifModifiedSince: String? = null,
    val ifUnmodifiedSince: String? = null,
    val responseContentType: String? = null,
    val responseContentDisposition: String? = null,
    val responseCacheControl: String? = null,
    val responseContentEncoding: String? = null,
    val responseContentLanguage: String? = null,
)

/**
 * A GET's result. **Not a data class** — see the file KDoc.
 *
 * @param metadata `x-amz-meta-*` headers with the prefix stripped and names lowercased.
 * @param eTag with S3's surrounding quotes removed.
 */
public class GetObjectResponse(
    public val body: ByteArray,
    public val contentLength: Long,
    public val contentType: String? = null,
    public val eTag: String? = null,
    public val lastModified: String? = null,
    public val versionId: String? = null,
    public val contentRange: String? = null,
    public val cacheControl: String? = null,
    public val contentEncoding: String? = null,
    public val contentDisposition: String? = null,
    public val metadata: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is GetObjectResponse &&
                body.contentEquals(other.body) &&
                contentLength == other.contentLength &&
                contentType == other.contentType &&
                eTag == other.eTag &&
                lastModified == other.lastModified &&
                versionId == other.versionId &&
                contentRange == other.contentRange &&
                cacheControl == other.cacheControl &&
                contentEncoding == other.contentEncoding &&
                contentDisposition == other.contentDisposition &&
                metadata == other.metadata
            )

    override fun hashCode(): Int {
        var result = body.contentHashCode()
        result = 31 * result + contentLength.hashCode()
        result = 31 * result + (eTag?.hashCode() ?: 0)
        result = 31 * result + (versionId?.hashCode() ?: 0)
        result = 31 * result + metadata.hashCode()
        return result
    }

    /** Reports the size, never the bytes. */
    override fun toString(): String =
        "GetObjectResponse(bytes=${body.size}, contentType=$contentType, eTag=$eTag)"
}

/** A PUT. **Not a data class** — it carries the body. */
public class PutObjectRequest(
    public val bucket: String,
    public val key: String,
    public val body: ByteArray,
    public val contentType: String? = null,
    public val cacheControl: String? = null,
    public val contentEncoding: String? = null,
    public val contentDisposition: String? = null,
    /** Written as `x-amz-meta-*`. */
    public val metadata: Map<String, String> = emptyMap(),
    /** `*` for "only if absent" — a conditional create. */
    public val ifNoneMatch: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PutObjectRequest &&
                bucket == other.bucket &&
                key == other.key &&
                body.contentEquals(other.body) &&
                contentType == other.contentType &&
                cacheControl == other.cacheControl &&
                contentEncoding == other.contentEncoding &&
                contentDisposition == other.contentDisposition &&
                metadata == other.metadata &&
                ifNoneMatch == other.ifNoneMatch
            )

    override fun hashCode(): Int {
        var result = bucket.hashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun toString(): String = "PutObjectRequest(s3://$bucket/$key, bytes=${body.size})"
}

public data class PutObjectResponse(
    val eTag: String? = null,
    val versionId: String? = null,
)

public data class HeadObjectRequest(
    val bucket: String,
    val key: String,
    val versionId: String? = null,
)

public data class HeadObjectResponse(
    val contentLength: Long,
    val contentType: String? = null,
    val eTag: String? = null,
    val lastModified: String? = null,
    val versionId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

public data class DeleteObjectRequest(
    val bucket: String,
    val key: String,
    val versionId: String? = null,
)

public data class DeleteObjectResponse(
    val versionId: String? = null,
    val deleteMarker: Boolean = false,
)
