package com.steamstreet.aws.test

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer

/**
 * Manages an instance of the Dynamo local server running via the library.
 */
class DynamoDBLocalRunner(
    val port: Int
) {
    private var server: DynamoDBProxyServer? = null

    private fun start() {
        server = ServerRunner.createServerFromCommandLineArgs(
            arrayOf("-inMemory", "-port", port.toString())
        )
        server?.start()
    }


    private fun stop() {
        server?.stop()
    }
}