package com.steamstreet.aws.test

import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import aws.sdk.kotlin.services.dynamodbstreams.model.DescribeStreamResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.DynamoDbStreamsException
import aws.sdk.kotlin.services.dynamodbstreams.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.LimitExceededException
import aws.sdk.kotlin.services.dynamodbstreams.model.ListStreamsResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodbstreams.model.Shard
import aws.sdk.kotlin.services.dynamodbstreams.model.Stream
import aws.sdk.kotlin.services.dynamodbstreams.model.StreamDescription
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * A runner whose shard or stream fails must stop on its own, because a test that aborts before teardown never
 * calls stop(). Before this was fixed, each of these cases spun forever and printed a stack trace per pass.
 */
class DynamoStreamRunnerFailureTest {
    private val streamArn = "arn:aws:dynamodb:us-east-1:123412341234:table/t/stream/1"

    private fun client(): DynamoDbStreamsClient = mockk {
        coEvery { listStreams(any()) } returns ListStreamsResponse {
            streams = listOf(Stream {
                tableName = "t"
                streamArn = this@DynamoStreamRunnerFailureTest.streamArn
            })
        }
        coEvery { getShardIterator(any()) } returns GetShardIteratorResponse { shardIterator = "iterator-1" }
    }

    private fun DynamoDbStreamsClient.describeOnceThenGone() {
        coEvery { describeStream(any()) } returns DescribeStreamResponse {
            streamDescription = StreamDescription { shards = listOf(Shard { shardId = "shard-1" }) }
        } andThenThrows ResourceNotFoundException { message = "Requested resource not found" }
    }

    private fun runner(client: DynamoDbStreamsClient) = DynamoStreamRunner("t", client, { }).apply {
        maxConsecutiveFailures = 4
        initialBackoffMillis = 1
        maxBackoffMillis = 4
    }

    @Test
    fun anInvalidShardIsDroppedAfterOneFailure() = runBlocking {
        val client = client()
        client.describeOnceThenGone()
        coEvery { client.getRecords(any()) } throws
                DynamoDbStreamsException("Invalid ShardId in ShardIterator")

        // start() returns only once the stream reader and every shard reader have ended.
        withTimeout(10_000) { runner(client).start() }

        coVerify(exactly = 1) { client.getRecords(any()) }
    }

    @Test
    fun otherFailuresStopAfterTheRetryCap() = runBlocking {
        val client = client()
        client.describeOnceThenGone()
        coEvery { client.getRecords(any()) } throws LimitExceededException { message = "Slow down" }

        withTimeout(10_000) { runner(client).start() }

        coVerify(exactly = 4) { client.getRecords(any()) }
    }

    @Test
    fun describeStreamFailuresStopAfterTheRetryCap() = runBlocking {
        val client = client()
        coEvery { client.describeStream(any()) } throws LimitExceededException { message = "Slow down" }

        withTimeout(10_000) { runner(client).start() }

        coVerify(exactly = 4) { client.describeStream(any()) }
    }
}
