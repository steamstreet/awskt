package com.steamstreet.awskt.smoke

import com.steamstreet.aws.lambda.lambdaContext
import com.steamstreet.aws.lambda.native.nativeLambda
import com.steamstreet.awskt.dynamodb.DynamoDb
import com.steamstreet.awskt.dynamodb.GetItemRequest
import com.steamstreet.awskt.dynamodb.PutItemRequest
import com.steamstreet.awskt.s3.GetObjectRequest
import com.steamstreet.awskt.s3.PutObjectRequest
import com.steamstreet.awskt.s3.S3
import com.steamstreet.awskt.s3.S3Presigner
import com.steamstreet.awskt.core.defaultCredentialsProvider
import com.steamstreet.dynamokt.AttributeValue
import com.steamstreet.env.Env
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

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

public fun main(): Unit = nativeLambda(
    initialize = {
        // Validated inside `initialize` so a missing variable is reported to
        // POST /runtime/init/error and shows up as an init failure, rather than killing the
        // process with nothing recorded against the function.
        listOf("AWS_REGION", "SMOKE_TABLE_NAME", "SMOKE_BUCKET_NAME").forEach { name ->
            requireNotNull(Env.optional(name)) { "$name is not set" }
        }
    }
) { _ ->
    val region = Env["AWS_REGION"]
    val table = Env["SMOKE_TABLE_NAME"]
    val bucket = Env["SMOKE_BUCKET_NAME"]

    val requestId = lambdaContext.requestId
    val remainingAtStart = lambdaContext.remainingTimeInMillis
    val credentials = defaultCredentialsProvider()

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
