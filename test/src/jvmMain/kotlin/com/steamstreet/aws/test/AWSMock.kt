package com.steamstreet.aws.test

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Creates an AWS local environment
 */
public class AWSLocal(
    public val region: String = "us-east-1",
    public val accountId: String = "123412341234"
) {
    private val services = mutableListOf<MockService>()
    private lateinit var job: Job

    /**
     * Are any of the services currently processing data.
     */
    public val processing: Boolean
        get() = services.any {
            it.isProcessing
    }

    public suspend fun start() {
        @Suppress("OPT_IN_USAGE")
        job = GlobalScope.launch {
            services.forEach {
                launch {
                    it.start()
                }
            }
        }
    }

    public suspend fun stop() {
        services.forEach {
            it.stop()
        }
        if (this::job.isInitialized) {
            job.cancel()
        }
    }

    public fun <T : MockService> addService(service: T): T {
        services.add(service)
        return service
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