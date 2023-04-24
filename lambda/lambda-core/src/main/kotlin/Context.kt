package com.steamstreet.aws.lambda

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import java.util.*

/**
 * A fake version of the Lambda context that can be useful for some testing or
 * for initializing a function for snap start.
 */
class MockLambdaContext(private val function: String = "Unknown") : Context {
    override fun getAwsRequestId(): String {
        return UUID.randomUUID().toString()
    }

    override fun getLogGroupName(): String {
        return "LogGroup"
    }

    override fun getLogStreamName(): String {
        return "LogStream"
    }

    override fun getFunctionName(): String {
        return function
    }

    override fun getFunctionVersion(): String {
        return "LATEST"
    }

    override fun getInvokedFunctionArn(): String {
        return "arn:aws:lambda:us-west-2:1234:function:${function}"
    }

    override fun getIdentity(): CognitoIdentity? {
        return null
    }

    override fun getClientContext(): ClientContext? {
        return null
    }

    override fun getRemainingTimeInMillis(): Int {
        return 30000
    }

    override fun getMemoryLimitInMB(): Int {
        return 1024
    }

    override fun getLogger(): LambdaLogger {
        return object : LambdaLogger {
            override fun log(message: String?) {
                println(message)
            }

            override fun log(message: ByteArray?) {
                message?.let {
                    log(it.toString(Charsets.UTF_8))
                }
            }
        }
    }
}