package com.steamstreet.awskt.sns

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class SnsHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessSns(
    harness: SnsHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): Sns {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultSns(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("sns", "us-west-2", "https://sns.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = SNS_PROTOCOL,
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

/** Percent-decoding, so a test reads what SNS would read rather than what we happened to write. */
private fun decode(value: String): String {
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

private const val TOPIC = "arn:aws:sns:us-west-2:123456789012:my-topic"

private const val PUBLISH_OK = """<?xml version="1.0"?>
<PublishResponse xmlns="http://sns.amazonaws.com/doc/2010-03-31/">
  <PublishResult><MessageId>msg-1</MessageId></PublishResult>
  <ResponseMetadata><RequestId>req-1</RequestId></ResponseMetadata>
</PublishResponse>"""

class SnsProtocolTest {

    @Test
    fun postsAFormEncodedBodyWithNoTargetHeader() = runTest {
        val h = SnsHarness()
        harnessSns(h) { PUBLISH_OK to HttpStatusCode.OK }.publish(PublishRequest("hello", topicArn = TOPIC))

        val request = h.requests.single()
        // The query protocol has no X-Amz-Target; `Action` in the body identifies the operation.
        assertNull(request.headers["X-Amz-Target"])
        assertEquals(
            "application/x-www-form-urlencoded",
            request.body.contentType?.toString()?.substringBefore(";"),
        )
        assertEquals("POST", request.method.value)
        assertEquals("/", request.url.encodedPath)
    }

    @Test
    fun namesTheActionAndTheApiVersion() = runTest {
        val h = SnsHarness()
        harnessSns(h) { PUBLISH_OK to HttpStatusCode.OK }.publish(PublishRequest("hello", topicArn = TOPIC))

        val fields = formFields(h.bodies.single())
        assertEquals("Publish", fields["Action"])
        // Without Version the query protocol answers MissingParameter on every call.
        assertEquals("2010-03-31", fields["Version"])
        assertEquals(TOPIC, fields["TopicArn"])
        assertEquals("hello", fields["Message"])
    }

    /**
     * The query protocol percent-encodes with SigV4's unreserved set, so a space is `%20` and not
     * the `+` that HTML form submission uses. Sending `+` delivers a literal plus sign.
     */
    @Test
    fun percentEncodesSpacesRatherThanUsingPlus() = runTest {
        val h = SnsHarness()
        harnessSns(h) { PUBLISH_OK to HttpStatusCode.OK }
            .publish(PublishRequest("hello world & goodbye", topicArn = TOPIC))

        val raw = h.bodies.single()
        assertContains(raw, "hello%20world%20%26%20goodbye")
        assertFalse(raw.contains("hello+world"), "a space must not be encoded as +")
        // And it round-trips to exactly what the caller wrote.
        assertEquals("hello world & goodbye", formFields(raw)["Message"])
    }

    @Test
    fun omitsAbsentFieldsEntirely() = runTest {
        val h = SnsHarness()
        harnessSns(h) { PUBLISH_OK to HttpStatusCode.OK }.publish(PublishRequest("hello", topicArn = TOPIC))

        assertEquals(setOf("Action", "Version", "TopicArn", "Message"), formFields(h.bodies.single()).keys)
    }

    /**
     * `MessageAttributes.entry.N` is **1-based**. Zero-based would not fail loudly — SNS reads the
     * indices it recognises and ignores the rest, so the first attribute would silently vanish, and
     * with it any subscription filter policy that matched on it.
     */
    @Test
    fun flattensMessageAttributesFromIndexOne() = runTest {
        val h = SnsHarness()
        harnessSns(h) { PUBLISH_OK to HttpStatusCode.OK }.publish(
            PublishRequest(
                "hello", topicArn = TOPIC,
                messageAttributes = linkedMapOf(
                    "kind" to MessageAttributeValue("String", stringValue = "order"),
                    "blob" to MessageAttributeValue("Binary", binaryValue = "hello".encodeToByteArray()),
                ),
            ),
        )

        val fields = formFields(h.bodies.single())
        assertEquals("kind", fields["MessageAttributes.entry.1.Name"])
        assertEquals("String", fields["MessageAttributes.entry.1.Value.DataType"])
        assertEquals("order", fields["MessageAttributes.entry.1.Value.StringValue"])
        assertEquals("blob", fields["MessageAttributes.entry.2.Name"])
        assertEquals("aGVsbG8=", fields["MessageAttributes.entry.2.Value.BinaryValue"])
        assertFalse(
            fields.keys.any { it.contains(".entry.0.") },
            "attribute indices must start at 1, not 0",
        )
    }

    @Test
    fun readsTheMessageIdOutOfTheXmlResponse() = runTest {
        val response = harnessSns(SnsHarness()) { PUBLISH_OK to HttpStatusCode.OK }
            .publish(PublishRequest("hello", topicArn = TOPIC))
        assertEquals("msg-1", response.messageId)
        assertNull(response.sequenceNumber)
    }

    @Test
    fun readsAFifoSequenceNumber() = runTest {
        val response = harnessSns(SnsHarness()) {
            """<PublishResponse><PublishResult><MessageId>m</MessageId>
               <SequenceNumber>10000000000000000001</SequenceNumber></PublishResult></PublishResponse>""" to
                HttpStatusCode.OK
        }.publish(PublishRequest("hello", topicArn = TOPIC, messageDeduplicationId = "d", messageGroupId = "g"))

        assertEquals("10000000000000000001", response.sequenceNumber)
    }
}

class PublishDestinationTest {

    /** SNS answers both "no destination" and "two destinations" with the same unhelpful error. */
    @Test
    fun rejectsARequestWithNoDestination() = runTest {
        val h = SnsHarness()
        val failure = assertFailsWith<IllegalArgumentException> {
            harnessSns(h) { PUBLISH_OK to HttpStatusCode.OK }.publish(PublishRequest("hello"))
        }
        assertContains(failure.message!!, "no destination")
        assertTrue(h.requests.isEmpty(), "nothing should have been sent")
    }

    @Test
    fun rejectsARequestWithTwoDestinations() = runTest {
        val h = SnsHarness()
        val failure = assertFailsWith<IllegalArgumentException> {
            harnessSns(h) { PUBLISH_OK to HttpStatusCode.OK }
                .publish(PublishRequest("hello", topicArn = TOPIC, phoneNumber = "+15551234567"))
        }
        assertContains(failure.message!!, "2 destinations")
        assertTrue(h.requests.isEmpty(), "nothing should have been sent")
    }

    @Test
    fun acceptsEachDestinationKindOnItsOwn() = runTest {
        val h = SnsHarness()
        val sns = harnessSns(h) { PUBLISH_OK to HttpStatusCode.OK }
        sns.publish(PublishRequest("a", topicArn = TOPIC))
        sns.publish(PublishRequest("b", targetArn = "arn:aws:sns:us-west-2:1:endpoint/GCM/app/x"))
        sns.publish(PublishRequest("c", phoneNumber = "+15551234567"))
        assertEquals(3, h.requests.size)
    }

    /** A phone number is always personal data, and a request object is routinely logged. */
    @Test
    fun toStringRedactsThePhoneNumberAndNeverPrintsTheMessage() {
        val printed = PublishRequest("sensitive customer data", phoneNumber = "+15551234567").toString()
        assertFalse(printed.contains("15551234567"), "toString leaked a phone number: $printed")
        assertFalse(printed.contains("sensitive customer data"), "toString leaked the message: $printed")
        assertContains(printed, "<redacted>")
    }
}

class SnsErrorMappingTest {

    private fun errorXml(code: String) = """<?xml version="1.0"?>
        <ErrorResponse xmlns="http://sns.amazonaws.com/doc/2010-03-31/">
          <Error><Type>Sender</Type><Code>$code</Code><Message>nope</Message></Error>
          <RequestId>req-1</RequestId>
        </ErrorResponse>"""

    @Test
    fun mapsTheQueryProtocolsOwnCodesRatherThanShapeNames() = runTest {
        suspend fun failingWith(code: String): Throwable = runCatching {
            harnessSns(SnsHarness()) { errorXml(code) to HttpStatusCode.BadRequest }
                .publish(PublishRequest("hello", topicArn = TOPIC))
        }.exceptionOrNull()!!

        // `NotFound`, not `NotFoundException` — the query protocol renames its errors.
        assertTrue(failingWith("NotFound") is NotFoundException)
        assertTrue(failingWith("AuthorizationError") is AuthorizationErrorException)
        assertTrue(failingWith("InvalidParameter") is InvalidParameterException)
        // Note the reversal: the shape is InvalidParameterValue, the wire code is ParameterValueInvalid.
        assertTrue(failingWith("ParameterValueInvalid") is InvalidParameterValueException)
        assertTrue(failingWith("EndpointDisabled") is EndpointDisabledException)
        assertTrue(failingWith("Throttled") is ThrottledException)
    }

    @Test
    fun collapsesTheKmsFamilyOntoOneTypeWithoutLosingTheCode() = runTest {
        val failure = assertFailsWith<TopicEncryptionKeyException> {
            harnessSns(SnsHarness()) { errorXml("KMSDisabled") to HttpStatusCode.BadRequest }
                .publish(PublishRequest("hello", topicArn = TOPIC))
        }
        // SNS capitalizes the prefix KMS where SQS writes Kms. The services genuinely disagree.
        assertEquals("KMSDisabled", failure.code)
    }

    @Test
    fun unknownCodesFallThroughToTheBaseType() = runTest {
        val failure = assertFailsWith<SnsException> {
            harnessSns(SnsHarness()) { errorXml("SomethingAwsAddedLater") to HttpStatusCode.BadRequest }
                .publish(PublishRequest("hello", topicArn = TOPIC))
        }
        assertEquals("SomethingAwsAddedLater", failure.code)
        assertTrue(failure.cause != null, "the transport exception was discarded")
    }
}

class PublishBatchTest {

    private fun batchXml(successful: List<String>, failed: List<Triple<String, Boolean, String>> = emptyList()) =
        """<PublishBatchResponse><PublishBatchResult>
             <Successful>${successful.joinToString("") {
            "<member><Id>$it</Id><MessageId>m-$it</MessageId></member>"
        }}</Successful>
             <Failed>${failed.joinToString("") { (id, sender, code) ->
            "<member><Id>$id</Id><SenderFault>$sender</SenderFault><Code>$code</Code>" +
                "<Message>because</Message></member>"
        }}</Failed>
           </PublishBatchResult></PublishBatchResponse>"""

    private fun entries(count: Int) =
        (1..count).map { PublishBatchRequestEntry("e$it", "body-$it") }

    @Test
    fun flattensEntriesFromIndexOne() = runTest {
        val h = SnsHarness()
        harnessSns(h) { batchXml(listOf("e1", "e2")) to HttpStatusCode.OK }
            .publishBatch(TOPIC, entries(2))

        val fields = formFields(h.bodies.single())
        assertEquals("PublishBatch", fields["Action"])
        assertEquals("e1", fields["PublishBatchRequestEntries.member.1.Id"])
        assertEquals("body-1", fields["PublishBatchRequestEntries.member.1.Message"])
        assertEquals("e2", fields["PublishBatchRequestEntries.member.2.Id"])
        assertFalse(fields.keys.any { it.contains(".member.0.") }, "member indices must start at 1")
    }

    /**
     * `Successful` and `Failed` are siblings in the XML. A scan for `<member>` over the whole
     * document would read one list's members into both, which is exactly the bug a hand-written
     * reader invites — so the reader scopes to each block first.
     */
    @Test
    fun readsSuccessfulAndFailedWithoutMixingThem() = runTest {
        val response = harnessSns(SnsHarness()) {
            batchXml(listOf("e1"), listOf(Triple("e2", true, "InvalidParameter"))) to HttpStatusCode.OK
        }.publishBatch(TOPIC, entries(2))

        assertEquals(listOf("e1"), response.successful.map { it.id })
        assertEquals("m-e1", response.successful.single().messageId)
        assertEquals(listOf("e2"), response.failed.map { it.id })
        assertTrue(response.failed.single().senderFault)
        assertEquals("InvalidParameter", response.failed.single().code)
    }

    /** SNS spells an empty list as a self-closing tag, which must read as "none" rather than fail. */
    @Test
    fun toleratesSelfClosingAndAbsentLists() = runTest {
        val response = harnessSns(SnsHarness()) {
            """<PublishBatchResponse><PublishBatchResult>
                 <Successful><member><Id>e1</Id><MessageId>m-e1</MessageId></member></Successful>
                 <Failed/>
               </PublishBatchResult></PublishBatchResponse>""" to HttpStatusCode.OK
        }.publishBatch(TOPIC, entries(1))

        assertEquals(1, response.successful.size)
        assertTrue(response.failed.isEmpty())
    }

    @Test
    fun unescapesXmlEntitiesInResponseText() = runTest {
        val response = harnessSns(SnsHarness()) {
            """<PublishBatchResponse><PublishBatchResult><Failed><member>
                 <Id>e1</Id><SenderFault>true</SenderFault><Code>InvalidParameter</Code>
                 <Message>bad &amp; wrong &lt;here&gt;</Message>
               </member></Failed></PublishBatchResult></PublishBatchResponse>""" to HttpStatusCode.OK
        }.publishBatch(TOPIC, entries(1))

        assertEquals("bad & wrong <here>", response.failed.single().message)
    }
}

class PublishAllTest {

    private fun batchXml(successful: List<String>, failed: List<Triple<String, Boolean, String>> = emptyList()) =
        """<PublishBatchResponse><PublishBatchResult>
             <Successful>${successful.joinToString("") {
            "<member><Id>$it</Id><MessageId>m-$it</MessageId></member>"
        }}</Successful>
             <Failed>${failed.joinToString("") { (id, sender, code) ->
            "<member><Id>$id</Id><SenderFault>$sender</SenderFault><Code>$code</Code></member>"
        }}</Failed>
           </PublishBatchResult></PublishBatchResponse>"""

    private fun entries(count: Int) =
        (1..count).map { PublishBatchRequestEntry("e$it", "body-$it") }

    @Test
    fun chunksAtTenEntriesAndReturnsRequestOrder() = runTest {
        val h = SnsHarness()
        val all = entries(23)
        val results = harnessSns(h) { call ->
            val ids = when (call) {
                0 -> (1..10); 1 -> (11..20); else -> (21..23)
            }.map { "e$it" }
            batchXml(ids) to HttpStatusCode.OK
        }.publishAll(TOPIC, all)

        assertEquals(3, h.bodies.size)
        assertEquals(all.map { it.id }, results.map { it.id })
    }

    @Test
    fun resubmitsTransientFailuresOnly() = runTest {
        val h = SnsHarness()
        val results = harnessSns(h) { call ->
            val xml =
                if (call == 0) batchXml(listOf("e1"), listOf(Triple("e2", false, "InternalError")))
                else batchXml(listOf("e2"))
            xml to HttpStatusCode.OK
        }.publishAll(TOPIC, entries(2))

        assertEquals(2, h.bodies.size)
        assertEquals(listOf("e1", "e2"), results.map { it.id })
    }

    @Test
    fun stopsOnASenderFaultAndCarriesThePartition() = runTest {
        val h = SnsHarness()
        val failure = assertFailsWith<PublishBatchPartialFailureException> {
            harnessSns(h) {
                batchXml(listOf("e1"), listOf(Triple("e2", true, "InvalidParameter"))) to HttpStatusCode.OK
            }.publishAll(TOPIC, entries(2))
        }

        assertEquals(1, h.bodies.size, "a sender fault must not be resubmitted")
        assertEquals(200, failure.statusCode)
        // Published messages have been fanned out and cannot be recalled — hence the partition.
        assertEquals(listOf("e1"), failure.succeeded.map { it.id })
        assertEquals(listOf("e2"), failure.failed.map { it.first.id })
        assertEquals("body-2", failure.failed.single().first.message)
    }

    @Test
    fun rejectsDuplicateIdsBeforeSendingAnything() = runTest {
        val h = SnsHarness()
        assertFailsWith<IllegalArgumentException> {
            harnessSns(h) { batchXml(emptyList()) to HttpStatusCode.OK }
                .publishAll(TOPIC, entries(10) + PublishBatchRequestEntry("e1", "again"))
        }
        assertTrue(h.requests.isEmpty(), "nothing should have been sent")
    }

    @Test
    fun sendsNothingForAnEmptyList() = runTest {
        val h = SnsHarness()
        assertTrue(harnessSns(h) { batchXml(emptyList()) to HttpStatusCode.OK }.publishAll(TOPIC, emptyList()).isEmpty())
        assertTrue(h.requests.isEmpty())
    }
}

/** The hand-written reader, exercised directly on the shapes it has to survive. */
class XmlReaderTest {

    @Test
    fun findsTextAtAnyDepth() {
        assertEquals("m-1", Xml.text("<a><b><MessageId>m-1</MessageId></b></a>", "MessageId"))
    }

    @Test
    fun answersNullRatherThanThrowingOnAMissingOrUnclosedTag() {
        assertNull(Xml.text("<a></a>", "MessageId"))
        assertNull(Xml.text("<a><MessageId>oops", "MessageId"))
        assertNull(Xml.block("<a/>", "a"))
        assertTrue(Xml.members(null).isEmpty())
    }

    @Test
    fun readsEveryMemberInOrder() {
        val members = Xml.members("<member><Id>1</Id></member><member><Id>2</Id></member>")
        assertEquals(listOf("1", "2"), members.map { Xml.text(it, "Id") })
    }

    /**
     * Ampersand must be unescaped last: doing it first lets a literal `&amp;lt;` — which means the
     * text "&lt;" — collapse all the way to "<".
     */
    @Test
    fun unescapesAmpersandLast() {
        assertEquals("&lt;", Xml.text("<m>&amp;lt;</m>", "m"))
        assertEquals("a & b", Xml.text("<m>a &amp; b</m>", "m"))
    }
}
