package com.steamstreet.awskt.scheduler

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request and response types for EventBridge Scheduler.
 *
 * ### Nothing here carries a `ByteArray`, so everything is a `data class`
 *
 * Stated because the other modules in this library all have an exception to note and this one does
 * not. Scheduler's payloads are JSON documents and strings throughout — [Target.input] is a
 * **string containing JSON**, which is the service's own contract, not a modelling shortcut.
 *
 * ### Dates are raw epoch-seconds `Double`s
 *
 * As in `aws-secretsmanager`, and for the reason `aws-s3` states once for its whole model:
 * date-shaped values are passed through unparsed, so no parse failure can fail a response.
 * restJson1 encodes a timestamp as epoch seconds with a fractional part, which arrives as a
 * `Double`; each one has an `…EpochMillis` computed neighbour for callers who want a number they
 * can do arithmetic on.
 *
 * ### Two target parameter blocks are `JsonElement`, not typed
 *
 * [Target.ecsParameters] and [Target.sageMakerPipelineParameters] are passed through as raw JSON.
 * Modelling them properly means carrying ECS's entire task-launch surface — VPC configuration,
 * placement constraints and strategies, capacity provider strategies, tag propagation — which is
 * some forty types that exist to describe a *different service*, and which this module would then
 * own and have to track. The other five parameter blocks are two or three fields each and are
 * typed normally.
 *
 * The passthrough is honest rather than lossy: a caller targeting ECS builds the object with
 * `buildJsonObject { }` and every field AWS accepts is available, today and after AWS adds the next
 * one. What is given up is compile-time checking on those two blocks specifically.
 */

/** `State` values for a schedule. Strings rather than an enum — see `aws-kms`'s Model.kt for why. */
public object ScheduleState {
    public const val ENABLED: String = "ENABLED"
    public const val DISABLED: String = "DISABLED"
}

/** `ActionAfterCompletion` values. [DELETE] makes a one-shot schedule clean up after itself. */
public object ActionAfterCompletion {
    public const val NONE: String = "NONE"

    /**
     * Delete the schedule once it has run.
     *
     * The setting that makes a one-time schedule — `at(2026-01-01T00:00:00)` — not accumulate.
     * Without it, every one-shot schedule ever created stays in the account forever, and the
     * account-wide schedule quota is the thing that eventually notices.
     */
    public const val DELETE: String = "DELETE"
}

/** `FlexibleTimeWindow.Mode` values. */
public object FlexibleTimeWindowMode {
    public const val OFF: String = "OFF"
    public const val FLEXIBLE: String = "FLEXIBLE"
}

/**
 * How much slack Scheduler may take in when it actually fires.
 *
 * **Required on every schedule**, which is why [off] exists — a caller who does not care still has
 * to say so. [FLEXIBLE][FlexibleTimeWindowMode.FLEXIBLE] lets Scheduler spread invocations over a
 * window, which is how a fleet of schedules avoids all firing on the same second and stampeding
 * whatever they target.
 *
 * @param maximumWindowInMinutes 1–1440. Required when [mode] is `FLEXIBLE`, and rejected when it is
 *   `OFF`.
 */
@Serializable
public data class FlexibleTimeWindow(
    @SerialName("Mode") public val mode: String,
    @SerialName("MaximumWindowInMinutes") public val maximumWindowInMinutes: Int? = null,
) {
    public companion object {
        /** Fire at the scheduled time, with no spreading. The common case. */
        public fun off(): FlexibleTimeWindow = FlexibleTimeWindow(FlexibleTimeWindowMode.OFF)

        /** Spread invocations across [minutes] (1–1440) starting at the scheduled time. */
        public fun flexible(minutes: Int): FlexibleTimeWindow =
            FlexibleTimeWindow(FlexibleTimeWindowMode.FLEXIBLE, minutes)
    }
}

/**
 * What Scheduler does when a target invocation fails.
 *
 * @param maximumEventAgeInSeconds 60–86400. After this, the event is discarded — or sent to
 *   [DeadLetterConfig] if one is set.
 * @param maximumRetryAttempts 0–185.
 */
@Serializable
public data class RetryPolicy(
    @SerialName("MaximumEventAgeInSeconds") public val maximumEventAgeInSeconds: Int? = null,
    @SerialName("MaximumRetryAttempts") public val maximumRetryAttempts: Int? = null,
)

/**
 * Where an invocation goes when it has failed past [RetryPolicy].
 *
 * **Worth setting on anything that matters.** Without one, a target that is failing — a Lambda
 * throwing, a queue that has gone away — produces a schedule that silently drops its invocations,
 * and the only evidence is a CloudWatch metric nobody has an alarm on.
 *
 * @param arn an SQS queue ARN.
 */
@Serializable
public data class DeadLetterConfig(
    @SerialName("Arn") public val arn: String? = null,
)

/** Extra fields when the target is an EventBridge bus. */
@Serializable
public data class EventBridgeParameters(
    @SerialName("DetailType") public val detailType: String,
    @SerialName("Source") public val source: String,
)

/** Extra fields when the target is a FIFO SQS queue. */
@Serializable
public data class SqsParameters(
    @SerialName("MessageGroupId") public val messageGroupId: String? = null,
)

/** Extra fields when the target is a Kinesis stream. */
@Serializable
public data class KinesisParameters(
    @SerialName("PartitionKey") public val partitionKey: String,
)

/**
 * What the schedule invokes.
 *
 * @param arn the target's ARN — a Lambda function, an SQS queue, an EventBridge bus, or one of the
 *   several hundred "universal target" API actions, which take the form
 *   `arn:aws:scheduler:::aws-sdk:sqs:sendMessage`.
 * @param roleArn the role **Scheduler assumes** to make the call. This is the field that most often
 *   makes a correct-looking schedule silently do nothing: the role needs a trust policy allowing
 *   `scheduler.amazonaws.com`, and the failure to have one shows up as an invocation that never
 *   arrives rather than as an error from [Scheduler.createSchedule].
 * @param input the payload, **as a string containing JSON**, not as a JSON object — Scheduler's own
 *   contract, the same shape as EventBridge's `Detail`. For a Lambda target this is the event; for
 *   a universal target it is the API action's parameters.
 * @param ecsParameters raw JSON — see the file KDoc for why this one is not typed.
 * @param sageMakerPipelineParameters raw JSON, likewise.
 */
@Serializable
public data class Target(
    @SerialName("Arn") public val arn: String,
    @SerialName("RoleArn") public val roleArn: String,
    @SerialName("Input") public val input: String? = null,
    @SerialName("RetryPolicy") public val retryPolicy: RetryPolicy? = null,
    @SerialName("DeadLetterConfig") public val deadLetterConfig: DeadLetterConfig? = null,
    @SerialName("EventBridgeParameters") public val eventBridgeParameters: EventBridgeParameters? = null,
    @SerialName("SqsParameters") public val sqsParameters: SqsParameters? = null,
    @SerialName("KinesisParameters") public val kinesisParameters: KinesisParameters? = null,
    @SerialName("EcsParameters") public val ecsParameters: JsonElement? = null,
    @SerialName("SageMakerPipelineParameters")
    public val sageMakerPipelineParameters: JsonElement? = null,
)

// -- CreateSchedule / UpdateSchedule -------------------------------------------------------------

/**
 * `CreateSchedule`.
 *
 * [name] is a **path parameter**, not a body field — it addresses `POST /schedules/{Name}` — which
 * is why this type is not itself `@Serializable` and is converted to a wire form before sending.
 *
 * @param name 1–64 characters of `[0-9a-zA-Z-_.]`. Unique within its group, not within the account.
 * @param scheduleExpression one of `at(yyyy-MM-ddTHH:mm:ss)` for a one-shot, `rate(value unit)`, or
 *   `cron(…)`. **Scheduler's cron has six fields, not five** — the sixth is the year — and a
 *   five-field expression copied from crontab is a `ValidationException`.
 * @param scheduleExpressionTimezone an IANA name such as `America/Los_Angeles`. Defaults to UTC.
 *   Setting it is what makes a `cron` schedule follow daylight saving; leaving it null makes a
 *   "9am daily" schedule drift by an hour twice a year.
 * @param groupName defaults to the `default` group. Groups are the unit of tagging and of the
 *   schedule quota.
 * @param startDate epoch **seconds**, as the wire carries it. Before this, the schedule does not
 *   fire.
 * @param endDate epoch seconds. After this, the schedule does not fire — and is *not* deleted; see
 *   [actionAfterCompletion].
 * @param actionAfterCompletion see [ActionAfterCompletion.DELETE], which is what stops one-shot
 *   schedules accumulating against the account quota.
 * @param clientToken the idempotency token. **Leave it null**: [Scheduler.createSchedule] generates
 *   one per call and reuses it across retries, which is what makes the operation safe to replay.
 */
public data class CreateScheduleRequest(
    public val name: String,
    public val scheduleExpression: String,
    public val target: Target,
    public val flexibleTimeWindow: FlexibleTimeWindow = FlexibleTimeWindow.off(),
    public val groupName: String? = null,
    public val description: String? = null,
    public val scheduleExpressionTimezone: String? = null,
    public val startDate: Double? = null,
    public val endDate: Double? = null,
    public val state: String? = null,
    public val kmsKeyArn: String? = null,
    public val actionAfterCompletion: String? = null,
    public val clientToken: String? = null,
)

/**
 * `UpdateSchedule`.
 *
 * ### This is a replace, not a patch
 *
 * **Every field you do not set is cleared**, not left alone. Scheduler's `UpdateSchedule` overwrites
 * the schedule with exactly what the request describes, so an update that means to change only the
 * expression, and sends only the expression, silently drops the description, the timezone, the
 * retry policy and the dead-letter queue.
 *
 * The safe shape is read-modify-write, and because both types are data classes it is one line:
 *
 * ```kotlin
 * val current = scheduler.getSchedule(GetScheduleRequest("nightly-report"))
 * scheduler.updateSchedule(current.toUpdateRequest().copy(scheduleExpression = "cron(0 3 * * ? *)"))
 * ```
 *
 * [GetScheduleResponse.toUpdateRequest] exists for exactly this and is the reason it exists.
 */
public data class UpdateScheduleRequest(
    public val name: String,
    public val scheduleExpression: String,
    public val target: Target,
    public val flexibleTimeWindow: FlexibleTimeWindow = FlexibleTimeWindow.off(),
    public val groupName: String? = null,
    public val description: String? = null,
    public val scheduleExpressionTimezone: String? = null,
    public val startDate: Double? = null,
    public val endDate: Double? = null,
    public val state: String? = null,
    public val kmsKeyArn: String? = null,
    public val actionAfterCompletion: String? = null,
    public val clientToken: String? = null,
)

/**
 * The JSON body shared by `CreateSchedule` and `UpdateSchedule`.
 *
 * Internal, and separate from the public request types because `Name` rides in the **path** while
 * everything else rides in the body. The same construction as `aws-secretsmanager`'s
 * `PutSecretValueWire`: one place where the two shapes have to agree, rather than a public type
 * carrying a field that must not be serialized.
 */
@Serializable
internal data class ScheduleBody(
    @SerialName("ScheduleExpression") val scheduleExpression: String,
    @SerialName("Target") val target: Target,
    @SerialName("FlexibleTimeWindow") val flexibleTimeWindow: FlexibleTimeWindow,
    @SerialName("GroupName") val groupName: String? = null,
    @SerialName("Description") val description: String? = null,
    @SerialName("ScheduleExpressionTimezone") val scheduleExpressionTimezone: String? = null,
    @SerialName("StartDate") val startDate: Double? = null,
    @SerialName("EndDate") val endDate: Double? = null,
    @SerialName("State") val state: String? = null,
    @SerialName("KmsKeyArn") val kmsKeyArn: String? = null,
    @SerialName("ActionAfterCompletion") val actionAfterCompletion: String? = null,
    @SerialName("ClientToken") val clientToken: String? = null,
)

internal fun CreateScheduleRequest.toBody(token: String): ScheduleBody = ScheduleBody(
    scheduleExpression = scheduleExpression,
    target = target,
    flexibleTimeWindow = flexibleTimeWindow,
    groupName = groupName,
    description = description,
    scheduleExpressionTimezone = scheduleExpressionTimezone,
    startDate = startDate,
    endDate = endDate,
    state = state,
    kmsKeyArn = kmsKeyArn,
    actionAfterCompletion = actionAfterCompletion,
    clientToken = clientToken ?: token,
)

internal fun UpdateScheduleRequest.toBody(token: String): ScheduleBody = ScheduleBody(
    scheduleExpression = scheduleExpression,
    target = target,
    flexibleTimeWindow = flexibleTimeWindow,
    groupName = groupName,
    description = description,
    scheduleExpressionTimezone = scheduleExpressionTimezone,
    startDate = startDate,
    endDate = endDate,
    state = state,
    kmsKeyArn = kmsKeyArn,
    actionAfterCompletion = actionAfterCompletion,
    clientToken = clientToken ?: token,
)

@Serializable
public data class CreateScheduleResponse(
    @SerialName("ScheduleArn") public val scheduleArn: String? = null,
)

@Serializable
public data class UpdateScheduleResponse(
    @SerialName("ScheduleArn") public val scheduleArn: String? = null,
)

// -- GetSchedule ---------------------------------------------------------------------------------

/** `GetSchedule`. [groupName] is a query parameter and defaults to the `default` group. */
public data class GetScheduleRequest(
    public val name: String,
    public val groupName: String? = null,
)

/**
 * `GetSchedule`'s result.
 *
 * @property startDate epoch **seconds** — see [startDateEpochMillis].
 * @property creationDate epoch seconds, likewise.
 */
@Serializable
public data class GetScheduleResponse(
    @SerialName("Arn") public val arn: String? = null,
    @SerialName("Name") public val name: String? = null,
    @SerialName("GroupName") public val groupName: String? = null,
    @SerialName("ScheduleExpression") public val scheduleExpression: String? = null,
    @SerialName("ScheduleExpressionTimezone") public val scheduleExpressionTimezone: String? = null,
    @SerialName("Target") public val target: Target? = null,
    @SerialName("FlexibleTimeWindow") public val flexibleTimeWindow: FlexibleTimeWindow? = null,
    @SerialName("Description") public val description: String? = null,
    @SerialName("State") public val state: String? = null,
    @SerialName("KmsKeyArn") public val kmsKeyArn: String? = null,
    @SerialName("ActionAfterCompletion") public val actionAfterCompletion: String? = null,
    @SerialName("StartDate") public val startDate: Double? = null,
    @SerialName("EndDate") public val endDate: Double? = null,
    @SerialName("CreationDate") public val creationDate: Double? = null,
    @SerialName("LastModificationDate") public val lastModificationDate: Double? = null,
) {
    /** [startDate] as epoch milliseconds, or null when absent. */
    public val startDateEpochMillis: Long? get() = startDate?.let { (it * 1000).toLong() }

    /** [endDate] as epoch milliseconds, or null when absent. */
    public val endDateEpochMillis: Long? get() = endDate?.let { (it * 1000).toLong() }

    /** [creationDate] as epoch milliseconds, or null when absent. */
    public val creationDateEpochMillis: Long? get() = creationDate?.let { (it * 1000).toLong() }

    /** [lastModificationDate] as epoch milliseconds, or null when absent. */
    public val lastModificationDateEpochMillis: Long?
        get() = lastModificationDate?.let { (it * 1000).toLong() }

    /**
     * Turns this schedule into the request that would rewrite it unchanged.
     *
     * **The safe way to edit a schedule**, and the answer to `UpdateSchedule` being a replace rather
     * than a patch — see [UpdateScheduleRequest]. Read, `copy()` the one field being changed, write:
     *
     * ```kotlin
     * val current = scheduler.getSchedule(GetScheduleRequest("nightly-report"))
     * scheduler.updateSchedule(current.toUpdateRequest().copy(state = ScheduleState.DISABLED))
     * ```
     *
     * @throws IllegalStateException if this response is missing [name], [scheduleExpression] or
     *   [target] — the three fields an update cannot omit. A `GetSchedule` on an existing schedule
     *   always carries all three, so this fires only for a hand-constructed response, and failing
     *   here beats sending an update that clears the target.
     */
    public fun toUpdateRequest(): UpdateScheduleRequest = UpdateScheduleRequest(
        name = checkNotNull(name) { "GetScheduleResponse has no name; cannot build an update from it." },
        scheduleExpression = checkNotNull(scheduleExpression) {
            "GetScheduleResponse has no scheduleExpression; cannot build an update from it."
        },
        target = checkNotNull(target) {
            "GetScheduleResponse has no target; cannot build an update from it."
        },
        flexibleTimeWindow = flexibleTimeWindow ?: FlexibleTimeWindow.off(),
        groupName = groupName,
        description = description,
        scheduleExpressionTimezone = scheduleExpressionTimezone,
        startDate = startDate,
        endDate = endDate,
        state = state,
        kmsKeyArn = kmsKeyArn,
        actionAfterCompletion = actionAfterCompletion,
    )
}

// -- DeleteSchedule ------------------------------------------------------------------------------

/**
 * `DeleteSchedule`. [groupName] and [clientToken] are query parameters.
 *
 * @param clientToken **leave it null** — [Scheduler.deleteSchedule] generates one per call.
 */
public data class DeleteScheduleRequest(
    public val name: String,
    public val groupName: String? = null,
    public val clientToken: String? = null,
)

// -- ListSchedules -------------------------------------------------------------------------------

/**
 * `ListSchedules`.
 *
 * @param groupName sent as the `ScheduleGroup` query parameter — **not** `GroupName`, which is what
 *   `GetSchedule` and `DeleteSchedule` call the same concept (as `groupName`, lowercased). The
 *   three spellings are the service's, verified against its own SDK's serializers, and this is
 *   exactly the sort of thing that is wrong for a year before anyone notices.
 * @param maxResults 1–100.
 */
public data class ListSchedulesRequest(
    public val groupName: String? = null,
    public val namePrefix: String? = null,
    public val state: String? = null,
    public val maxResults: Int? = null,
    public val nextToken: String? = null,
)

/** A schedule as it appears in a listing — a summary, not the full definition. */
@Serializable
public data class ScheduleSummary(
    @SerialName("Arn") public val arn: String? = null,
    @SerialName("Name") public val name: String? = null,
    @SerialName("GroupName") public val groupName: String? = null,
    @SerialName("State") public val state: String? = null,
    @SerialName("Target") public val target: TargetSummary? = null,
    @SerialName("CreationDate") public val creationDate: Double? = null,
    @SerialName("LastModificationDate") public val lastModificationDate: Double? = null,
)

/** The cut-down target a listing carries. The full [Target] needs a `GetSchedule`. */
@Serializable
public data class TargetSummary(
    @SerialName("Arn") public val arn: String? = null,
)

/**
 * `ListSchedules`' result.
 *
 * **[nextToken] non-null means there are more pages**, and it can be non-null on a page that came
 * back short or even empty. See [Scheduler.listAllSchedules], which handles the pagination.
 */
@Serializable
public data class ListSchedulesResponse(
    @SerialName("Schedules") public val schedules: List<ScheduleSummary> = emptyList(),
    @SerialName("NextToken") public val nextToken: String? = null,
)

/** The shape of a Scheduler response that carries nothing. Internal — `deleteSchedule` returns `Unit`. */
@Serializable
internal class EmptyResponse
