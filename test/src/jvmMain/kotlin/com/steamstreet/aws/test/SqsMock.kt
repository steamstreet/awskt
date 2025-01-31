package com.steamstreet.aws.test

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.*
import com.steamstreet.strings.toSlug
import io.mockk.mockk
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.collections.ArrayDeque

private class SqsQueue(
    val name: String,
    val url: String
) {
    private val mutex = Mutex()
    val messages: ArrayDeque<Message> = ArrayDeque()
    private var sequence = 0

    suspend fun sendMessage(input: SendMessageRequest): SendMessageResponse {
        return mutex.withLock {
            val message = Message {
                this.messageId = UUID.randomUUID().toString()
                this.body = input.messageBody
                this.messageAttributes = input.messageAttributes
            }

            messages.addLast(message)
            sequence++
            SendMessageResponse {
                this.messageId = message.messageId
                this.sequenceNumber = sequence.toString().padStart(12, '0')
            }
        }
    }
}

public class SqsMock(
    private val mock: SqsClient = mockk<SqsClient>(relaxed = true)
) : SqsClient by mock, MockService {
    private val queues = mutableMapOf<String, SqsQueue>()

    override fun close() {
    }

    override suspend fun createQueue(input: CreateQueueRequest): CreateQueueResponse {
        val slug = input.queueName?.toSlug() ?: throw IllegalArgumentException("Invalid queue name")
        if (queues[slug] != null) {
            throw IllegalArgumentException("Queue exists")
        }

        val queue = SqsQueue(input.queueName!!, "https://queues.com/${slug}")
        queues[queue.url] = queue

        return CreateQueueResponse {
            this.queueUrl = queue.url
        }
    }

    override suspend fun sendMessage(input: SendMessageRequest): SendMessageResponse {
        val queue = queues[input.queueUrl] ?: throw ResourceNotFoundException {
            this.message = "Unknown queue url: ${input.queueUrl}"
        }
        return queue.sendMessage(input)
    }
}