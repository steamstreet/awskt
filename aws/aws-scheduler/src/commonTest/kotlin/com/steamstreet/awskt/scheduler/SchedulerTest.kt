package com.steamstreet.awskt.scheduler

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class SchedulerHarness {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
}

internal fun harnessScheduler(
    harness: SchedulerHarness,
    responder: (Int) -> Pair<String, HttpStatusCode>,
): Scheduler {
    var call = 0
    val engine = MockEngine { request ->
        harness.requests += request
        harness.bodies += (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
        val (body, status) = responder(call++)
        respond(body, status)
    }
    return DefaultScheduler(
        AwsServiceClient(
            httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
            credentialsProvider = StaticCredentialsProvider(AwsCredentials("AKID", "SECRET", "TOKEN")),
            endpoint = resolveEndpoint("scheduler", "us-west-2", "https://scheduler.us-west-2.amazonaws.com"),
            region = "us-west-2",
            protocol = SCHEDULER_PROTOCOL,
            clock = { 1_700_000_000_000 },
            random = { 1.0 },
            sleep = {},
        ),
    )
}

private fun bodyJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

private val TARGET = Target(
    arn = "arn:aws:lambda:us-west-2:1:function:worker",
    roleArn = "arn:aws:iam::1:role/scheduler",
    input = """{"job":"nightly"}""",
)

private const val CREATE_OK = """{"ScheduleArn":"arn:aws:scheduler:us-west-2:1:schedule/default/nightly"}"""

class SchedulerProtocolTest {

    /**
     * restJson1 addresses by method and path, not by `X-Amz-Target`. A target header here would be
     * ignored by the service and would mean `AwsProtocol.restJson1` had been given a target prefix.
     */
    @Test
    fun addressesByPathWithNoTargetHeader() = runTest {
        val h = SchedulerHarness()
        harnessScheduler(h) { CREATE_OK to HttpStatusCode.OK }
            .createSchedule(CreateScheduleRequest("nightly", "rate(1 day)", TARGET))

        val request = h.requests.single()
        assertNull(request.headers["X-Amz-Target"])
        assertEquals("POST", request.method.value)
        assertEquals("/schedules/nightly", request.url.encodedPath)
        assertEquals("application/json", request.body.contentType?.toString()?.substringBefore(";"))
    }

    @Test
    fun eachOperationUsesItsOwnVerbAndPath() = runTest {
        val h = SchedulerHarness()
        val scheduler = harnessScheduler(h) { CREATE_OK to HttpStatusCode.OK }
        scheduler.createSchedule(CreateScheduleRequest("a", "rate(1 day)", TARGET))
        scheduler.updateSchedule(UpdateScheduleRequest("a", "rate(1 day)", TARGET))
        scheduler.getSchedule(GetScheduleRequest("a"))
        scheduler.deleteSchedule(DeleteScheduleRequest("a"))
        scheduler.listSchedules(ListSchedulesRequest())

        assertEquals(
            listOf("POST", "PUT", "GET", "DELETE", "GET"),
            h.requests.map { it.method.value },
        )
        assertEquals(
            listOf("/schedules/a", "/schedules/a", "/schedules/a", "/schedules/a", "/schedules"),
            h.requests.map { it.url.encodedPath },
        )
    }

    /** `GET` and `DELETE` send no body at all — not `{}`, which would hash and type differently. */
    @Test
    fun bodylessVerbsSendNoBody() = runTest {
        val h = SchedulerHarness()
        val scheduler = harnessScheduler(h) { "{}" to HttpStatusCode.OK }
        scheduler.getSchedule(GetScheduleRequest("a"))
        scheduler.deleteSchedule(DeleteScheduleRequest("a"))

        assertTrue(h.bodies.all { it.isEmpty() }, "a GET or DELETE must not carry a JSON body")
    }

    @Test
    fun percentEncodesTheScheduleNameInThePath() = runTest {
        val h = SchedulerHarness()
        // Scheduler's own charset would never produce this, which is the point: an unvalidated name
        // from user input must not be able to address a different resource.
        harnessScheduler(h) { "{}" to HttpStatusCode.OK }.getSchedule(GetScheduleRequest("a/../b"))

        assertEquals("/schedules/a%2F..%2Fb", h.requests.single().url.encodedPath)
    }

    /**
     * The three spellings of one concept, all the service's own: `groupName` on Get and Delete,
     * `ScheduleGroup` on List. Verified against the AWS SDK's own serializers.
     */
    @Test
    fun usesTheServicesOwnQueryParameterSpellings() = runTest {
        val h = SchedulerHarness()
        val scheduler = harnessScheduler(h) { "{}" to HttpStatusCode.OK }
        scheduler.getSchedule(GetScheduleRequest("a", groupName = "grp"))
        scheduler.listSchedules(ListSchedulesRequest(groupName = "grp", namePrefix = "nightly", maxResults = 50))

        assertEquals("grp", h.requests[0].url.parameters["groupName"])
        assertEquals("grp", h.requests[1].url.parameters["ScheduleGroup"])
        assertNull(h.requests[1].url.parameters["groupName"])
        assertEquals("nightly", h.requests[1].url.parameters["NamePrefix"])
        assertEquals("50", h.requests[1].url.parameters["MaxResults"])
    }

    @Test
    fun serializesTheBodyInPascalCaseAndOmitsNulls() = runTest {
        val h = SchedulerHarness()
        harnessScheduler(h) { CREATE_OK to HttpStatusCode.OK }.createSchedule(
            CreateScheduleRequest(
                name = "nightly",
                scheduleExpression = "cron(0 3 * * ? *)",
                target = TARGET,
                scheduleExpressionTimezone = "America/Los_Angeles",
            ),
        )

        val body = bodyJson(h.bodies.single())
        assertEquals("cron(0 3 * * ? *)", body["ScheduleExpression"]?.jsonPrimitive?.content)
        assertEquals("America/Los_Angeles", body["ScheduleExpressionTimezone"]?.jsonPrimitive?.content)
        assertEquals("OFF", body["FlexibleTimeWindow"]!!.jsonObject["Mode"]?.jsonPrimitive?.content)
        val target = body["Target"]!!.jsonObject
        assertEquals(TARGET.arn, target["Arn"]?.jsonPrimitive?.content)
        // Input is a JSON *string*, not a nested object — Scheduler's contract, same as EventBridge's Detail.
        assertEquals("""{"job":"nightly"}""", target["Input"]?.jsonPrimitive?.content)
        // Name rides in the path and must not also appear in the body.
        assertNull(body["Name"])
        assertNull(body["Description"])
    }

    /** The two untyped parameter blocks pass through as whatever JSON the caller built. */
    @Test
    fun passesEcsParametersThroughAsRawJson() = runTest {
        val h = SchedulerHarness()
        harnessScheduler(h) { CREATE_OK to HttpStatusCode.OK }.createSchedule(
            CreateScheduleRequest(
                "ecs-job", "rate(1 hour)",
                TARGET.copy(
                    ecsParameters = buildJsonObject {
                        put("TaskDefinitionArn", "arn:aws:ecs:us-west-2:1:task-definition/job:1")
                        put("LaunchType", "FARGATE")
                    },
                ),
            ),
        )

        val ecs = bodyJson(h.bodies.single())["Target"]!!.jsonObject["EcsParameters"]!!.jsonObject
        assertEquals("FARGATE", ecs["LaunchType"]?.jsonPrimitive?.content)
    }

    @Test
    fun parsesAScheduleAndItsDates() = runTest {
        val response = harnessScheduler(SchedulerHarness()) {
            """{"Arn":"arn:sched","Name":"nightly","GroupName":"default",
                "ScheduleExpression":"rate(1 day)","State":"ENABLED",
                "Target":{"Arn":"arn:fn","RoleArn":"arn:role"},
                "FlexibleTimeWindow":{"Mode":"OFF"},
                "CreationDate":1.7e9}""" to HttpStatusCode.OK
        }.getSchedule(GetScheduleRequest("nightly"))

        assertEquals("nightly", response.name)
        assertEquals("ENABLED", response.state)
        assertEquals("arn:fn", response.target?.arn)
        // CreationDate is epoch *seconds* on the wire; reading it as millis dates it to 1970.
        assertEquals(1_700_000_000_000, response.creationDateEpochMillis)
    }
}

class ClientTokenTest {

    /**
     * The token is minted once per **call** and reused across retries — the property that makes
     * `CreateSchedule` safe to mark IDEMPOTENT. A token generated per attempt would make each retry
     * a fresh create, and a replay after an ambiguous failure would then raise ConflictException on
     * a write that had in fact landed.
     */
    @Test
    fun reusesOneClientTokenAcrossRetriesOfOneCall() = runTest {
        val h = SchedulerHarness()
        harnessScheduler(h) { call ->
            // A 500 is TRANSIENT in aws-core's status table, so the first attempt is retried.
            if (call == 0) """{"__type":"InternalServerException"}""" to HttpStatusCode.InternalServerError
            else CREATE_OK to HttpStatusCode.OK
        }.createSchedule(CreateScheduleRequest("nightly", "rate(1 day)", TARGET))

        assertEquals(2, h.bodies.size, "expected the 500 to be retried")
        val tokens = h.bodies.map { bodyJson(it)["ClientToken"]?.jsonPrimitive?.content }
        assertEquals(36, tokens[0]?.length, "expected a generated 36-character token")
        assertEquals(tokens[0], tokens[1], "the retry minted a second token and would create twice")
    }

    @Test
    fun honoursACallerSuppliedToken() = runTest {
        val h = SchedulerHarness()
        harnessScheduler(h) { CREATE_OK to HttpStatusCode.OK }.createSchedule(
            CreateScheduleRequest("nightly", "rate(1 day)", TARGET, clientToken = "workflow-step-7"),
        )
        assertEquals(
            "workflow-step-7",
            bodyJson(h.bodies.single())["ClientToken"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun deleteCarriesAClientTokenAsAQueryParameter() = runTest {
        val h = SchedulerHarness()
        harnessScheduler(h) { "{}" to HttpStatusCode.OK }.deleteSchedule(DeleteScheduleRequest("nightly"))

        assertEquals(36, h.requests.single().url.parameters["clientToken"]?.length)
    }
}

class SchedulerErrorMappingTest {

    private fun errorBody(code: String) = """{"__type":"$code","message":"nope"}"""

    @Test
    fun mapsKnownCodesOntoTypedExceptions() = runTest {
        suspend fun failingWith(code: String, status: HttpStatusCode): Throwable = runCatching {
            harnessScheduler(SchedulerHarness()) { errorBody(code) to status }
                .createSchedule(CreateScheduleRequest("a", "rate(1 day)", TARGET))
        }.exceptionOrNull()!!

        assertTrue(failingWith("ConflictException", HttpStatusCode.Conflict) is ConflictException)
        assertTrue(
            failingWith("ResourceNotFoundException", HttpStatusCode.NotFound) is ResourceNotFoundException,
        )
        assertTrue(failingWith("ValidationException", HttpStatusCode.BadRequest) is ValidationException)
        assertTrue(
            failingWith("ServiceQuotaExceededException", HttpStatusCode.BadRequest)
                is ServiceQuotaExceededException,
        )
    }

    /**
     * A restJson1 service normally reports its code in the `x-amzn-errortype` **header** rather than
     * the body, and `AwsJsonErrorParser` reads that first. Asserted because this module relies on
     * it and adds no parser of its own.
     */
    @Test
    fun readsTheCodeFromTheErrorTypeHeader() = runTest {
        val engine = MockEngine {
            respond(
                "{}",
                HttpStatusCode.Conflict,
                io.ktor.http.headersOf("x-amzn-errortype", "ConflictException"),
            )
        }
        val scheduler = DefaultScheduler(
            AwsServiceClient(
                httpClient = HttpClient(engine) { followRedirects = false; expectSuccess = false },
                credentialsProvider = StaticCredentialsProvider(AwsCredentials("A", "S", "T")),
                endpoint = resolveEndpoint("scheduler", "us-west-2", "https://scheduler.us-west-2.amazonaws.com"),
                region = "us-west-2",
                protocol = SCHEDULER_PROTOCOL,
                clock = { 1_700_000_000_000 },
                random = { 1.0 },
                sleep = {},
            ),
        )

        assertFailsWith<ConflictException> {
            scheduler.createSchedule(CreateScheduleRequest("a", "rate(1 day)", TARGET))
        }
    }

    @Test
    fun unknownCodesFallThroughToTheBaseType() = runTest {
        val failure = assertFailsWith<SchedulerException> {
            harnessScheduler(SchedulerHarness()) {
                errorBody("AccessDeniedException") to HttpStatusCode.Forbidden
            }.getSchedule(GetScheduleRequest("a"))
        }
        assertEquals("AccessDeniedException", failure.code)
        assertTrue(failure.cause != null, "the transport exception was discarded")
    }
}

/**
 * `UpdateSchedule` is a replace, not a patch — every omitted field is cleared. `toUpdateRequest`
 * is the round trip that makes editing one field safe.
 */
class ToUpdateRequestTest {

    private val full = GetScheduleResponse(
        arn = "arn:sched",
        name = "nightly",
        groupName = "reports",
        scheduleExpression = "rate(1 day)",
        scheduleExpressionTimezone = "America/Los_Angeles",
        target = TARGET,
        flexibleTimeWindow = FlexibleTimeWindow.flexible(15),
        description = "the nightly report",
        state = ScheduleState.ENABLED,
        actionAfterCompletion = ActionAfterCompletion.NONE,
        creationDate = 1.7e9,
    )

    @Test
    fun carriesEveryFieldAnUpdateWouldOtherwiseClear() {
        val update = full.toUpdateRequest()

        assertEquals("nightly", update.name)
        assertEquals("reports", update.groupName)
        assertEquals("the nightly report", update.description)
        assertEquals("America/Los_Angeles", update.scheduleExpressionTimezone)
        assertEquals(FlexibleTimeWindow.flexible(15), update.flexibleTimeWindow)
        assertEquals(TARGET, update.target)
        assertEquals(ScheduleState.ENABLED, update.state)
    }

    @Test
    fun changingOneFieldLeavesTheRestIntactOnTheWire() = runTest {
        val h = SchedulerHarness()
        harnessScheduler(h) { CREATE_OK to HttpStatusCode.OK }
            .updateSchedule(full.toUpdateRequest().copy(state = ScheduleState.DISABLED))

        val body = bodyJson(h.bodies.single())
        assertEquals("DISABLED", body["State"]?.jsonPrimitive?.content)
        assertEquals("the nightly report", body["Description"]?.jsonPrimitive?.content)
        assertEquals("reports", body["GroupName"]?.jsonPrimitive?.content)
        assertEquals(15, body["FlexibleTimeWindow"]!!.jsonObject["MaximumWindowInMinutes"]
            ?.jsonPrimitive?.content?.toInt())
    }

    /** Failing here beats sending an update that clears the target. */
    @Test
    fun refusesToBuildAnUpdateFromAnIncompleteResponse() {
        val failure = assertFailsWith<IllegalStateException> {
            GetScheduleResponse(name = "nightly", scheduleExpression = "rate(1 day)").toUpdateRequest()
        }
        assertContains(failure.message!!, "target")
    }
}

class SchedulerConvenienceTest {

    @Test
    fun scheduleOnceBuildsAnAtExpressionThatDeletesItself() = runTest {
        val h = SchedulerHarness()
        harnessScheduler(h) { CREATE_OK to HttpStatusCode.OK }
            .scheduleOnce("remind-42", "2026-08-20T17:00:00", TARGET, timezone = "America/New_York")

        val body = bodyJson(h.bodies.single())
        assertEquals("at(2026-08-20T17:00:00)", body["ScheduleExpression"]?.jsonPrimitive?.content)
        // Without DELETE, every one-shot schedule ever created sits against the account quota.
        assertEquals("DELETE", body["ActionAfterCompletion"]?.jsonPrimitive?.content)
        assertEquals("America/New_York", body["ScheduleExpressionTimezone"]?.jsonPrimitive?.content)
    }

    @Test
    fun listAllSchedulesFollowsNextToken() = runTest {
        val h = SchedulerHarness()
        val all = harnessScheduler(h) { call ->
            when (call) {
                0 -> """{"Schedules":[{"Name":"a"}],"NextToken":"page-2"}""" to HttpStatusCode.OK
                else -> """{"Schedules":[{"Name":"b"}]}""" to HttpStatusCode.OK
            }
        }.listAllSchedules()

        assertEquals(listOf("a", "b"), all.map { it.name })
        assertNull(h.requests[0].url.parameters["NextToken"])
        assertEquals("page-2", h.requests[1].url.parameters["NextToken"])
    }

    /** A token that never clears would otherwise loop forever inside a request handler. */
    @Test
    fun listAllSchedulesIsBounded() = runTest {
        val h = SchedulerHarness()
        val all = harnessScheduler(h) {
            """{"Schedules":[{"Name":"a"}],"NextToken":"never-ends"}""" to HttpStatusCode.OK
        }.listAllSchedules(maxPages = 3)

        assertEquals(3, h.requests.size, "pagination was not bounded")
        assertEquals(3, all.size)
    }
}
