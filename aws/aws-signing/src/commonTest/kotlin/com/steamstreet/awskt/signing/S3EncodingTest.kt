package com.steamstreet.awskt.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The S3 signing mode: `doubleUriEncode = false` and `normalizeUriPath = false`, together.
 *
 * AWS's corpus is not silent on these flags individually — seven cases cover `normalize = false`
 * and `get-percent-single-encoded` covers `double_uri_encode = false`. What no AWS fixture covers is
 * **both at once under S3-shaped keys**, which is exactly the combination S3 uses. This file is that
 * supplement and nothing more.
 *
 * The failure this guards against is nasty: an S3 key containing `.`, `..` or `//` passes every
 * test in CI and fails every request in production, because a bucket may legitimately hold an object
 * literally named `my-object//example//photo.user` and normalizing the path signs a different
 * resource than the one being requested.
 */
class S3EncodingTest {

    @Test
    fun s3ModeLeavesAnEncodedPathUntouched() {
        val wire = "/a%20b/c..d/e%2Bf/%E6%97%A5%E6%9C%AC%E8%AA%9E"
        assertEquals(
            wire,
            canonicalUri(wire, doubleUriEncode = false, normalize = false),
            "S3 signs the path exactly as it appears on the wire",
        )
    }

    @Test
    fun s3ModePreservesDotSegments() {
        // A real key. Normalizing this would sign "/bucket/d" and request "/bucket/a/../d".
        val wire = "/bucket/a/../d"
        assertEquals("/bucket/a/../d", canonicalUri(wire, doubleUriEncode = false, normalize = false))
    }

    @Test
    fun s3ModePreservesEmptySegments() {
        val wire = "/my-object//example//photo.user"
        assertEquals(wire, canonicalUri(wire, doubleUriEncode = false, normalize = false))
    }

    @Test
    fun s3ModeAndDefaultModeDisagree() {
        // The plan's specific requirement: `a/../b` must canonicalize two different ways.
        val wire = "/a/../b"

        val s3 = canonicalUri(wire, doubleUriEncode = false, normalize = false)
        val default = canonicalUri(wire, doubleUriEncode = true, normalize = true)

        assertEquals("/a/../b", s3)
        assertEquals("/b", default)
        assertNotEquals(s3, default)
    }

    @Test
    fun defaultModeDoubleEncodesAnAlreadyEncodedPath() {
        // Non-S3 services encode the wire path a second time; `%` becomes `%25`.
        assertEquals(
            "/a%2520b",
            canonicalUri("/a%20b", doubleUriEncode = true, normalize = true),
        )
    }

    @Test
    fun keyEncodingProducesTheExpectedWireForm() {
        // What a caller must do to turn a raw S3 key into the path passed to the signer.
        assertEquals(
            "a%20b/c..d/e%2Bf/%E6%97%A5%E6%9C%AC%E8%AA%9E",
            sigV4UriEncode("a b/c..d/e+f/日本語", encodeSlash = false),
        )
    }

    @Test
    fun reservedCharactersInKeysAreEncoded() {
        // `?` and `#` in particular: leaving either unencoded silently truncates the key.
        assertEquals("%3F", sigV4UriEncode("?"))
        assertEquals("%23", sigV4UriEncode("#"))
        assertEquals("%3A", sigV4UriEncode(":"))
        assertEquals("%25", sigV4UriEncode("%"))
        assertEquals("%2B", sigV4UriEncode("+"))
        assertEquals("%20", sigV4UriEncode(" "))
        assertEquals("%2F", sigV4UriEncode("/"))
        assertEquals("/", sigV4UriEncode("/", encodeSlash = false))
    }

    @Test
    fun unreservedCharactersAreNeverEncoded() {
        val unreserved = "-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        assertEquals(unreserved, sigV4UriEncode(unreserved))
    }

    @Test
    fun sigV4EncodesSubDelimitersThatGeneralUrlEncodersLeaveAlone() {
        // This is where a platform URL encoder diverges from SigV4 and produces a signature that
        // fails on a small, hard-to-reproduce fraction of requests.
        for (c in listOf('!', '$', '&', '\'', '(', ')', '*', ',', ';', '=', '@')) {
            val encoded = sigV4UriEncode(c.toString())
            assertEquals(3, encoded.length, "'$c' must be percent-encoded, got '$encoded'")
            assertEquals('%', encoded[0])
        }
    }

    @Test
    fun percentEncodingUsesUppercaseHex() {
        // Lowercase hex is a valid URL but an invalid canonical request.
        assertEquals("%E6%97%A5", sigV4UriEncode("日"))
    }
}
