package com.steamstreet.aws.sqs

import com.steamstreet.aws.lambda.MockLambdaContext
import kotlinx.serialization.Serializable
import org.amshove.kluent.shouldBeEqualTo
import org.intellij.lang.annotations.Language
import java.io.ByteArrayOutputStream
import kotlin.test.Test

class SQSTests {
    @Test
    fun testParse() {
        @Language("JSON")
        val message = """{
    "Records": [
        {
            "messageId": "059f36b4-87a3-44ab-83d2-661975830a7d",
            "receiptHandle": "AQEBwJnKyrHigUMZj6rYigCgxlaS3SLy0a...",
            "body": "{\"name\":\"Jon\"}",
            "attributes": {
                "ApproximateReceiveCount": "1",
                "SentTimestamp": "1545082649183",
                "SenderId": "AIDAIENQZJOLO23YVJ4VO",
                "ApproximateFirstReceiveTimestamp": "1545082649185"
            },
            "messageAttributes": {},
            "md5OfBody": "e4e68fb7bd0e697a0ae8f1bb342846b3",
            "eventSource": "aws:sqs",
            "eventSourceARN": "arn:aws:sqs:us-east-2:123456789012:my-queue",
            "awsRegion": "us-east-2"
        }
    ]
}"""

        @Serializable
        class Payload(
            val name: String
        )

        var name: String? = null
        val handler = object : SQSHandler<Payload>(Payload.serializer()) {
            context(SQSRecord) override suspend fun handleMessage(message: Payload) {
                name = message.name
            }
        }

        handler.execute(message.byteInputStream(), ByteArrayOutputStream(), MockLambdaContext())
        name.shouldBeEqualTo("Jon")
    }
}