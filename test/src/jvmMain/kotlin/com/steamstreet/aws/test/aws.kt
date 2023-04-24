package com.steamstreet.aws.test

import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * Creates an AWS local environment
 */
class AWSLocal(
    val region: String = "us-east-1",
    val accountId: String = "455481184986"
) {
    lateinit var dynamoRunner: DynamoRunner

    val processing: Boolean get() = dynamoRunner.processing || lambda.processing || eventBridge.processing

    val lambda: LocalLambdaClient by lazy {
        LocalLambdaClient()
    }

    val s3: S3Local by lazy {
        S3Local()
    }

    val dynamo: DynamoDbClient by lazy {
        dynamoRunner = DynamoRunner()
        dynamoRunner.client
    }

    val eventBridge: EventBridgeLocal by lazy {
        EventBridgeLocal(lambda)
    }

    fun start() {
        if (this::dynamoRunner.isInitialized) {
            dynamoRunner.startStreamProcessing()
        }
    }

    fun stop() {
        if (this::dynamoRunner.isInitialized) {
            dynamoRunner.stop()
        }
    }

    /**
     * Wait for certain processes to complete before moving on.
     */
    fun waitForProcessing(maxWait: Long = 20000) {
        val start = System.currentTimeMillis()
        Thread.sleep(150)
        // never wait longer than 20 seconds.
        while (System.currentTimeMillis() - start < maxWait) {
            if (processing) {
                Thread.sleep(50)
            } else {
                // sleep, then check again
                Thread.sleep(200)
                if (!processing) return
            }
        }
    }
}