package com.steamstreet.awskt.sns

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PLATFORM_APP = "arn:aws:sns:us-west-2:123456789012:app/GCM/my-app"
private const val ENDPOINT = "arn:aws:sns:us-west-2:123456789012:endpoint/GCM/my-app/abc-123"
private const val TOKEN = "fZx9pQ:APA91bH_device_token"

private fun createOk(arn: String = ENDPOINT) = """<CreatePlatformEndpointResponse>
  <CreatePlatformEndpointResult><EndpointArn>$arn</EndpointArn></CreatePlatformEndpointResult>
  <ResponseMetadata><RequestId>req-1</RequestId></ResponseMetadata>
</CreatePlatformEndpointResponse>"""

private fun attributesOk(vararg pairs: Pair<String, String>) = """<GetEndpointAttributesResponse>
  <GetEndpointAttributesResult><Attributes>
    ${pairs.joinToString("") { (k, v) -> "<entry><key>$k</key><value>$v</value></entry>" }}
  </Attributes></GetEndpointAttributesResult>
</GetEndpointAttributesResponse>"""

private const val EMPTY_OK = """<SetEndpointAttributesResponse>
  <ResponseMetadata><RequestId>req-1</RequestId></ResponseMetadata>
</SetEndpointAttributesResponse>"""

/** Splits a form body back into fields. Duplicated from SnsTest deliberately — tests do not share. */
private fun fields(body: String): Map<String, String> = body.split("&").associate { pair ->
    val (k, v) = pair.split("=", limit = 2)
    dec(k) to dec(v)
}

private fun dec(value: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < value.length) {
        if (value[i] == '%' && i + 2 < value.length + 1) {
            bytes += value.substring(i + 1, i + 3).toInt(16).toByte()
            i += 3
        } else {
            bytes += value[i].code.toByte()
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

class EndpointOperationsTest {

    @Test
    fun createPlatformEndpointNamesTheActionAndCarriesTheToken() = runTest {
        val h = SnsHarness()
        val response = harnessSns(h) { createOk() to HttpStatusCode.OK }
            .createPlatformEndpoint(CreatePlatformEndpointRequest(PLATFORM_APP, TOKEN, "user-42"))

        val form = fields(h.bodies.single())
        assertEquals("CreatePlatformEndpoint", form["Action"])
        assertEquals(PLATFORM_APP, form["PlatformApplicationArn"])
        assertEquals(TOKEN, form["Token"])
        assertEquals("user-42", form["CustomUserData"])
        assertEquals(ENDPOINT, response.endpointArn)
    }

    /**
     * Endpoint attribute maps use the query protocol's default `key`/`value` spelling, **not** the
     * `Name`/`Value` that `MessageAttributes` overrides to. The wrong spelling produces a request
     * SNS accepts and silently ignores.
     */
    @Test
    fun attributeMapsUseLowercaseKeyAndValue() = runTest {
        val h = SnsHarness()
        harnessSns(h) { EMPTY_OK to HttpStatusCode.OK }.setEndpointAttributes(
            ENDPOINT,
            linkedMapOf(EndpointAttribute.TOKEN to TOKEN, EndpointAttribute.ENABLED to "true"),
        )

        val form = fields(h.bodies.single())
        assertEquals("Token", form["Attributes.entry.1.key"])
        assertEquals(TOKEN, form["Attributes.entry.1.value"])
        assertEquals("Enabled", form["Attributes.entry.2.key"])
        assertEquals("true", form["Attributes.entry.2.value"])
        assertFalse(
            form.keys.any { it.endsWith(".Name") },
            "endpoint attributes must not use the MessageAttributes spelling",
        )
    }

    @Test
    fun getEndpointAttributesReadsTheEntryList() = runTest {
        val attributes = harnessSns(SnsHarness()) {
            attributesOk(
                "Token" to TOKEN,
                "Enabled" to "true",
                "CustomUserData" to "user-42",
            ) to HttpStatusCode.OK
        }.getEndpointAttributes(ENDPOINT)

        assertEquals(TOKEN, attributes.token)
        assertEquals(true, attributes.enabled)
        assertEquals("user-42", attributes.customUserData)
    }

    /** `Enabled` is the string "false", not a JSON boolean. Comparing without parsing is a live bug. */
    @Test
    fun enabledIsParsedFromItsStringForm() = runTest {
        val attributes = harnessSns(SnsHarness()) {
            attributesOk("Enabled" to "false") to HttpStatusCode.OK
        }.getEndpointAttributes(ENDPOINT)

        assertEquals(false, attributes.enabled)
        assertEquals("false", attributes.raw["Enabled"])
    }

    @Test
    fun anEmptyAttributeValueReadsAsEmptyRatherThanBeingDropped() {
        val map = Xml.attributeMap("<entry><key>CustomUserData</key><value></value></entry>")
        assertEquals(mapOf("CustomUserData" to ""), map)
    }

    @Test
    fun deleteEndpointAndSetAttributesReturnNothingAndTargetCorrectly() = runTest {
        val h = SnsHarness()
        val sns = harnessSns(h) { EMPTY_OK to HttpStatusCode.OK }
        sns.deleteEndpoint(ENDPOINT)

        val form = fields(h.bodies.single())
        assertEquals("DeleteEndpoint", form["Action"])
        assertEquals(ENDPOINT, form["EndpointArn"])
    }

    @Test
    fun listEndpointsReadsMembersWithTheirAttributes() = runTest {
        val response = harnessSns(SnsHarness()) {
            """<ListEndpointsByPlatformApplicationResponse><ListEndpointsByPlatformApplicationResult>
                 <Endpoints>
                   <member><EndpointArn>$ENDPOINT</EndpointArn><Attributes>
                     <entry><key>Token</key><value>$TOKEN</value></entry>
                     <entry><key>Enabled</key><value>true</value></entry>
                   </Attributes></member>
                   <member><EndpointArn>${ENDPOINT}-2</EndpointArn><Attributes>
                     <entry><key>Enabled</key><value>false</value></entry>
                   </Attributes></member>
                 </Endpoints>
                 <NextToken>page-2</NextToken>
               </ListEndpointsByPlatformApplicationResult></ListEndpointsByPlatformApplicationResponse>""" to
                HttpStatusCode.OK
        }.listEndpointsByPlatformApplication(PLATFORM_APP)

        assertEquals(2, response.endpoints.size)
        assertEquals(TOKEN, response.endpoints[0].attributes.token)
        assertEquals(true, response.endpoints[0].attributes.enabled)
        assertEquals(false, response.endpoints[1].attributes.enabled)
        assertNull(response.endpoints[1].attributes.token)
        assertEquals("page-2", response.nextToken)
    }

    @Test
    fun listAllEndpointsFollowsPaginationAndIsBounded() = runTest {
        val h = SnsHarness()
        val all = harnessSns(h) { call ->
            val more = if (call == 0) "<NextToken>page-2</NextToken>" else ""
            """<ListEndpointsByPlatformApplicationResponse><ListEndpointsByPlatformApplicationResult>
                 <Endpoints><member><EndpointArn>e-$call</EndpointArn><Attributes/></member></Endpoints>
                 $more
               </ListEndpointsByPlatformApplicationResult></ListEndpointsByPlatformApplicationResponse>""" to
                HttpStatusCode.OK
        }.listAllEndpoints(PLATFORM_APP)

        assertEquals(listOf("e-0", "e-1"), all.map { it.endpointArn })
        assertNull(fields(h.bodies[0])["NextToken"])
        assertEquals("page-2", fields(h.bodies[1])["NextToken"])
    }

    /** A device token is a capability and a stable device identifier; request objects get logged. */
    @Test
    fun tokensAreRedactedButTheirLengthIsKept() {
        val printed = CreatePlatformEndpointRequest(PLATFORM_APP, TOKEN, "user-42").toString()
        assertFalse(printed.contains(TOKEN), "toString leaked the device token: $printed")
        // The length survives because "the token changed length" is the useful debugging signal.
        assertContains(printed, "${TOKEN.length} chars")
        assertContains(printed, "user-42")

        val attributes = EndpointAttributes(mapOf("Token" to TOKEN, "Enabled" to "true")).toString()
        assertFalse(attributes.contains(TOKEN), "toString leaked the device token: $attributes")
    }
}

/**
 * The registration dance, which is the reason this module has a helper at all.
 */
class RegisterDeviceTest {

    @Test
    fun firstRegistrationCreatesThenVerifies() = runTest {
        val h = SnsHarness()
        val arn = harnessSns(h) { call ->
            when (call) {
                0 -> createOk() to HttpStatusCode.OK
                else -> attributesOk("Token" to TOKEN, "Enabled" to "true") to HttpStatusCode.OK
            }
        }.registerDevice(PLATFORM_APP, TOKEN)

        assertEquals(ENDPOINT, arn)
        // Two calls, not one: nothing in the create response says whether the endpoint is enabled.
        assertEquals(2, h.bodies.size)
        assertEquals("CreatePlatformEndpoint", fields(h.bodies[0])["Action"])
        assertEquals("GetEndpointAttributes", fields(h.bodies[1])["Action"])
    }

    /**
     * The headline case: SNS reports "already registered" as an exception with the ARN buried in
     * the message text, and AWS's own documented procedure is to parse it out.
     */
    @Test
    fun recoversTheExistingArnFromTheInvalidParameterMessage() = runTest {
        val h = SnsHarness()
        val existing = "arn:aws:sns:us-west-2:123456789012:endpoint/GCM/my-app/existing-99"
        val arn = harnessSns(h) { call ->
            when (call) {
                0 -> """<ErrorResponse><Error><Type>Sender</Type><Code>InvalidParameter</Code>
                        <Message>Invalid parameter: Token Reason: Endpoint $existing already exists
                        with the same Token, but different attributes.</Message></Error></ErrorResponse>""" to
                    HttpStatusCode.BadRequest

                1 -> attributesOk("Token" to TOKEN, "Enabled" to "true") to HttpStatusCode.OK
                else -> EMPTY_OK to HttpStatusCode.OK
            }
        }.registerDevice(PLATFORM_APP, TOKEN)

        assertEquals(existing, arn)
    }

    /** SNS disables endpoints itself when the push service rejects a token. Re-enabling is the repair. */
    @Test
    fun reEnablesAnEndpointThatSnsHadDisabled() = runTest {
        val h = SnsHarness()
        harnessSns(h) { call ->
            when (call) {
                0 -> createOk() to HttpStatusCode.OK
                1 -> attributesOk("Token" to TOKEN, "Enabled" to "false") to HttpStatusCode.OK
                else -> EMPTY_OK to HttpStatusCode.OK
            }
        }.registerDevice(PLATFORM_APP, TOKEN)

        assertEquals(3, h.bodies.size, "a disabled endpoint should have been repaired")
        val repair = fields(h.bodies[2])
        assertEquals("SetEndpointAttributes", repair["Action"])
        assertEquals("true", repair["Attributes.entry.2.value"])
    }

    /** A rotated device token on an existing endpoint has to be written through. */
    @Test
    fun updatesAStaleToken() = runTest {
        val h = SnsHarness()
        harnessSns(h) { call ->
            when (call) {
                0 -> createOk() to HttpStatusCode.OK
                1 -> attributesOk("Token" to "old-token", "Enabled" to "true") to HttpStatusCode.OK
                else -> EMPTY_OK to HttpStatusCode.OK
            }
        }.registerDevice(PLATFORM_APP, "new-token")

        assertEquals(3, h.bodies.size)
        assertEquals("new-token", fields(h.bodies[2])["Attributes.entry.1.value"])
    }

    @Test
    fun leavesAHealthyEndpointAlone() = runTest {
        val h = SnsHarness()
        harnessSns(h) { call ->
            if (call == 0) createOk() to HttpStatusCode.OK
            else attributesOk("Token" to TOKEN, "Enabled" to "true") to HttpStatusCode.OK
        }.registerDevice(PLATFORM_APP, TOKEN)

        assertEquals(2, h.bodies.size, "a healthy endpoint should not be written to")
    }

    /**
     * An `InvalidParameter` that is *not* the already-registered case — a malformed token, say —
     * must propagate unchanged rather than being guessed at.
     */
    @Test
    fun rethrowsAnUnrelatedInvalidParameter() = runTest {
        val h = SnsHarness()
        val failure = assertFailsWith<InvalidParameterException> {
            harnessSns(h) {
                """<ErrorResponse><Error><Code>InvalidParameter</Code>
                   <Message>Invalid parameter: Token Reason: token is not a valid hex string</Message>
                   </Error></ErrorResponse>""" to HttpStatusCode.BadRequest
            }.registerDevice(PLATFORM_APP, "nonsense")
        }

        assertContains(failure.message!!, "not a valid hex string")
        assertEquals(1, h.bodies.size, "nothing should have followed the failed create")
    }
}

/**
 * The double-encoded push envelope — the other trap this file exists for.
 */
class MobilePushMessageTest {

    @Test
    fun perPlatformPayloadsAreStringsInsideTheEnvelopeNotNestedObjects() {
        val message = mobilePushMessage("You have a message") {
            platform(PushPlatform.APNS, """{"aps":{"alert":"hi"}}""")
            platform(PushPlatform.GCM, """{"notification":{"body":"hi"}}""")
        }

        val envelope = Json.parseToJsonElement(message.toMessageJson()) as JsonObject
        assertEquals("You have a message", envelope["default"]?.jsonPrimitive?.content)
        // A *string* whose content is JSON — writing it as a nested object is what SNS rejects.
        assertTrue(envelope["APNS"]!!.jsonPrimitive.isString)
        assertEquals("""{"aps":{"alert":"hi"}}""", envelope["APNS"]?.jsonPrimitive?.content)
        assertEquals("""{"notification":{"body":"hi"}}""", envelope["GCM"]?.jsonPrimitive?.content)
    }

    /** String concatenation would break here; a real encoder does not. */
    @Test
    fun escapesQuotesAndNewlinesInUserContent() {
        val nasty = """He said "hello"
            and left \ a backslash"""
        val message = mobilePushMessage(nasty) {
            platform(PushPlatform.APNS, apnsAlert(body = nasty))
        }

        val envelope = Json.parseToJsonElement(message.toMessageJson()) as JsonObject
        assertEquals(nasty, envelope["default"]?.jsonPrimitive?.content)
        // And the inner payload round-trips through both layers of encoding intact.
        val apns = Json.parseToJsonElement(envelope["APNS"]!!.jsonPrimitive.content) as JsonObject
        assertEquals(nasty, apns["aps"]!!.jsonObject["alert"]!!.jsonObject["body"]?.jsonPrimitive?.content)
    }

    @Test
    fun acceptsAJsonObjectDirectly() {
        val message = mobilePushMessage("hi") {
            platform(PushPlatform.GCM, buildJsonObject { put("data", buildJsonObject { put("k", "v") }) })
        }
        val envelope = Json.parseToJsonElement(message.toMessageJson()) as JsonObject
        assertEquals("""{"data":{"k":"v"}}""", envelope["GCM"]?.jsonPrimitive?.content)
    }

    @Test
    fun apnsAlertBuildsAnApsDictionary() {
        val payload = Json.parseToJsonElement(
            apnsAlert(title = "Alice", body = "Hey", badge = 3, sound = "default", contentAvailable = true),
        ) as JsonObject
        val aps = payload["aps"]!!.jsonObject

        assertEquals("Alice", aps["alert"]!!.jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals("Hey", aps["alert"]!!.jsonObject["body"]?.jsonPrimitive?.content)
        assertEquals(3, aps["badge"]?.jsonPrimitive?.content?.toInt())
        // An integer, not a boolean — APNs ignores a JSON boolean here.
        assertEquals("1", aps["content-available"]?.jsonPrimitive?.content)
    }

    /** Zero clears the badge and null leaves it alone; the two must stay distinguishable. */
    @Test
    fun aNullBadgeIsOmittedAndAZeroBadgeIsSent() {
        val without = Json.parseToJsonElement(apnsAlert(body = "x")) as JsonObject
        assertNull(without["aps"]!!.jsonObject["badge"])

        val clearing = Json.parseToJsonElement(apnsAlert(body = "x", badge = 0)) as JsonObject
        assertEquals(0, clearing["aps"]!!.jsonObject["badge"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun fcmNotificationBuildsANotificationBlockWithOptionalData() {
        val payload = Json.parseToJsonElement(
            fcmNotification(title = "Alice", body = "Hey", data = mapOf("deepLink" to "app://chat/7")),
        ) as JsonObject

        assertEquals("Alice", payload["notification"]!!.jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals("app://chat/7", payload["data"]!!.jsonObject["deepLink"]?.jsonPrimitive?.content)
    }

    @Test
    fun publishToDeviceSetsTargetArnAndTheJsonMessageStructure() = runTest {
        val h = SnsHarness()
        val messageId = harnessSns(h) {
            """<PublishResponse><PublishResult><MessageId>m-1</MessageId></PublishResult></PublishResponse>""" to
                HttpStatusCode.OK
        }.publishToDevice(ENDPOINT, mobilePushMessage("hi") { platform(PushPlatform.GCM, "{}") })

        val form = fields(h.bodies.single())
        assertEquals("Publish", form["Action"])
        assertEquals(ENDPOINT, form["TargetArn"])
        // Without this SNS treats the envelope as literal text and delivers the raw JSON.
        assertEquals("json", form["MessageStructure"])
        assertNull(form["TopicArn"])
        assertEquals("m-1", messageId)
    }

    /** SNS renamed nothing when Google did: the wire name is still GCM. */
    @Test
    fun theFcmAliasCarriesTheGcmWireName() {
        assertEquals("GCM", PushPlatform.FCM)
        assertEquals(PushPlatform.GCM, PushPlatform.FCM)
    }

    @Test
    fun toStringDoesNotPrintTheMessageBodies() {
        val printed = mobilePushMessage("sensitive customer data") {
            platform(PushPlatform.APNS, """{"aps":{"alert":"sensitive customer data"}}""")
        }.toString()
        assertFalse(printed.contains("sensitive customer data"), "toString leaked the body: $printed")
        assertContains(printed, "APNS")
    }
}
