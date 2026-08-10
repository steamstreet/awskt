package com.steamstreet.awskt.signing

/**
 * The parts of an HTTP request that participate in a SigV4 signature.
 *
 * @param method the HTTP method, e.g. `GET`.
 * @param path the request path **as it will appear on the wire**. Whether it is already
 *   percent-encoded is the caller's business: with [SigV4Config.doubleUriEncode] set (the default,
 *   and correct everywhere except S3) this string is encoded once more; with it clear, the string is
 *   used verbatim.
 * @param host the HTTP authority, **including a non-default port** — `example.com` for 443, but
 *   `localhost:4566` for LocalStack. This is passed in rather than derived, because the same string
 *   must be both signed and sent as the `Host` header, and a mismatch is invisible until production.
 * @param queryParameters query parameters in **decoded** form; the signer encodes them.
 * @param headers request headers in encounter order. Duplicates are permitted and are joined with
 *   commas in that order. Hop-by-hop and volatile headers are dropped during canonicalization.
 * @param body the request body, used only when [SigV4Config.payloadHash] is [PayloadHash.Compute].
 */
public class SigningRequest(
    public val method: String,
    public val path: String,
    public val host: String,
    public val queryParameters: List<Pair<String, String>> = emptyList(),
    public val headers: List<Pair<String, String>> = emptyList(),
    public val body: ByteArray = ByteArray(0),
) {
    override fun toString(): String =
        "SigningRequest(method=$method, path=$path, host=$host)"
}

/**
 * The result of signing.
 *
 * [canonicalRequest] and [stringToSign] are part of the public surface on purpose. They are what
 * turn a `SignatureDoesNotMatch` from an opaque hex mismatch into a diff against exactly one
 * normalization rule, and they are what let the AWS fixture corpus assert three levels per case
 * instead of one.
 *
 * @param headers headers to send, including `Authorization` when signing via headers.
 * @param queryParameters query parameters to send, including the signature when presigning.
 * @param signedHeaderNames the lowercase header names covered by the signature, in signing order.
 */
public class SignedRequest(
    public val headers: List<Pair<String, String>>,
    public val queryParameters: List<Pair<String, String>>,
    public val signedHeaderNames: List<String>,
    public val canonicalRequest: String,
    public val stringToSign: String,
    public val signature: String,
) {
    /**
     * Renders the query string for a presigned URL. Parameters are emitted in signing order, which
     * is *not* required to be canonical order — sorting is a canonicalization rule, not a
     * URL-construction one.
     */
    public fun encodedQueryString(): String =
        queryParameters.joinToString("&") { (k, v) -> "${sigV4UriEncode(k)}=${sigV4UriEncode(v)}" }

    /**
     * Deliberately omits the signature and every `X-Amz-` credential-bearing parameter: a presigned
     * URL *is* a credential, and this object routinely ends up in log lines.
     */
    override fun toString(): String =
        "SignedRequest(signedHeaders=${signedHeaderNames.joinToString(";")})"
}
