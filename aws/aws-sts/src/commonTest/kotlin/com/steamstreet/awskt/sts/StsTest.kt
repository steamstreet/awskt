package com.steamstreet.awskt.sts

import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.signing.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

internal class StsHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessSts(
    harness: StsHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): Sts {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultSts(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("sts", "us-west-2", "https://sts.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = STS_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

/** Splits a form body back into fields, so a test can assert one without depending on the order. */
private fun formFields(body: String): Map<String, String> = body.split("&").associate { pair ->
    val (k, v) = pair.split("=", limit = 2)
    decode(k) to decode(v)
}

/** Percent-decoding, so a test reads what STS would read rather than what we happened to write. */
private fun decode(value: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < value.length) {
        if (value[i] == '%') {
            bytes += value.substring(i + 1, i + 3).toInt(16).toByte()
            i += 3
        } else {
            bytes += value[i].code.toByte()
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val ROLE = "arn:aws:iam::123456789012:role/demo"

internal const val ASSUME_ROLE_OK = """<AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <AssumeRoleResult>
    <SourceIdentity>Alice</SourceIdentity>
    <AssumedRoleUser>
      <Arn>arn:aws:sts::123456789012:assumed-role/demo/reports</Arn>
      <AssumedRoleId>ARO123EXAMPLE123:reports</AssumedRoleId>
    </AssumedRoleUser>
    <Credentials>
      <AccessKeyId>ASIAIOSFODNN7EXAMPLE</AccessKeyId>
      <SecretAccessKey>wJalrXUtnFEMI/K7MDENG/bPxRfiCYzEXAMPLEKEY</SecretAccessKey>
      <SessionToken>AQoDYXdzEPT//////////wEXAMPLEtc764</SessionToken>
      <Expiration>2023-11-14T23:13:20Z</Expiration>
    </Credentials>
    <PackedPolicySize>6</PackedPolicySize>
  </AssumeRoleResult>
  <ResponseMetadata><RequestId>c6104cbe-af31-11e0-8154-cbc7ccf896c7</RequestId></ResponseMetadata>
</AssumeRoleResponse>"""

private const val CALLER_IDENTITY_OK = """<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <GetCallerIdentityResult>
    <Arn>arn:aws:sts::123456789012:assumed-role/demo/reports</Arn>
    <UserId>ARO123EXAMPLE123:reports</UserId>
    <Account>123456789012</Account>
  </GetCallerIdentityResult>
  <ResponseMetadata><RequestId>01234567-89ab-cdef-0123-456789abcdef</RequestId></ResponseMetadata>
</GetCallerIdentityResponse>"""

private fun errorResponse(code: String, message: String) =
    """<ErrorResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <Error><Type>Sender</Type><Code>$code</Code><Message>$message</Message></Error>
  <RequestId>req-err</RequestId>
</ErrorResponse>"""

class StsProtocolTest {

    @Test
    fun postsAFormEncodedBodyWithNoTargetHeader() = runTest {
        val h = StsHarness()
        harnessSts(h) { ASSUME_ROLE_OK to HttpStatusCode.OK }.assumeRole(AssumeRoleRequest(ROLE, "reports"))

        val request = h.requests.single()
        assertEquals("POST", request.method.value)
        assertEquals("sts.us-west-2.amazonaws.com", request.url.host)
        assertNull(request.headers["X-Amz-Target"])
        assertEquals(
            "application/x-www-form-urlencoded",
            (request.body as OutgoingContent.ByteArrayContent).contentType.toString(),
        )
        assertContains(request.headers["Authorization"]!!, "/us-west-2/sts/aws4_request")
    }

    @Test
    fun assumeRoleEncodesEveryModelledField() = runTest {
        val h = StsHarness()
        harnessSts(h) { ASSUME_ROLE_OK to HttpStatusCode.OK }.assumeRole(
            AssumeRoleRequest(
                roleArn = ROLE,
                roleSessionName = "reports",
                duration = 15.minutes,
                externalId = "ext-1",
                policyArns = listOf("arn:aws:iam::aws:policy/ReadOnlyAccess", "arn:aws:iam::aws:policy/Other"),
                tags = mapOf("team" to "data", "env" to "prod"),
                transitiveTagKeys = listOf("team"),
                sourceIdentity = "alice",
            ),
        )

        assertEquals(
            mapOf(
                "Action" to "AssumeRole",
                "Version" to "2011-06-15",
                "RoleArn" to ROLE,
                "RoleSessionName" to "reports",
                "DurationSeconds" to "900",
                "ExternalId" to "ext-1",
                "SourceIdentity" to "alice",
                // Indexed from 1. From 0, STS would ignore the first entry without complaint.
                "PolicyArns.member.1.arn" to "arn:aws:iam::aws:policy/ReadOnlyAccess",
                "PolicyArns.member.2.arn" to "arn:aws:iam::aws:policy/Other",
                "Tags.member.1.Key" to "team",
                "Tags.member.1.Value" to "data",
                "Tags.member.2.Key" to "env",
                "Tags.member.2.Value" to "prod",
                "TransitiveTagKeys.member.1" to "team",
            ),
            formFields(h.bodies.single()),
        )
    }

    @Test
    fun omitsUnsetOptionalFields() = runTest {
        val h = StsHarness()
        harnessSts(h) { ASSUME_ROLE_OK to HttpStatusCode.OK }.assumeRole(AssumeRoleRequest(ROLE, "reports"))

        assertEquals(
            setOf("Action", "Version", "RoleArn", "RoleSessionName"),
            formFields(h.bodies.single()).keys,
        )
    }

    /**
     * A session policy is JSON full of spaces and quotes. A `+`-for-space encoder produces a body
     * STS decodes into a different policy, so the raw body is asserted, not the decoded one.
     */
    @Test
    fun sessionPolicyIsPercentEncodedWithoutPlusForSpace() = runTest {
        val h = StsHarness()
        val policy = """{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": "s3:Get*"}]}"""
        harnessSts(h) { ASSUME_ROLE_OK to HttpStatusCode.OK }
            .assumeRole(AssumeRoleRequest(ROLE, "reports", policy = policy))

        val raw = h.bodies.single()
        assertFalse('+' in raw, "a space must encode as %20, never +: $raw")
        assertContains(raw, "%7B%22Version%22%3A%20%222012-10-17%22")
        assertEquals(policy, formFields(raw)["Policy"])
    }

    @Test
    fun readsCredentialsAndTheirExpiry() = runTest {
        val response = harnessSts(StsHarness()) { ASSUME_ROLE_OK to HttpStatusCode.OK }
            .assumeRole(AssumeRoleRequest(ROLE, "reports"))

        val credentials = response.credentials
        assertEquals("ASIAIOSFODNN7EXAMPLE", credentials.accessKeyId)
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYzEXAMPLEKEY", credentials.secretAccessKey)
        assertEquals("AQoDYXdzEPT//////////wEXAMPLEtc764", credentials.sessionToken)
        assertEquals(1_700_003_600_000, credentials.expiresAtEpochMillis)
        assertEquals(
            AssumedRoleUser("arn:aws:sts::123456789012:assumed-role/demo/reports", "ARO123EXAMPLE123:reports"),
            response.assumedRoleUser,
        )
        assertEquals(6, response.packedPolicySize)
        assertEquals("Alice", response.sourceIdentity)
    }

    @Test
    fun printingTheResponseDoesNotLeakTheSecret() = runTest {
        val response = harnessSts(StsHarness()) { ASSUME_ROLE_OK to HttpStatusCode.OK }
            .assumeRole(AssumeRoleRequest(ROLE, "reports"))

        val printed = response.toString()
        assertFalse("wJalrXUtnFEMI" in printed, printed)
        assertFalse("AQoDYXdz" in printed, printed)
    }

    @Test
    fun aSuccessWithoutCredentialsIsAnErrorNotABlankCredential() = runTest {
        val sts = harnessSts(StsHarness()) {
            "<AssumeRoleResponse><AssumeRoleResult></AssumeRoleResult></AssumeRoleResponse>" to HttpStatusCode.OK
        }
        val e = assertFailsWith<StsException> { sts.assumeRole(AssumeRoleRequest(ROLE, "reports")) }
        assertContains(e.message!!, "<Credentials>")
    }

    @Test
    fun getCallerIdentity() = runTest {
        val h = StsHarness()
        val identity = harnessSts(h) { CALLER_IDENTITY_OK to HttpStatusCode.OK }.getCallerIdentity()

        assertEquals(
            CallerIdentity(
                account = "123456789012",
                arn = "arn:aws:sts::123456789012:assumed-role/demo/reports",
                userId = "ARO123EXAMPLE123:reports",
            ),
            identity,
        )
        assertEquals(
            mapOf("Action" to "GetCallerIdentity", "Version" to "2011-06-15"),
            formFields(h.bodies.single()),
        )
    }

    @Test
    fun defaultEndpointIsRegional() {
        val endpoint = resolveEndpoint("sts", "eu-west-1", getEnv = { null })
        assertContains(endpoint.toString(), "https://sts.eu-west-1.amazonaws.com")
    }
}

class StsErrorTest {

    private suspend fun failWith(code: String, status: HttpStatusCode): StsException {
        val sts = harnessSts(StsHarness()) { errorResponse(code, "it failed") to status }
        return assertFailsWith<StsException> { sts.assumeRole(AssumeRoleRequest(ROLE, "reports")) }
    }

    @Test
    fun modelledCodesMapToTheirTypes() = runTest {
        assertIs<ExpiredTokenException>(failWith("ExpiredTokenException", HttpStatusCode.BadRequest))
        assertIs<MalformedPolicyDocumentException>(failWith("MalformedPolicyDocument", HttpStatusCode.BadRequest))
        assertIs<PackedPolicyTooLargeException>(failWith("PackedPolicyTooLarge", HttpStatusCode.BadRequest))
        assertIs<RegionDisabledException>(failWith("RegionDisabledException", HttpStatusCode.Forbidden))
    }

    /** The failure a caller actually meets: a trust policy that does not name them. */
    @Test
    fun accessDeniedKeepsItsCodeAndMessage() = runTest {
        val e = failWith("AccessDenied", HttpStatusCode.Forbidden)
        assertEquals(StsException::class, e::class)
        assertEquals("AccessDenied", e.code)
        assertEquals(403, e.statusCode)
        assertContains(e.message!!, "it failed")
    }
}
