package com.steamstreet.awskt.scheduler

import com.steamstreet.awskt.core.AwsCallObserver
import com.steamstreet.awskt.core.AwsCredentialsProvider
import com.steamstreet.awskt.core.AwsHttpTimeouts
import com.steamstreet.awskt.core.AwsProtocol
import com.steamstreet.awskt.core.AwsServiceClient
import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.RetryConfig
import com.steamstreet.awskt.core.awsHttpClient
import com.steamstreet.awskt.core.callRestJson
import com.steamstreet.awskt.core.callRestJsonNoBody
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.awskt.core.randomUuidString
import com.steamstreet.awskt.core.resolveEndpoint
import com.steamstreet.awskt.core.resolveRegion
import com.steamstreet.awskt.signing.sigV4UriEncode
import io.ktor.client.HttpClient

/**
 * EventBridge Scheduler's **restJson1** dialect.
 *
 * The first REST-shaped JSON service in this library. Operations are addressed by method and path
 * (`POST /schedules/{Name}`) rather than by `X-Amz-Target`, which is why this module uses
 * `callRestJson` rather than `callJson` and why [AwsProtocol.restJson1] exists.
 *
 * The endpoint prefix is `scheduler`. Note this is **not** `aws-eventbridge`'s `events` — despite
 * the shared brand these are separate services with separate endpoints, separate protocols and no
 * overlapping operations.
 */
public val SCHEDULER_PROTOCOL: AwsProtocol = AwsProtocol.restJson1(endpointPrefix = "scheduler")

/**
 * An EventBridge Scheduler client.
 *
 * ### "Data plane" needs defining for this service, because it does not have one
 *
 * KMS has cryptographic operations against keys somebody else provisioned; SQS has messages moving
 * through queues somebody else created. Scheduler has no such split — **a schedule is the data**,
 * and applications create schedules at runtime as a matter of course. "Remind this user in three
 * days", "retry this payment tomorrow", "expire this session at midnight" are all
 * `CreateSchedule` calls made by a request handler.
 *
 * So the line drawn here is *per-schedule operations*, which are in scope, versus *schedule group*
 * management (`CreateScheduleGroup`, `DeleteScheduleGroup`, `ListScheduleGroups`) and tagging,
 * which are not: a group is provisioned infrastructure in the way a queue or a topic is. All of
 * those remain reachable through the extension seam below.
 *
 * ### Two things worth knowing before you use it
 *
 * 1. **`UpdateSchedule` is a replace, not a patch.** Every field omitted is cleared. Use
 *    [GetScheduleResponse.toUpdateRequest] and `copy()`.
 * 2. **A schedule that fires into a role Scheduler cannot assume fails silently.** [Target.roleArn]
 *    needs a trust policy naming `scheduler.amazonaws.com`; without one, `createSchedule` succeeds
 *    and the invocation never arrives.
 *
 * ### Extending it
 *
 * [client] is public and no operation below has privileged access to it. Note the seam here uses
 * `callRestJson` and needs an explicit method and path:
 *
 * ```kotlin
 * @Serializable
 * data class CreateScheduleGroupBody(@SerialName("ClientToken") val clientToken: String? = null)
 *
 * @Serializable
 * data class CreateScheduleGroupResponse(@SerialName("ScheduleGroupArn") val arn: String? = null)
 *
 * suspend fun Scheduler.createScheduleGroup(name: String): CreateScheduleGroupResponse =
 *     client.callRestJson(
 *         method = "POST",
 *         path = "/schedule-groups/${sigV4UriEncode(name)}",
 *         request = CreateScheduleGroupBody(),
 *         requestSerializer = CreateScheduleGroupBody.serializer(),
 *         responseSerializer = CreateScheduleGroupResponse.serializer(),
 *         operation = "CreateScheduleGroup",
 *     )
 * ```
 *
 * **Encode interpolated path segments with `sigV4UriEncode`**, as that example does: `callRestJson`
 * signs and sends the path byte-for-byte as given, so a raw name is a signature mismatch at best.
 */
public interface Scheduler : AutoCloseable {
    /** The signed transport. Public because it is the extension seam — see the interface KDoc. */
    public val client: AwsServiceClient

    /**
     * Creates a schedule. `POST /schedules/{Name}`.
     *
     * `IDEMPOTENT`, and the `ClientToken` is what makes it so: one is generated per call **before
     * the first attempt** and reused across every retry, so an ambiguous replay is recognised
     * rather than creating a second schedule. The same construction as `aws-secretsmanager`'s
     * `putSecretValue` and `aws-dynamodb`'s `TransactWriteItems`.
     *
     * Without that token the failure mode would be visible rather than silent — a replay would hit
     * [ConflictException] on the name — but it would be a *lie*: the write landed, and the caller
     * would be told it did not.
     *
     * @throws ConflictException if a schedule of that name already exists in that group.
     */
    public suspend fun createSchedule(request: CreateScheduleRequest): CreateScheduleResponse

    /**
     * Replaces a schedule. `PUT /schedules/{Name}`.
     *
     * **Read [UpdateScheduleRequest] first: omitted fields are cleared, not preserved.**
     */
    public suspend fun updateSchedule(request: UpdateScheduleRequest): UpdateScheduleResponse

    /** Reads a schedule. `GET /schedules/{Name}?groupName=…`. */
    public suspend fun getSchedule(request: GetScheduleRequest): GetScheduleResponse

    /**
     * Deletes a schedule. `DELETE /schedules/{Name}?groupName=…&clientToken=…`. Returns nothing.
     *
     * `IDEMPOTENT`. Deleting an absent schedule raises [ResourceNotFoundException] rather than
     * succeeding quietly, so a caller that does not care whether it existed should catch that.
     */
    public suspend fun deleteSchedule(request: DeleteScheduleRequest)

    /**
     * Lists one page of schedules. `GET /schedules?…`.
     *
     * **[ListSchedulesResponse.nextToken] can be non-null on a short or empty page.** See
     * [listAllSchedules], which follows the pagination.
     */
    public suspend fun listSchedules(request: ListSchedulesRequest): ListSchedulesResponse
}

/** Configuration for [Scheduler]. */
public class SchedulerConfig {
    public var region: String? = null
    public var endpointUrl: String? = null
    public var credentialsProvider: AwsCredentialsProvider? = null

    /**
     * A client to send on, instead of one built here.
     *
     * Supplying one **bypasses [caInfo] and [httpTimeouts]**: those are arguments to the client this
     * factory would have built, and a client the caller already owns is configured by the caller. A
     * caller-supplied client with no `HttpTimeout` plugin has no attempt bound at all.
     */
    public var httpClient: HttpClient? = null
    public var retryConfig: RetryConfig = RetryConfig()

    /** CA bundle for the native Curl engine. Null uses the system trust store. */
    public var caInfo: String? = null

    /**
     * Per-attempt time limits for the client this factory builds. Ignored when [httpClient] is set.
     *
     * The library defaults suit Scheduler: every request and response here is a small JSON document
     * and none of the operations waits on anything. Note the *schedule* firing later has nothing to
     * do with these timeouts — creating a schedule that runs in a year is as fast as creating one
     * that runs in a minute.
     */
    public var httpTimeouts: AwsHttpTimeouts = AwsHttpTimeouts()

    /** Notified of every attempt, retry decision and give-up. Null means no instrumentation. */
    public var observer: AwsCallObserver? = null
}

/** Builds an EventBridge Scheduler client. */
public fun Scheduler(configure: SchedulerConfig.() -> Unit = {}): Scheduler {
    val config = SchedulerConfig().apply(configure)
    val region = resolveRegion(config.region)
    // One binding for the effective client, handed to both the transport and the close path — see
    // the same note in `DynamoDb()`, where splitting the two leaked the client we had just built.
    val httpClient = config.httpClient ?: awsHttpClient(config.caInfo, config.httpTimeouts)
    return DefaultScheduler(
        client = AwsServiceClient(
            httpClient = httpClient,
            credentialsProvider = config.credentialsProvider ?: defaultCredentialsProvider(),
            endpoint = resolveEndpoint("scheduler", region, config.endpointUrl),
            region = region,
            protocol = SCHEDULER_PROTOCOL,
            retryConfig = config.retryConfig,
            observer = config.observer,
        ),
        ownsHttpClient = config.httpClient == null,
        httpClient = httpClient,
    )
}

internal class DefaultScheduler(
    override val client: AwsServiceClient,
    private val ownsHttpClient: Boolean = false,
    private val httpClient: HttpClient? = null,
) : Scheduler {

    override suspend fun createSchedule(request: CreateScheduleRequest): CreateScheduleResponse =
        mapErrors {
            client.callRestJson(
                method = "POST",
                path = schedulePath(request.name),
                // The token is materialized here, BEFORE the retry loop, and reused for every
                // attempt. Generating it per attempt would make each retry a fresh create.
                request = request.toBody(randomUuidString()),
                requestSerializer = ScheduleBody.serializer(),
                responseSerializer = CreateScheduleResponse.serializer(),
                operation = "CreateSchedule",
                safety = OperationSafety.IDEMPOTENT,
            )
        }

    override suspend fun updateSchedule(request: UpdateScheduleRequest): UpdateScheduleResponse =
        mapErrors {
            client.callRestJson(
                method = "PUT",
                path = schedulePath(request.name),
                request = request.toBody(randomUuidString()),
                requestSerializer = ScheduleBody.serializer(),
                responseSerializer = UpdateScheduleResponse.serializer(),
                operation = "UpdateSchedule",
                safety = OperationSafety.IDEMPOTENT,
            )
        }

    override suspend fun getSchedule(request: GetScheduleRequest): GetScheduleResponse = mapErrors {
        client.callRestJsonNoBody(
            method = "GET",
            path = schedulePath(request.name),
            responseSerializer = GetScheduleResponse.serializer(),
            // Lowercase `groupName` here, capitalized `ScheduleGroup` on ListSchedules. Both are
            // the service's own spellings — see ListSchedulesRequest.
            query = listOfNotNull(request.groupName?.let { "groupName" to it }),
            operation = "GetSchedule",
            safety = OperationSafety.IDEMPOTENT,
        )
    }

    override suspend fun deleteSchedule(request: DeleteScheduleRequest) {
        mapErrors {
            client.callRestJsonNoBody(
                method = "DELETE",
                path = schedulePath(request.name),
                responseSerializer = EmptyResponse.serializer(),
                query = listOfNotNull(
                    request.groupName?.let { "groupName" to it },
                    "clientToken" to (request.clientToken ?: randomUuidString()),
                ),
                operation = "DeleteSchedule",
                safety = OperationSafety.IDEMPOTENT,
            )
        }
    }

    override suspend fun listSchedules(request: ListSchedulesRequest): ListSchedulesResponse =
        mapErrors {
            client.callRestJsonNoBody(
                method = "GET",
                path = "/schedules",
                responseSerializer = ListSchedulesResponse.serializer(),
                query = listOfNotNull(
                    request.groupName?.let { "ScheduleGroup" to it },
                    request.namePrefix?.let { "NamePrefix" to it },
                    request.state?.let { "State" to it },
                    request.maxResults?.let { "MaxResults" to it.toString() },
                    request.nextToken?.let { "NextToken" to it },
                ),
                operation = "ListSchedules",
                safety = OperationSafety.IDEMPOTENT,
            )
        }

    override fun close() {
        if (ownsHttpClient) httpClient?.close()
    }
}

/**
 * Builds `/schedules/{Name}` with the name percent-encoded.
 *
 * `callRestJson` signs and sends the path byte-for-byte as given, so encoding is this module's job.
 * Schedule names are restricted to `[0-9a-zA-Z-_.]`, every character of which
 * [sigV4UriEncode] passes through unchanged — so in practice this is a no-op, and it is here for
 * the case where it is not: a name that came from user input and was never validated would
 * otherwise be able to inject a `/` and address a different resource entirely.
 */
private fun schedulePath(name: String): String = "/schedules/${sigV4UriEncode(name)}"

// -- Conveniences --------------------------------------------------------------------------------

/**
 * Every schedule matching the request, following [ListSchedulesResponse.nextToken] to the end.
 *
 * The pagination trap here is the mild one — a `nextToken` on a short page — rather than the
 * partial-failure-inside-a-200 that the batch APIs elsewhere in this library have. It is still
 * worth not writing by hand at every call site, and it still needs a bound: a token that never
 * clears is an infinite loop inside somebody's request handler.
 *
 * @param maxPages a ceiling on requests issued. Reaching it returns what was collected so far
 *   rather than throwing — a truncated *listing* is a legitimate partial answer in a way that a
 *   truncated batch write is not, and the caller can tell by comparing against [maxPages]. If that
 *   ambiguity matters, page manually with [Scheduler.listSchedules].
 */
public suspend fun Scheduler.listAllSchedules(
    request: ListSchedulesRequest = ListSchedulesRequest(),
    maxPages: Int = 100,
): List<ScheduleSummary> {
    val all = mutableListOf<ScheduleSummary>()
    var token: String? = request.nextToken
    var page = 0
    do {
        val response = listSchedules(request.copy(nextToken = token))
        all += response.schedules
        token = response.nextToken
        page++
    } while (token != null && page < maxPages)
    return all
}

/**
 * Creates a one-shot schedule that fires once at [at] and then deletes itself.
 *
 * The shape most application code actually wants from this service, and the one where the
 * boilerplate hides the two mistakes that matter: forgetting
 * [ActionAfterCompletion.DELETE] — which leaves every schedule ever created sitting against the
 * account quota — and forgetting that `FlexibleTimeWindow` is required at all.
 *
 * @param at an ISO-8601 local date-time **without a zone or offset**, as Scheduler's `at()`
 *   expression requires: `2026-08-20T17:00:00`. The zone is [timezone]'s business, not this
 *   string's, and appending a `Z` here is a `ValidationException`.
 * @param timezone an IANA name. Null means UTC.
 */
public suspend fun Scheduler.scheduleOnce(
    name: String,
    at: String,
    target: Target,
    groupName: String? = null,
    timezone: String? = null,
    description: String? = null,
): CreateScheduleResponse = createSchedule(
    CreateScheduleRequest(
        name = name,
        scheduleExpression = "at($at)",
        target = target,
        groupName = groupName,
        scheduleExpressionTimezone = timezone,
        description = description,
        actionAfterCompletion = ActionAfterCompletion.DELETE,
    ),
)
