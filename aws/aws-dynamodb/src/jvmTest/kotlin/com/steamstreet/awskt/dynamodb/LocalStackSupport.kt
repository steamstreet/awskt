package com.steamstreet.awskt.dynamodb

import com.steamstreet.awskt.core.StaticCredentialsProvider
import com.steamstreet.awskt.signing.AwsCredentials
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName

/**
 * A LocalStack DynamoDB, started once per JVM and shared by every container-backed suite.
 *
 * ### What this oracle is, and what it is not
 *
 * It **is** a real DynamoDB implementation: it rejects `"ExpressionAttributeValues":{}`, enforces
 * the 25-item `BatchWriteItem` cap, evaluates condition expressions, and applies `ADD` and
 * `list_append` for real. None of that is reachable from a `MockEngine` fixture, which answers
 * whatever it is told to answer — so this is the first oracle in the project that can say the API
 * *works*, rather than that it is *shaped right*.
 *
 * It is **not** a signature oracle. LocalStack accepts `DummyKey`/`DummySecret` and ships with IAM
 * enforcement disabled, so a run here is fully compatible with a signer that fails every production
 * request. That is why `LiveDynamoDbTest` exists and why the plan lists them as separate rows in
 * its validation table. Do not let a green run here be read as closing M3 exit criterion (d).
 *
 * It does, however, exercise one thing real AWS cannot: LocalStack listens on an **ephemeral port**,
 * so every request signs and sends `host:port` rather than a bare host. That is Risk 3 in the plan —
 * a bug there passes every default-port fixture and fails every real request.
 *
 * Started lazily so a developer without Docker still gets a green offline build: suites call
 * [available] and skip themselves. Testcontainers' Ryuk sidecar reaps the container when the JVM
 * exits, so there is nothing to stop by hand.
 */
internal object LocalStack {

    /** False when Docker is not reachable, in which case every container suite self-skips. */
    val available: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    /**
     * Pinned to **4.0**, not the `3.0` the three existing suites use, and the difference is load-bearing.
     *
     * `ReturnValuesOnConditionCheckFailure` reached DynamoDB in December 2023; LocalStack 3.0
     * predates it and silently ignores the field, returning a `ConditionalCheckFailedException`
     * with no `Item`. On 3.0 the conditional-write test fails in a way that looks exactly like a
     * client bug — which is how this pin was found. Verified 2026-08-10: identical test, 3.0 fails
     * and 4.0 passes.
     */
    private val container: LocalStackContainer by lazy {
        LocalStackContainer(DockerImageName.parse("localstack/localstack:4.0"))
            .withServices(LocalStackContainer.Service.DYNAMODB)
            .also { it.start() }
    }

    val endpoint: String
        get() = container.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString()

    /** Our client, pointed at the container. Credentials are fabricated; LocalStack never checks. */
    fun dynamoDb(): DynamoDb = DynamoDb {
        region = "us-east-1"
        endpointUrl = endpoint
        credentialsProvider = StaticCredentialsProvider(AwsCredentials("DummyKey", "DummySecret"))
    }
}
