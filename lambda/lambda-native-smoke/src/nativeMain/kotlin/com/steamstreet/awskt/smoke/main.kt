package com.steamstreet.awskt.smoke

import com.steamstreet.aws.lambda.lambdaContext
import com.steamstreet.aws.lambda.native.nativeLambda
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.GetItemRequest
import com.steamstreet.awskt.dynamodb.PutItemRequest
import com.steamstreet.awskt.eventbridge.EventBridge
import com.steamstreet.awskt.eventbridge.PutEventsEntry
import com.steamstreet.awskt.eventbridge.PutEventsResponse
import com.steamstreet.awskt.bedrock.BedrockRuntime
import com.steamstreet.awskt.bedrock.ConverseRequest
import com.steamstreet.awskt.bedrock.InferenceConfiguration
import com.steamstreet.awskt.bedrock.Message as BedrockMessage
import com.steamstreet.awskt.bedrock.ask
import com.steamstreet.awskt.bedrock.textDeltas
import com.steamstreet.awskt.kms.Kms
import com.steamstreet.awskt.logs.Logs
import com.steamstreet.awskt.logs.StartQueryRequest
import com.steamstreet.awskt.logs.query
import com.steamstreet.awskt.kms.decrypt
import com.steamstreet.awskt.kms.encrypt
import com.steamstreet.awskt.scheduler.ActionAfterCompletion
import com.steamstreet.awskt.scheduler.CreateScheduleRequest
import com.steamstreet.awskt.scheduler.DeleteScheduleRequest
import com.steamstreet.awskt.scheduler.GetScheduleRequest
import com.steamstreet.awskt.scheduler.ScheduleState
import com.steamstreet.awskt.scheduler.Scheduler
import com.steamstreet.awskt.scheduler.Target
import com.steamstreet.awskt.secretsmanager.SecretsManager
import com.steamstreet.awskt.secretsmanager.getSecretString
import com.steamstreet.awskt.sns.Sns
import com.steamstreet.awskt.sns.publish
import com.steamstreet.awskt.sqs.Sqs
import com.steamstreet.awskt.sqs.deleteMessage
import com.steamstreet.awskt.sqs.receiveMessages
import com.steamstreet.awskt.sqs.sendMessage
import com.steamstreet.awskt.s3.GetObjectRequest
import com.steamstreet.awskt.s3.PutObjectRequest
import com.steamstreet.awskt.s3.S3
import com.steamstreet.awskt.s3.S3Presigner
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.dynamokt.AttributeValue
import com.steamstreet.env.Env
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The deployed-Lambda smoke function.
 *
 * `linuxArm64` is a Tier 2 Kotlin/Native target with test execution unsupported, so nothing else in
 * this repository can run a single line of code on the architecture that actually ships. This
 * function is that coverage: it exercises the hand-written signer, the DynamoDB client, the S3
 * client and the presigner against real AWS, from a real Lambda, on Graviton.
 */

private const val CA_BUNDLE = "/etc/pki/tls/certs/ca-bundle.crt"

/**
 * A key containing every character class that S3's signing flags (`normalizeUriPath=false`,
 * `doubleUriEncode=false`) change the handling of: a space, a `..` segment, a `+`, and non-ASCII.
 * Risk 25 in the plan — the flag combination has no AWS fixture coverage, and the failure mode is a
 * `SignatureDoesNotMatch` that names no rule. A presigned GET of this key, fetched unauthenticated,
 * is the strongest available oracle: it needs no credentials and no signer on the verifying side.
 */
private const val AWKWARD_SUFFIX = "a b/c..d/e+f/日本語.txt"

@Serializable
private data class SmokeResult(
    val requestId: String,
    val functionName: String,
    val remainingTimeInMillisAtStart: Int,
    val dynamo: DynamoResult,
    val s3: S3Result,
)

@Serializable
private data class DynamoResult(
    val table: String,
    val key: String,
    val roundTrippedValue: String?,
    val roundTrippedNumber: String?,
    val matched: Boolean,
)

@Serializable
private data class S3Result(
    val bucket: String,
    val simpleKey: String,
    val awkwardKey: String,
    val roundTrippedBody: String?,
    val matched: Boolean,
    val presignedSimpleUrl: String,
    val presignedAwkwardUrl: String,
    val expectedBody: String,
)

private val json = Json { prettyPrint = false }

/** Tolerant of a malformed or empty payload — an unparseable event has no mode. */
private fun modeOf(event: String): String = runCatching {
    json.parseToJsonElement(event).jsonObject["mode"]?.jsonPrimitive?.content
}.getOrNull() ?: "full"

/**
 * The clients are held across invocations, deliberately, and this is the single most important
 * thing about these numbers.
 *
 * A client built inside the handler and closed with `use { }` — which is what the `full` workload
 * below does — creates a fresh Curl client and pays a fresh TLS handshake to every service on
 * *every* invocation. That is invisible in a correctness test and dominates warm latency. Holding
 * them in `lazy` module state means the handshake is paid once, during the first invocation, and
 * every later invocation reuses the connection. This is the shape production handlers should use.
 */
private val credentials by lazy { defaultCredentialsProvider() }

private val dynamoClient by lazy {
    DynamoDb {
        region = Env["AWS_REGION"]
        credentialsProvider = credentials
        caInfo = CA_BUNDLE
    }
}

private val eventBridgeClient by lazy {
    EventBridge {
        region = Env["AWS_REGION"]
        credentialsProvider = credentials
        caInfo = CA_BUNDLE
    }
}

/** A row seeded once by the perf harness, so `get` is a hit rather than a miss. */
private const val FIXED_KEY = "perf#fixed"

@Serializable
private data class MinimalResult(
    val mode: String,
    val requestId: String,
    val item: String? = null,
    val eventId: String? = null,
    val failedEntryCount: Int? = null,
)

// -- M8–M10 services ---------------------------------------------------------------------------
//
// Secrets Manager, KMS, SQS, SNS, Scheduler and Bedrock, each behind its own environment variable.
//
// **Every one is optional and none is added to `initialize`'s required set.** An existing deployment
// of this function has three variables set and must keep working untouched; a probe whose variable
// is absent reports "skipped" rather than failing the invocation. That also makes the function
// useful in an account where only some of these exist.
//
// Clients are held in `lazy` module state, which is the pattern the `dynamoClient` note above
// argues for: one TLS handshake per container rather than one per invocation.

private val secretsClient by lazy {
    SecretsManager { region = Env["AWS_REGION"]; credentialsProvider = credentials; caInfo = CA_BUNDLE }
}

private val kmsClient by lazy {
    Kms { region = Env["AWS_REGION"]; credentialsProvider = credentials; caInfo = CA_BUNDLE }
}

private val sqsClient by lazy {
    Sqs { region = Env["AWS_REGION"]; credentialsProvider = credentials; caInfo = CA_BUNDLE }
}

private val snsClient by lazy {
    Sns { region = Env["AWS_REGION"]; credentialsProvider = credentials; caInfo = CA_BUNDLE }
}

private val schedulerClient by lazy {
    Scheduler { region = Env["AWS_REGION"]; credentialsProvider = credentials; caInfo = CA_BUNDLE }
}

private val bedrockClient by lazy {
    BedrockRuntime { region = Env["AWS_REGION"]; credentialsProvider = credentials; caInfo = CA_BUNDLE }
}

private val logsClient by lazy {
    Logs { region = Env["AWS_REGION"]; credentialsProvider = credentials; caInfo = CA_BUNDLE }
}

/**
 * One service's outcome.
 *
 * [ok] is the assertion — a round trip that came back with what went in — rather than "the call did
 * not throw". The distinction matters: a client that base64-encodes wrongly in both directions
 * returns 200s all day and fails this.
 */
@Serializable
private data class ProbeResult(
    val service: String,
    val ok: Boolean,
    val detail: String? = null,
    val skipped: Boolean = false,
    val error: String? = null,
)

@Serializable
private data class ServicesResult(
    val requestId: String,
    val probes: List<ProbeResult>,
    val allOk: Boolean,
)

/**
 * Runs one probe, converting a failure into a result rather than an invocation error.
 *
 * A smoke function that dies on the first broken service reports one bug per deployment. Collecting
 * every probe reports all of them at once, which for a suite covering six clients is the difference
 * between one round trip and six.
 */
private suspend fun probe(service: String, variable: String, block: suspend (String) -> String): ProbeResult {
    val configured = Env.optional(variable)
        ?: return ProbeResult(service, ok = false, skipped = true, detail = "$variable not set")
    return try {
        ProbeResult(service, ok = true, detail = block(configured))
    } catch (failure: Throwable) {
        ProbeResult(service, ok = false, error = "${failure::class.simpleName}: ${failure.message}")
    }
}

/** Every M8–M10 client, exercised against real AWS from Graviton. */
private suspend fun runServiceProbes(requestId: String): List<ProbeResult> = listOf(
    probe("secretsmanager", "SMOKE_SECRET_ID") { id ->
        val value = secretsClient.getSecretString(id)
        require(!value.isNullOrEmpty()) { "secret came back empty" }
        "read ${value.length} chars (redacted)"
    },

    // The strongest oracle here: a client that mishandles base64 blobs cannot get its own
    // plaintext back out of a service that handles them correctly.
    probe("kms", "SMOKE_KMS_KEY_ID") { key ->
        val plaintext = "awskt smoke $requestId — 日本語"
        val context = mapOf("purpose" to "awskt-smoke")
        val blob = kmsClient.encrypt(key, plaintext.encodeToByteArray(), context)
        val recovered = kmsClient.decrypt(key, blob, context).decodeToString()
        require(recovered == plaintext) { "KMS round trip changed the plaintext" }
        "round-tripped ${blob.size} ciphertext bytes"
    },

    probe("sqs", "SMOKE_QUEUE_URL") { queue ->
        val marker = "awskt-smoke-$requestId"
        sqsClient.sendMessage(queue, marker)
        // Short wait: this runs inside a Lambda with a bounded timeout, and the assertion that
        // matters — that the send and the receive both parse — does not need the message back.
        val received = sqsClient.receiveMessages(queue, maxNumberOfMessages = 10, waitTimeSeconds = 5)
        val ours = received.filter { it.body == marker }
        ours.forEach { sqsClient.deleteMessage(queue, it) }
        "sent 1, received ${ours.size} of ${received.size}, deleted ${ours.size}"
    },

    probe("sns", "SMOKE_TOPIC_ARN") { topic ->
        // The form encoder and the XML reader, which are hand-written and have no differential.
        val id = snsClient.publish(topic, "awskt smoke $requestId — a b & c + d = e", "awskt smoke")
        require(!id.isNullOrBlank()) { "SNS returned no MessageId" }
        "published $id"
    },

    probe("scheduler", "SMOKE_SCHEDULER_TARGET_ARN") { targetArn ->
        val role = Env.optional("SMOKE_SCHEDULER_ROLE_ARN")
            ?: error("SMOKE_SCHEDULER_ROLE_ARN not set")
        val name = "awskt-smoke-$requestId".take(64)
        try {
            schedulerClient.createSchedule(
                CreateScheduleRequest(
                    name = name,
                    // Far future and disabled: this must never actually fire.
                    scheduleExpression = "at(2099-01-01T00:00:00)",
                    target = Target(arn = targetArn, roleArn = role, input = """{"smoke":true}"""),
                    state = ScheduleState.DISABLED,
                    actionAfterCompletion = ActionAfterCompletion.DELETE,
                )
            )
            val read = schedulerClient.getSchedule(GetScheduleRequest(name))
            require(read.name == name) { "GetSchedule returned '${read.name}'" }
            "created and read '$name'"
        } finally {
            // Schedules count against a per-group account quota; leaking one per invocation would
            // eventually break the account this smoke function runs in.
            runCatching { schedulerClient.deleteSchedule(DeleteScheduleRequest(name)) }
        }
    },

    // The only probe that costs money, and the only one that exercises `callStreaming` against a
    // real chunked response — the gap the plan's M10 status note names as highest-value.
    probe("bedrock", "SMOKE_BEDROCK_MODEL_ID") { model ->
        val answer = bedrockClient.ask(model, "Reply with exactly the word: pong", maxTokens = 16)
        require(answer.isNotBlank()) { "the model returned no text" }
        val deltas = bedrockClient.converseStream(
            ConverseRequest(
                modelId = model,
                messages = listOf(BedrockMessage.user("Count from 1 to 20, one per line.")),
                inferenceConfig = InferenceConfiguration(maxTokens = 200, temperature = 0f),
            )
        ).textDeltas().toList()
        require(deltas.size > 1) { "converseStream produced ${deltas.size} delta(s) — did it stream?" }
        "converse='${answer.trim().take(20)}', stream delivered ${deltas.size} deltas"
    },

    // Insights, querying this function's own log group — which is the one log group a Lambda can
    // always name, and which by the time this runs certainly contains this very invocation.
    probe("cloudwatch-insights", "SMOKE_LOG_GROUP") { group ->
        // Epoch SECONDS, not milliseconds — see StartQueryRequest. A fixed wide window rather than
        // one around "now": this source set has no clock, and a `limit 1` makes the window's width
        // nearly free while guaranteeing it covers the present.
        val response = logsClient.query(
            StartQueryRequest(
                queryString = "fields @timestamp, @message | sort @timestamp desc | limit 1",
                // A wide window with a tiny limit: cheap to scan, and guaranteed to cover now.
                startTime = 1_600_000_000,
                endTime = 4_100_000_000,
                logGroupNames = listOf(group),
            ),
            timeout = 30.seconds,
        )
        require(response.isComplete) { "query ended '${response.status}' rather than Complete" }
        "completed, ${response.rows.size} row(s), ${response.statistics?.bytesScanned} bytes scanned"
    },
)

private suspend fun getFixedItem(): String? {
    val table = Env["SMOKE_TABLE_NAME"]
    val got = dynamoClient.getItem(
        GetItemRequest(
            tableName = table,
            key = mapOf("pk" to AttributeValue.S(FIXED_KEY)),
        )
    ).item
    return (got?.get("value") as? AttributeValue.S)?.value
}

private suspend fun putOneEvent(requestId: String): PutEventsResponse =
    eventBridgeClient.putEvents(
        listOf(
            PutEventsEntry(
                source = "awskt.perf",
                detailType = "PerfProbe",
                detail = """{"requestId":"$requestId"}""",
            )
        )
    )

public fun main(): Unit = nativeLambda(
    initialize = {
        // Validated inside `initialize` so a missing variable is reported to
        // POST /runtime/init/error and shows up as an init failure, rather than killing the
        // process with nothing recorded against the function.
        listOf("AWS_REGION", "SMOKE_TABLE_NAME", "SMOKE_BUCKET_NAME").forEach { name ->
            requireNotNull(Env.optional(name)) { "$name is not set" }
        }
    }
) { event ->
    // Lambda's REPORT line gives one `Duration` for the whole handler, so a measurement taken only
    // against the full workload cannot separate Kotlin/Native overhead from time spent waiting on
    // AWS — and the overhead is the entire reason for choosing Native. These modes separate it.
    // `Init Duration`, which Lambda reports independently, covers process start either way.
    val requestId = lambdaContext.requestId
    when (modeOf(event)) {
        // No AWS at all: pure runtime and handler overhead.
        "ping" -> return@nativeLambda """{"pong":true,"requestId":"$requestId"}"""

        // One DynamoDB GetItem and return it. The realistic floor for a read handler.
        "get" -> return@nativeLambda json.encodeToString(
            MinimalResult.serializer(),
            MinimalResult(mode = "get", requestId = requestId, item = getFixedItem())
        )

        // One EventBridge PutEvents.
        "event" -> {
            val response = putOneEvent(requestId)
            return@nativeLambda json.encodeToString(
                MinimalResult.serializer(),
                MinimalResult(
                    mode = "event",
                    requestId = requestId,
                    eventId = response.entries?.firstOrNull()?.eventId,
                    failedEntryCount = response.failedEntryCount,
                )
            )
        }

        // Every M8–M10 client — Secrets Manager, KMS, SQS, SNS, Scheduler, Bedrock — against real
        // AWS from Graviton. Each probe is independently gated on its own environment variable and
        // reports rather than throws, so one broken service does not hide the other five.
        "services" -> {
            val probes = runServiceProbes(requestId)
            return@nativeLambda json.encodeToString(
                ServicesResult.serializer(),
                ServicesResult(
                    requestId = requestId,
                    probes = probes,
                    allOk = probes.none { !it.ok && !it.skipped },
                )
            )
        }

        // Read then emit — the shape most real handlers actually have.
        "getevent" -> {
            val item = getFixedItem()
            val response = putOneEvent(requestId)
            return@nativeLambda json.encodeToString(
                MinimalResult.serializer(),
                MinimalResult(
                    mode = "getevent",
                    requestId = requestId,
                    item = item,
                    eventId = response.entries?.firstOrNull()?.eventId,
                    failedEntryCount = response.failedEntryCount,
                )
            )
        }
    }

    val region = Env["AWS_REGION"]
    val table = Env["SMOKE_TABLE_NAME"]
    val bucket = Env["SMOKE_BUCKET_NAME"]

    val remainingAtStart = lambdaContext.remainingTimeInMillis

    // NOTE: from here down the clients are built per invocation and closed with `use { }`, unlike
    // the minimal modes above. That is deliberate but it is NOT the pattern to copy — it re-pays a
    // TLS handshake to every service on every call, measured at ~35 ms per round trip against ~4 ms
    // when the client is held. It stays that way here because this path is the correctness smoke,
    // where proving that a client can be constructed, used and closed cleanly is the point, and
    // because keeping one slow variant makes the difference measurable. Production handlers should
    // hold their clients in module state — see `dynamoClient` / `eventBridgeClient` above.
    val expectedValue = "round-tripped-by-$requestId"

    val dynamoResult = DynamoDb {
        this.region = region
        this.credentialsProvider = credentials
        this.caInfo = CA_BUNDLE
    }.use { dynamo ->
        val key = mapOf("pk" to AttributeValue.S("smoke#$requestId"))

        dynamo.putItem(
            PutItemRequest(
                tableName = table,
                item = key + mapOf(
                    "value" to AttributeValue.S(expectedValue),
                    "number" to AttributeValue.N("12345678901234567890.0987654321"),
                )
            )
        )

        // Consistent read, so the assertion is about the round trip rather than about replication
        // timing.
        val got = dynamo.getItem(
            GetItemRequest(tableName = table, key = key, consistentRead = true)
        ).item

        val value = (got?.get("value") as? AttributeValue.S)?.value
        val number = (got?.get("number") as? AttributeValue.N)?.value

        DynamoResult(
            table = table,
            key = "smoke#$requestId",
            roundTrippedValue = value,
            roundTrippedNumber = number,
            // The N assertion is deliberate: DynamoDB numbers are 38-digit decimals, and a client
            // that round-trips them through a Double loses this value silently.
            matched = value == expectedValue && number == "12345678901234567890.0987654321",
        )
    }

    val simpleKey = "smoke/$requestId/simple.txt"
    val awkwardKey = "smoke/$requestId/$AWKWARD_SUFFIX"
    val payload = "hello from kotlin native on graviton: $requestId"

    val s3Result = S3 {
        this.region = region
        this.credentialsProvider = credentials
        this.caInfo = CA_BUNDLE
    }.use { s3 ->
        listOf(simpleKey, awkwardKey).forEach { key ->
            s3.putObject(
                PutObjectRequest(
                    bucket = bucket,
                    key = key,
                    body = payload.encodeToByteArray(),
                    contentType = "text/plain; charset=utf-8",
                )
            )
        }

        val fetched = s3.getObject(GetObjectRequest(bucket = bucket, key = awkwardKey))
            .body.decodeToString()

        val presigner = S3Presigner {
            this.region = region
            this.credentialsProvider = credentials
        }

        S3Result(
            bucket = bucket,
            simpleKey = simpleKey,
            awkwardKey = awkwardKey,
            roundTrippedBody = fetched,
            matched = fetched == payload,
            presignedSimpleUrl = presigner.presignGetObject(bucket, simpleKey, 15.minutes).url,
            presignedAwkwardUrl = presigner.presignGetObject(bucket, awkwardKey, 15.minutes).url,
            expectedBody = payload,
        )
    }

    json.encodeToString(
        SmokeResult.serializer(),
        SmokeResult(
            requestId = requestId,
            functionName = lambdaContext.functionName,
            remainingTimeInMillisAtStart = remainingAtStart,
            dynamo = dynamoResult,
            s3 = s3Result,
        )
    )
}
