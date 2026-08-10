package com.steamstreet.awskt.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the signer with AWS's own SigV4 test-suite corpus.
 *
 * This is the project's primary oracle for signing correctness, and the only one that exists before
 * any HTTP client or service client does. See `src/commonTest/vectors/README.md` for provenance and
 * for why three cases are skipped.
 *
 * Each case asserts three levels — canonical request, string-to-sign, signature — so a failure
 * points at one normalization rule rather than at an opaque hex mismatch.
 */
class SigV4VectorTest {

    /**
     * Cases with no assertable expectation in either mode. These test inbound header parsing or a
     * header we deliberately refuse to sign; they are not signer bugs. See the vectors README.
     */
    private val skipped = setOf(
        // obs-fold continuation lines: a shape we parse but never produce.
        "get-header-value-multiline",
        // Both sign `content-length`, which we deliberately exclude.
        "post-x-www-form-urlencoded",
        "post-x-www-form-urlencoded-parameters",
    )

    @Test
    fun headerSigningMatchesAwsVectors() {
        var asserted = 0
        for (case in sigV4Vectors) {
            if (case.name in skipped) continue
            val expectedCanonical = case.headerCanonicalRequest ?: continue

            val signed = case.sign(SignatureLocation.HEADERS)

            assertEquals(expectedCanonical, signed.canonicalRequest, "${case.name}: canonical request")
            assertEquals(case.headerStringToSign, signed.stringToSign, "${case.name}: string to sign")
            assertEquals(case.headerSignature, signed.signature, "${case.name}: signature")
            asserted++
        }
        assertEquals(37, asserted, "expected 37 assertable header-mode cases")
    }

    @Test
    fun querySigningMatchesAwsVectors() {
        var asserted = 0
        for (case in sigV4Vectors) {
            if (case.name in skipped) continue
            val expectedCanonical = case.queryCanonicalRequest ?: continue

            val signed = case.sign(SignatureLocation.QUERY_STRING)

            assertEquals(expectedCanonical, signed.canonicalRequest, "${case.name}: canonical request")
            assertEquals(case.queryStringToSign, signed.stringToSign, "${case.name}: string to sign")
            assertEquals(case.querySignature, signed.signature, "${case.name}: signature")
            asserted++
        }
        assertEquals(37, asserted, "expected 37 assertable query-mode cases")
    }

    /**
     * Guards the arithmetic the plan rests on. If the corpus is ever re-vendored and these move,
     * the assertion counts above are stale and the coverage claim is wrong.
     */
    @Test
    fun corpusShapeIsWhatWeThinkItIs() {
        assertEquals(42, sigV4Vectors.size, "case directories")
        assertEquals(40, sigV4Vectors.count { it.headerCanonicalRequest != null }, "header-capable")
        assertEquals(40, sigV4Vectors.count { it.queryCanonicalRequest != null }, "query-capable")
    }

    /**
     * The session-token path is worth calling out: Lambda execution-role credentials always carry a
     * token, so 100% of production signing takes this branch, while a `StaticCredentialsProvider`
     * against LocalStack takes it 0% of the time.
     */
    @Test
    fun sessionTokenIsSignedInsideTheCanonicalQueryWhenPresigning() {
        val case = sigV4Vectors.single { it.name == "get-vanilla-with-session-token" }
        val signed = case.sign(SignatureLocation.QUERY_STRING)

        assertTrue(
            signed.canonicalRequest.contains("X-Amz-Security-Token="),
            "the token must be signed inside the canonical query, not sent alongside it",
        )
        assertEquals(listOf("host"), signed.signedHeaderNames)
    }

    @Test
    fun omitSessionTokenExcludesItFromTheSignature() {
        val before = sigV4Vectors.single { it.name == "post-sts-header-before" }
        val after = sigV4Vectors.single { it.name == "post-sts-header-after" }

        assertTrue(before.sign(SignatureLocation.HEADERS).signedHeaderNames.contains("x-amz-security-token"))
        assertTrue(!after.sign(SignatureLocation.HEADERS).signedHeaderNames.contains("x-amz-security-token"))

        // ...but it still has to be *sent*, or the request is unauthenticated.
        val sent = after.sign(SignatureLocation.HEADERS).headers.map { it.first.lowercase() }
        assertTrue("x-amz-security-token" in sent)
    }
}

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

private fun SigV4VectorCase.sign(location: SignatureLocation): SignedRequest {
    val parsed = parseHttpRequest(request)

    val config = SigV4Config(
        region = region,
        service = service,
        location = location,
        expiresInSeconds = if (location == SignatureLocation.QUERY_STRING) expirationInSeconds else null,
        // The corpus signs the (empty) body even when presigning — UNSIGNED-PAYLOAD is an S3-layer
        // policy, not a signer-level rule, so it must not be forced here.
        payloadHash = if (signBody) PayloadHash.Compute else PayloadHash.EmptyBody,
        signedBodyHeader = if (signBody && location == SignatureLocation.HEADERS) {
            SignedBodyHeader.X_AMZ_CONTENT_SHA256
        } else {
            SignedBodyHeader.NONE
        },
        doubleUriEncode = doubleUriEncode,
        normalizeUriPath = normalize,
        omitSessionToken = omitSessionToken,
    )

    return SigV4.sign(
        request = SigningRequest(
            method = parsed.method,
            path = parsed.path,
            host = parsed.host,
            queryParameters = parsed.queryParameters,
            headers = parsed.headers,
            body = parsed.body.encodeToByteArray(),
        ),
        credentials = AwsCredentials(accessKeyId, secretAccessKey, sessionToken),
        config = config,
        signingInstantMillis = parseIso8601Utc(timestamp),
    )
}

private class ParsedHttpRequest(
    val method: String,
    val path: String,
    val host: String,
    val queryParameters: List<Pair<String, String>>,
    val headers: List<Pair<String, String>>,
    val body: String,
)

/** Parses the corpus's raw `request.txt` form: request line, headers, blank line, body. */
private fun parseHttpRequest(raw: String): ParsedHttpRequest {
    val normalized = raw.replace("\r\n", "\n")
    val headerBlockEnd = normalized.indexOf("\n\n")
    val headSection = if (headerBlockEnd >= 0) normalized.substring(0, headerBlockEnd) else normalized
    val body = if (headerBlockEnd >= 0) normalized.substring(headerBlockEnd + 2) else ""

    val lines = headSection.split("\n").filter { it.isNotEmpty() }
    val requestLine = lines.first()

    val method = requestLine.substringBefore(' ')
    val target = requestLine.substringAfter(' ').substringBeforeLast(" HTTP/")

    val path = target.substringBefore('?')
    val queryString = if ('?' in target) target.substringAfter('?') else ""

    val queryParameters = if (queryString.isEmpty()) {
        emptyList()
    } else {
        queryString.split('&').filter { it.isNotEmpty() }.map { pair ->
            val key = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            // The signer takes decoded parameters and encodes them itself, which is what makes
            // `ሴ=bar` and `%E1%88%B4=Value1` canonicalize identically.
            percentDecode(key) to percentDecode(value)
        }
    }

    var host = ""
    val headers = ArrayList<Pair<String, String>>()
    for (line in lines.drop(1)) {
        val name = line.substringBefore(':')
        val value = line.substringAfter(':')
        if (name.equals("host", ignoreCase = true)) host = value.trim() else headers += name to value
    }

    return ParsedHttpRequest(method, path, host, queryParameters, headers, body)
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

private fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    var literalStart = 0

    fun flushLiteral(end: Int) {
        if (end > literalStart) {
            for (b in value.substring(literalStart, end).encodeToByteArray()) bytes.add(b)
        }
    }

    while (i < value.length) {
        if (value[i] == '%' && i + 2 < value.length) {
            val hi = hexValue(value[i + 1])
            val lo = hexValue(value[i + 2])
            if (hi >= 0 && lo >= 0) {
                flushLiteral(i)
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
                literalStart = i
                continue
            }
        }
        i++
    }
    flushLiteral(value.length)
    return bytes.toByteArray().decodeToString()
}

/** Parses `yyyy-MM-ddTHH:mm:ssZ` to epoch millis, without a date library. */
internal fun parseIso8601Utc(value: String): Long {
    val year = value.substring(0, 4).toLong()
    val month = value.substring(5, 7).toLong()
    val day = value.substring(8, 10).toLong()
    val hour = value.substring(11, 13).toLong()
    val minute = value.substring(14, 16).toLong()
    val second = value.substring(17, 19).toLong()

    // Howard Hinnant's days_from_civil, the inverse of the arithmetic in Sigv4Time.
    val y = if (month <= 2L) year - 1L else year
    val era = (if (y >= 0L) y else y - 399L) / 400L
    val yearOfEra = y - era * 400L
    val monthPrime = if (month > 2L) month - 3L else month + 9L
    val dayOfYear = (153L * monthPrime + 2L) / 5L + day - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    val days = era * 146_097L + dayOfEra - 719_468L

    return ((days * 86_400L) + hour * 3_600L + minute * 60L + second) * 1_000L
}
