package com.steamstreet.aws.test

/**
 * Creates an AWS local environment
 */
public class AWSLocal(
    public val region: String = "us-east-1",
    public val accountId: String = "123412341234"
) {
    private val services = mutableListOf<MockService>()

    /**
     * Are any of the services currently processing data.
     */
    public val processing: Boolean
        get() = services.any {
            it.isProcessing
    }

    public suspend fun start() {
        services.forEach { it.start() }
    }

    public suspend fun stop() {
        services.forEach {
            it.stop()
        }
    }

    /**
     * Wait for certain processes to complete before moving on.
     */
    public fun waitForProcessing(maxWait: Long = 20000) {
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

/**
 * Interface for mock services to interact with AWS Local
 */
public interface MockService {
    public suspend fun start() {}

    public suspend fun stop() {}

    public val isProcessing: Boolean get() = false
}